package co.kuznetsov.mailkick.jmap;

import co.kuznetsov.mailkick.model.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Connects to the FastMail JMAP EventSource endpoint, parses StateChange events,
 * calls {@code Email/changes} when the Email state changes, and fans out new email IDs
 * onto all registered signal queues. Reconnects automatically with exponential backoff.
 */
public class EventSourceListener implements Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(
        EventSourceListener.class
    );

    private static final int INITIAL_BACKOFF_MS = 1000;
    private static final int MAX_BACKOFF_MS = 10000;
    private static final int RECONNECT_DELAY_MS = 5000;
    // FastMail's actual ping interval is ~2 minutes regardless of the ping param.
    // Set read timeout to 5 minutes to comfortably outlast any ping interval.
    private static final int PING_INTERVAL_SECONDS = 30;
    private static final int READ_TIMEOUT_SECONDS = 300;
    private static final String SSE_TYPES = "Email";
    private static final String SSE_CLOSE_AFTER = "no";
    private static final String SSE_DATA_PREFIX = "data:";

    private final String bearerToken;
    private final JmapSession session;
    private final EmailFetcher fetcher;
    private final String triageMailboxId;
    private final List<BlockingQueue<String>> signalQueues;
    private final AtomicReference<String> lastEmailState;
    private final AtomicBoolean running;
    private final OkHttpClient httpClient;
    private final Runnable onSuccess;
    private final Consumer<String> onFailure;

    /**
     * Constructs an {@code EventSourceListener}.
     *
     * @param bearerToken     the bearer token used to authenticate SSE requests
     * @param session         the active JMAP session
     * @param fetcher         the email fetcher used to retrieve changes
     * @param triageMailboxId the Triage mailbox ID, used when resetting stale state
     * @param signalQueues    the queues onto which new email IDs are fanned out
     * @param lastEmailState  shared reference to the last known Email state string
     * @param running         shared flag controlling the run loop
     * @param onSuccess       called after each successful connection + catch-up cycle
     * @param onFailure       called with the error message on connection or catch-up failure
     */
    public EventSourceListener(
        String bearerToken,
        JmapSession session,
        EmailFetcher fetcher,
        String triageMailboxId,
        List<BlockingQueue<String>> signalQueues,
        AtomicReference<String> lastEmailState,
        AtomicBoolean running,
        Runnable onSuccess,
        Consumer<String> onFailure
    ) {
        this.bearerToken = bearerToken;
        this.session = session;
        this.fetcher = fetcher;
        this.triageMailboxId = triageMailboxId;
        this.signalQueues = signalQueues;
        this.lastEmailState = lastEmailState;
        this.running = running;
        this.onSuccess = onSuccess;
        this.onFailure = onFailure;
        this.httpClient = new OkHttpClient.Builder()
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build();
    }

    /**
     * Starts the SSE listen loop. Reconnects with exponential backoff on failure.
     * Runs until {@code running} is set to {@code false} or the thread is interrupted.
     */
    public void run() {
        int backoffMs = INITIAL_BACKOFF_MS;
        while (running.get()) {
            try {
                connectOnce();
                // Normal return: server closed the stream cleanly.
                if (catchUp()) {
                    onSuccess.run();
                } else {
                    onFailure.accept(
                        "SSE catch-up failed after connection closed"
                    );
                }
                backoffMs = INITIAL_BACKOFF_MS;
                sleep(RECONNECT_DELAY_MS);
            } catch (EOFException e) {
                LOG.info("SSE stream closed by server, catching up and reconnecting");
                if (catchUp()) {
                    onSuccess.run();
                } else {
                    onFailure.accept("SSE catch-up failed after server close");
                }
                backoffMs = INITIAL_BACKOFF_MS;
                sleep(RECONNECT_DELAY_MS);
            } catch (SocketTimeoutException e) {
                // Read timeout on idle connection — no events arrived, not an error.
                LOG.info("SSE read timeout (no events), catching up and reconnecting");
                if (catchUp()) {
                    onSuccess.run();
                } else {
                    onFailure.accept("SSE catch-up failed after read timeout");
                }
                backoffMs = INITIAL_BACKOFF_MS;
                sleep(RECONNECT_DELAY_MS);
            } catch (IOException e) {
                // Unexpected error — catch up best-effort, report failure, then backoff.
                catchUp();
                String msg =
                    e.getMessage() != null
                        ? e.getMessage()
                        : e.getClass().getSimpleName();
                onFailure.accept(msg);
                LOG.warn("SSE connection error, retrying in {}ms: {}", backoffMs, msg);
                sleep(backoffMs);
                backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
            }
        }
    }

    private void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            running.set(false);
        }
    }

    private boolean catchUp() {
        try {
            String oldState = lastEmailState.get();
            EmailFetcher.EmailChangesResult result = fetcher.getChanges(
                oldState
            );
            lastEmailState.compareAndSet(oldState, result.getNewState());
            int enqueued = fanOut(result.getCreatedIds());
            if (enqueued > 0) {
                LOG.info("[SSE catch-up] {} email signals enqueued", enqueued);
            }
            return true;
        } catch (IOException e) {
            if (isStateOutdated(e)) {
                LOG.info(
                    "SSE catch-up: state is outdated — resetting to current state"
                );
                resetState();
                return true;
            }
            LOG.warn("SSE catch-up failed: {}", e.getMessage());
            return false;
        }
    }

    private void connectOnce() throws IOException {
        String url = session
            .getEventSourceUrl()
            .replace("{types}", SSE_TYPES)
            .replace("{closeafter}", SSE_CLOSE_AFTER)
            .replace("{ping}", String.valueOf(PING_INTERVAL_SECONDS));

        Request request = new Request.Builder()
            .url(url)
            .header("Authorization", "Bearer " + bearerToken)
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException(
                    "SSE connection failed: HTTP " + response.code()
                );
            }
            ResponseBody body = response.body();
            if (body == null) {
                return;
            }
            LOG.info(
                "SSE connection established, listening for StateChange events"
            );
            parseSseStream(body);
        }
    }

    private static boolean isStateOutdated(IOException e) {
        return (
            e.getMessage() != null &&
            e.getMessage().contains("cannotCalculateChanges")
        );
    }

    private void resetState() {
        try {
            String freshState = fetcher.getInitialState();
            lastEmailState.set(freshState);
            List<String> triageEmails = fetcher.queryFolderEmailIds(
                triageMailboxId
            );
            int enqueued = fanOut(triageEmails);
            LOG.info(
                "State reset: freshState={}, {} Triage email(s) enqueued",
                freshState,
                enqueued
            );
        } catch (IOException ex) {
            LOG.warn("State reset failed: {}", ex.getMessage());
        }
    }

    private void parseSseStream(ResponseBody body) throws IOException {
        try (
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(body.byteStream(), StandardCharsets.UTF_8)
            )
        ) {
            StringBuilder dataBuilder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null && running.get()) {
                LOG.debug("SSE raw line: [{}]", line);
                if (line.startsWith(SSE_DATA_PREFIX)) {
                    dataBuilder.append(
                        line.substring(SSE_DATA_PREFIX.length()).trim()
                    );
                } else if (line.isEmpty() && dataBuilder.length() > 0) {
                    LOG.debug("SSE event data: {}", dataBuilder);
                    handleEvent(dataBuilder.toString());
                    dataBuilder.setLength(0);
                }
            }
            LOG.info("SSE stream ended (readLine returned null)");
        }
    }

    private void handleEvent(String data) throws IOException {
        JsonNode event = JsonUtil.OBJECT_MAPPER.readTree(data);

        // FastMail uses "type" (not RFC 8620's "@type").
        // State-change events have type=null or type=state; ping events have type=ping.
        // We identify a relevant event by the presence of "changed" with Email state.
        JsonNode changed = event
            .path("changed")
            .path(session.getPrimaryAccountId());
        if (changed.isMissingNode() || changed.isNull()) {
            LOG.debug("SSE event ignored (no changed block for our account)");
            return;
        }

        String newEmailState = changed.path("Email").asText("");
        if (newEmailState.isEmpty()) {
            LOG.debug("SSE event ignored (no Email state in changed block)");
            return;
        }

        String oldState = lastEmailState.get();
        if (newEmailState.equals(oldState)) {
            LOG.debug(
                "SSE event ignored (Email state unchanged: {})",
                oldState
            );
            return;
        }

        LOG.debug("SSE Email state changed: {} -> {}", oldState, newEmailState);
        EmailFetcher.EmailChangesResult result;
        try {
            result = fetcher.getChanges(oldState);
        } catch (IOException e) {
            if (isStateOutdated(e)) {
                LOG.info(
                    "SSE event: state is outdated — resetting to current state"
                );
                resetState();
                return;
            }
            throw e;
        }
        lastEmailState.compareAndSet(oldState, result.getNewState());
        int enqueued = fanOut(result.getCreatedIds());
        LOG.info(
            "[SSE] StateChange received — {} email signals enqueued",
            enqueued
        );
    }

    private int fanOut(List<String> ids) {
        int count = 0;
        for (String id : ids) {
            for (BlockingQueue<String> queue : signalQueues) {
                if (queue.offer(id)) {
                    count++;
                }
            }
        }
        return count;
    }
}
