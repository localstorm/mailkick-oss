package co.kuznetsov.mailkick.jmap;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Single-threaded consumer that dequeues email IDs from a shared signal queue,
 * confirms each email is still present in the Sent mailbox, and moves it to Inbox
 * marked as read.
 *
 * <p>Intended to be run on a dedicated thread managed by {@link MailKickBootstrap}.
 */
public class SentWorker implements FolderWorker {

    private static final Logger LOG = LoggerFactory.getLogger(SentWorker.class);
    private static final int QUEUE_POLL_TIMEOUT_SECONDS = 5;

    private final EmailFetcher fetcher;
    private final EmailMover mover;
    private final String sentMailboxId;
    private final String inboxMailboxId;
    private final BlockingQueue<String> signalQueue;
    private AtomicBoolean running;

    /**
     * Constructs a new {@code SentWorker}. The {@code running} flag must be set via
     * {@link #setRunning} before the worker is started on a thread.
     *
     * @param fetcher        used to retrieve the current state of each email
     * @param mover          used to move emails and set their read state
     * @param sentMailboxId  the mailbox ID that identifies the Sent folder
     * @param inboxMailboxId the mailbox ID for Inbox
     */
    public SentWorker(
        EmailFetcher fetcher,
        EmailMover mover,
        String sentMailboxId,
        String inboxMailboxId
    ) {
        this.fetcher = fetcher;
        this.mover = mover;
        this.sentMailboxId = sentMailboxId;
        this.inboxMailboxId = inboxMailboxId;
        this.signalQueue = new DeduplicatingBlockingQueue();
    }

    @Override
    public void setRunning(AtomicBoolean running) {
        this.running = running;
    }

    @Override
    public BlockingQueue<String> getSignalQueue() {
        return signalQueue;
    }

    @Override
    public String getMailboxId() {
        return sentMailboxId;
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
        try {
            Optional<JsonNode> emailNode = fetcher.fetchEmailNode(emailId);
            if (emailNode.isEmpty()) {
                LOG.debug("Sent email {} no longer exists, skipping", emailId);
                return;
            }
            if (!emailNode.get().path("mailboxIds").has(sentMailboxId)) {
                LOG.debug("Email {} no longer in Sent folder, skipping", emailId);
                return;
            }
            mover.moveToMailboxAndSetRead(emailId, inboxMailboxId);
            LOG.info("Moved sent email {} to Inbox (read)", emailId);
        } catch (IOException e) {
            LOG.error("Failed to process sent email {}: {}", emailId, e.getMessage());
        }
    }
}
