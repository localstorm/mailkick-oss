package co.kuznetsov.mailkick.jmap;

import java.io.IOException;
import java.util.List;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Polls a folder on a fixed interval and enqueues all currently present email IDs.
 * Acts as a safety net so emails that arrived before or between SSE events are not missed.
 */
public class FolderPoller implements Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(FolderPoller.class);

    private static final int POLL_INTERVAL_MS = 30000;

    private final Random rng;
    private final String folderName;
    private final String mailboxId;
    private final EmailFetcher fetcher;
    private final BlockingQueue<String> signalQueue;
    private final AtomicBoolean running;
    private final Runnable onSuccess;
    private final Consumer<String> onFailure;

    /**
     * Constructs a {@code FolderPoller}.
     *
     * @param folderName  human-readable name used in log messages
     * @param mailboxId   the JMAP mailbox ID to query on each poll cycle
     * @param fetcher     the email fetcher used to query folder contents
     * @param signalQueue the queue onto which email IDs are placed
     * @param running     shared flag controlling the run loop
     * @param onSuccess   called after each successful poll
     * @param onFailure   called with the error message after each failed poll
     */
    public FolderPoller(
        String folderName,
        String mailboxId,
        EmailFetcher fetcher,
        BlockingQueue<String> signalQueue,
        AtomicBoolean running,
        Runnable onSuccess,
        Consumer<String> onFailure
    ) {
        this.folderName = folderName;
        this.mailboxId = mailboxId;
        this.fetcher = fetcher;
        this.signalQueue = signalQueue;
        this.running = running;
        this.onSuccess = onSuccess;
        this.onFailure = onFailure;
        this.rng = new Random(System.nanoTime());
    }

    /**
     * Starts the polling loop, sleeping {@value POLL_INTERVAL_MS}ms + random jitter between each poll.
     * Runs until {@code running} is set to {@code false} or the thread is interrupted.
     */
    @Override
    public void run() {
        while (running.get()) {
            try {
                Thread.sleep(POLL_INTERVAL_MS + rng.nextLong(POLL_INTERVAL_MS));
                if (!running.get()) {
                    break;
                }
                if (signalQueue.isEmpty()) {
                    poll();
                }
                onSuccess.run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                LOG.warn("[{}] Poll failed: {}", folderName, e.getMessage());
                onFailure.accept(e.getMessage());
            }
        }
    }

    private void poll() throws IOException {
        LOG.info("[{}] Poll: starting next poll cycle", folderName);
        List<String> folderIds = fetcher.queryFolderEmailIds(mailboxId);
        int enqueued = 0;
        for (String id : folderIds) {
            if (signalQueue.offer(id)) {
                enqueued++;
            }
        }
        if (enqueued > 0) {
            LOG.info("[{}] Poll: {} email signals enqueued", folderName, enqueued);
        } else {
            LOG.debug("[{}] Poll: folder empty", folderName);
        }
    }
}