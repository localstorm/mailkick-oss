package co.kuznetsov.mailkick.jmap;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Single-threaded consumer that dequeues email IDs from a shared signal queue,
 * confirms each email is still present in the Triage mailbox, and delegates
 * processing to a {@link TriageProcessor}.
 *
 * <p>Intended to be run on a dedicated thread managed by {@link MailKickBootstrap}.
 */
public class TriageWorker implements Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(TriageWorker.class);
    private static final int QUEUE_POLL_TIMEOUT_SECONDS = 5;

    private final EmailFetcher fetcher;
    private final String triageMailboxId;
    private final BlockingQueue<String> signalQueue;
    private final TriageProcessor processor;
    private final AtomicBoolean running;
    private final Consumer<String> onFailure;
    private final Runnable onSuccess;

    /**
     * Constructs a new {@code TriageWorker}.
     *
     * @param fetcher         used to retrieve the current state of each email
     * @param triageMailboxId the mailbox ID that identifies the Triage folder
     * @param signalQueue     shared queue from which email IDs are consumed
     * @param processor       called for each email that is confirmed to be in Triage
     * @param running         shared flag; the loop exits when this becomes {@code false}
     * @param onFailure       called with the error message when a JMAP fetch fails
     * @param onSuccess       called when a JMAP fetch succeeds after a prior failure
     */
    public TriageWorker(
            EmailFetcher fetcher,
            String triageMailboxId,
            BlockingQueue<String> signalQueue,
            TriageProcessor processor,
            AtomicBoolean running,
            Consumer<String> onFailure,
            Runnable onSuccess) {
        this.fetcher = fetcher;
        this.triageMailboxId = triageMailboxId;
        this.signalQueue = signalQueue;
        this.processor = processor;
        this.running = running;
        this.onFailure = onFailure;
        this.onSuccess = onSuccess;
    }

    @Override
    public void run() {
        while (running.get()) {
            try {
                String emailId = signalQueue.poll(QUEUE_POLL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (emailId == null) {
                    continue;
                }
                processSignal(emailId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void processSignal(String emailId) {
        Optional<JsonNode> emailNode = JmapRetry.withRetry(
            "triage.fetch:" + emailId,
            () -> fetcher.fetchEmailNode(emailId),
            onFailure,
            onSuccess
        );

        if (emailNode.isEmpty()) {
            LOG.debug("Email {} no longer exists, skipping", emailId);
            return;
        }

        JsonNode mailboxIds = emailNode.get().path("mailboxIds");

        if (!mailboxIds.has(triageMailboxId)) {
            LOG.debug("Email {} no longer in Triage, skipping", emailId);
            return;
        }

        LOG.info("Processing email: id={}", emailId);
        try {
            processor.process(emailId, emailNode.get());
        } catch (Exception e) {
            LOG.error("Failed to process email {}: {}", emailId, e.getMessage(), e);
        }
    }
}
