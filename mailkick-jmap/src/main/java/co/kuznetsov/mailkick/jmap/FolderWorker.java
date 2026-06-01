package co.kuznetsov.mailkick.jmap;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A worker that consumes email ID signals for a single monitored mailbox.
 */
public interface FolderWorker extends Runnable {

    /**
     * Returns the signal queue that the SSE listener and the folder poller write into.
     *
     * @return the signal queue for this worker
     */
    BlockingQueue<String> getSignalQueue();

    /**
     * Returns the JMAP mailbox ID this worker monitors.
     *
     * @return the JMAP mailbox ID
     */
    String getMailboxId();

    /**
     * Injects the shared running flag from the owning {@link MailKickBootstrap}.
     * Called before the worker thread is started.
     *
     * @param running the shared running flag
     */
    void setRunning(AtomicBoolean running);
}