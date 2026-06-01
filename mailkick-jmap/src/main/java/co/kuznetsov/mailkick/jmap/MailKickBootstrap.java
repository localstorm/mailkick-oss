package co.kuznetsov.mailkick.jmap;

import co.kuznetsov.mailkick.model.MailKickConfig;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wires and starts the Triage signal pipeline: SSE listener, fallback poller, and triage worker.
 * Conditionally starts a {@link SentWorker} based on the runtime config.
 *
 * <p>Call {@link #start} once on application startup and {@link #stop} on shutdown.
 */
public class MailKickBootstrap {

    private static final Logger LOG = LoggerFactory.getLogger(MailKickBootstrap.class);

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final List<Thread> threads = new ArrayList<>();

    /**
     * Starts the monitoring pipeline for the Triage mailbox and optionally the Sent folder.
     *
     * @param bearerToken   the FastMail API bearer token used by the SSE listener
     * @param session       the active JMAP session
     * @param resolver      used to look up mailbox IDs
     * @param fetcher       used to query email state and folder contents
     * @param mover         used by the {@link SentWorker} when ReturnSentToInbox is enabled
     * @param processor     called by the triage worker for each confirmed Triage email
     * @param triageFolder  full path of the Triage folder (e.g. {@code Inbox/Triage})
     * @param config        runtime configuration; drives optional worker setup
     * @param onJmapSuccess called after each successful SSE cycle or poll
     * @param onJmapFailure called with the error message on SSE or poll failure
     * @throws IOException if mailbox IDs or initial state cannot be retrieved
     */
    public void start(
        String bearerToken,
        JmapSession session,
        MailboxResolver resolver,
        EmailFetcher fetcher,
        EmailMover mover,
        TriageProcessor processor,
        String triageFolder,
        MailKickConfig config,
        Runnable onJmapSuccess,
        Consumer<String> onJmapFailure
    ) throws IOException {
        CopyOnWriteArrayList<BlockingQueue<String>> sseQueues = new CopyOnWriteArrayList<>();

        running.set(true);

        String triageId = resolver.getMailboxId(triageFolder);
        String triageInitialState = fetcher.getInitialState();
        AtomicReference<String> triageState = new AtomicReference<>(triageInitialState);
        BlockingQueue<String> triageQueue = new DeduplicatingBlockingQueue();
        sseQueues.add(triageQueue);

        FolderPoller triagePoller = new FolderPoller(
            "triage", triageId, fetcher, triageQueue, running, onJmapSuccess, onJmapFailure);
        TriageWorker triageWorker = new TriageWorker(
            fetcher, triageId, triageQueue, processor, running, onJmapFailure, onJmapSuccess);

        addThread(triagePoller, "mailkick-poller-triage");
        addThread(triageWorker, "mailkick-worker-triage");

        if (config.isReturnSentToInbox()) {
            String sentFolder = config.getSentFolder();
            if (sentFolder != null && !sentFolder.isBlank()) {
                String sentMailboxId = resolver.getMailboxId(sentFolder);
                String inboxMailboxId = resolver.getInboxId();
                SentWorker sentWorker = new SentWorker(fetcher, mover, sentMailboxId, inboxMailboxId);
                sentWorker.setRunning(running);
                BlockingQueue<String> sentQueue = sentWorker.getSignalQueue();
                sseQueues.add(sentQueue);

                FolderPoller sentPoller = new FolderPoller(
                    "sent", sentMailboxId, fetcher, sentQueue, running, onJmapSuccess, onJmapFailure);

                addThread(sentPoller, "mailkick-poller-sent");
                addThread(sentWorker, "mailkick-worker-sent");
                LOG.info("ReturnSentToInbox enabled, sentFolder={}", sentFolder);
            } else {
                LOG.warn("returnSentToInbox is enabled but sentFolder is not configured");
            }
        }

        EventSourceListener sseListener = new EventSourceListener(
            bearerToken, session, fetcher, triageId, sseQueues, triageState, running,
            onJmapSuccess, onJmapFailure);
        addThread(sseListener, "mailkick-sse");

        for (Thread t : threads) {
            t.start();
            LOG.info("Started thread: {}", t.getName());
        }

        LOG.info("MailKickBootstrap started. triageId={}, initialState={}", triageId, triageInitialState);
    }

    private void addThread(Runnable runnable, String name) {
        Thread t = new Thread(runnable, name);
        t.setDaemon(true);
        threads.add(t);
    }

    /**
     * Signals all threads to stop and interrupts each one.
     */
    public void stop() {
        LOG.info("MailKickBootstrap stopping...");
        running.set(false);
        for (Thread t : threads) {
            t.interrupt();
        }
        LOG.info("MailKickBootstrap stopped.");
    }

    /**
     * Returns {@code true} if the monitor has been started and has not yet been stopped.
     *
     * @return the current value of the running flag
     */
    public boolean isRunning() {
        return running.get();
    }
}