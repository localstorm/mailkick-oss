package co.kuznetsov.mailkick.server;

import co.kuznetsov.mailkick.agent.AgentPromptLoader;
import co.kuznetsov.mailkick.agent.AnthropicAgent;
import co.kuznetsov.mailkick.agent.ToolCall;
import co.kuznetsov.mailkick.agent.ToolRegistry;
import co.kuznetsov.mailkick.agent.executor.MoveChainTool;
import co.kuznetsov.mailkick.jmap.EmailFetcher;
import co.kuznetsov.mailkick.jmap.EmailFetcher.EmailSummary;
import co.kuznetsov.mailkick.jmap.EmailMover;
import co.kuznetsov.mailkick.jmap.EmailNormaliser;
import co.kuznetsov.mailkick.jmap.JmapClient;
import co.kuznetsov.mailkick.jmap.JmapRetry;
import co.kuznetsov.mailkick.jmap.JmapSession;
import co.kuznetsov.mailkick.jmap.MailboxResolver;
import co.kuznetsov.mailkick.model.AutoArchiveConfig;
import co.kuznetsov.mailkick.model.Email;
import co.kuznetsov.mailkick.model.HealthComponent;
import co.kuznetsov.mailkick.model.MailKickConfig;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Background task that periodically processes emails in a configured archive staging folder.
 *
 * <p>On each cycle: emails not yet seen have their arrival time recorded in memory, then threads
 * where every email has been settled for at least {@code settlingMinutes} are passed to the LLM
 * which calls {@code move_chain} to file the entire thread into a permanent folder.
 *
 * <p>All per-email AutoArchive state (arrival time, failed-settle attempt count) is tracked
 * in-memory rather than via IMAP keywords. FastMail (Cyrus IMAP) caps each mailbox at a fixed
 * number of distinct custom keyword names ever used, a cap that is never reclaimed even after a
 * keyword is unset from every message — so any keyword-based tracking scheme eventually
 * exhausts it permanently. In-memory tracking means AutoArchive never touches keywords at all.
 * The tradeoff is that a server restart loses in-flight state, giving every email in the folder
 * a fresh settling window.
 */
public final class AutoArchiveRunner {

    private static final Logger LOG = LoggerFactory.getLogger(AutoArchiveRunner.class);

    private static final int RUN_INTERVAL_MS = 120_000;
    private static final int SECONDS_PER_MINUTE = 60;
    private static final int MAX_SETTLE_ATTEMPTS = 3;

    private final AgentPromptLoader promptLoader;
    private final JmapClient jmapClient;
    private final JmapSession jmapSession;
    private final EmailFetcher fetcher;
    private final EmailMover mover;
    private final AnthropicAgent anthropicAgent;
    private final ToolRegistry toolRegistry;
    private final HealthTracker healthTracker;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Map<String, Long> arrivalEpochByEmailId = new ConcurrentHashMap<>();
    private final Map<String, Integer> failedAttemptsByThreadId = new ConcurrentHashMap<>();

    /**
     * Constructs an {@code AutoArchiveRunner} with all required dependencies.
     *
     * @param promptLoader   loader providing live {@link MailKickConfig}
     * @param jmapClient     JMAP client used to refresh the mailbox resolver each run
     * @param jmapSession    active JMAP session used to refresh the mailbox resolver each run
     * @param fetcher        email fetcher for querying and reading emails
     * @param mover          email mover for mailbox operations
     * @param anthropicAgent LLM agent used to decide where to file each thread
     * @param toolRegistry   tool registry for resolving extra tools per prompt
     * @param healthTracker  health tracker for recording JMAP failures and recoveries
     */
    public AutoArchiveRunner(
        AgentPromptLoader promptLoader,
        JmapClient jmapClient,
        JmapSession jmapSession,
        EmailFetcher fetcher,
        EmailMover mover,
        AnthropicAgent anthropicAgent,
        ToolRegistry toolRegistry,
        HealthTracker healthTracker
    ) {
        this.promptLoader = promptLoader;
        this.jmapClient = jmapClient;
        this.jmapSession = jmapSession;
        this.fetcher = fetcher;
        this.mover = mover;
        this.anthropicAgent = anthropicAgent;
        this.toolRegistry = toolRegistry;
        this.healthTracker = healthTracker;
    }

    /** Starts the background scheduling thread. */
    public void start() {
        running.set(true);
        Thread thread = new Thread(this::run, "mailkick-autoarchive");
        thread.setDaemon(false);
        thread.start();
        LOG.info("AutoArchiveRunner started");
    }

    /** Signals the scheduling thread to stop. */
    public void stop() {
        running.set(false);
        LOG.info("AutoArchiveRunner stopped");
    }

    private void run() {
        arrivalEpochByEmailId.clear();
        failedAttemptsByThreadId.clear();

        try {
            executeAutoArchive();
        } catch (Exception e) {
            LOG.error("AutoArchive bootstrap run failed: {}", e.getMessage(), e);
        }

        while (running.get()) {
            try {
                Thread.sleep(RUN_INTERVAL_MS);
                if (!running.get()) {
                    break;
                }
                executeAutoArchive();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                LOG.error("AutoArchive run failed: {}", e.getMessage(), e);
            }
        }
    }

    private void executeAutoArchive() throws IOException {
        MailKickConfig config = promptLoader.getConfig();
        if (!config.isAutoArchiveEnabled()) {
            LOG.info("AutoArchive not configured, skipping");
            return;
        }

        AutoArchiveConfig archiveCfg = config.getAutoArchive();
        int settlingMinutes = archiveCfg.getSettlingMinutes();
        String archivePromptName = archiveCfg.getArchivePromptName();

        LOG.info("AutoArchive starting — settlingMinutes={}", settlingMinutes);

        MailboxResolver resolver = jmapRetry("autoarchive.resolveMailboxes",
            () -> new MailboxResolver(jmapClient, jmapSession));

        String archiveFolderId = jmapRetry("autoarchive.resolveArchiveFolder",
            resolver::getArchiveId);
        List<EmailSummary> allEmails = jmapRetry("autoarchive.queryEmails",
            () -> fetcher.queryAllEmailsInMailbox(archiveFolderId));
        LOG.info("AutoArchive found {} email(s) in archive folder", allEmails.size());

        recordArrivals(allEmails);

        Map<String, List<EmailSummary>> byThread = groupByThread(allEmails);

        long nowSeconds = Instant.now().getEpochSecond();
        long settlingSeconds = (long) settlingMinutes * SECONDS_PER_MINUTE;

        Set<String> processedThreadIds = new HashSet<>();

        for (Map.Entry<String, List<EmailSummary>> entry : byThread.entrySet()) {
            String threadId = entry.getKey();
            if (!processedThreadIds.add(threadId)) {
                continue;
            }
            List<EmailSummary> threadEmails = entry.getValue();
            if (!isThreadSettled(threadEmails, nowSeconds, settlingSeconds)) {
                LOG.debug("Thread {} not yet settled, skipping", threadId);
                continue;
            }
            try {
                processSettledThread(
                    threadId,
                    threadEmails,
                    archiveFolderId,
                    archivePromptName,
                    config,
                    resolver
                );
            } catch (Exception e) {
                LOG.error("AutoArchive failed to process thread {}: {}", threadId, e.getMessage(), e);
                try {
                    recordFailedAttempt(threadId, threadEmails, resolver);
                } catch (IOException ioe) {
                    LOG.error("AutoArchive failed to record attempt for thread {}: {}", threadId, ioe.getMessage(), ioe);
                }
            }
        }
    }

    private void recordArrivals(List<EmailSummary> emails) {
        long nowEpoch = Instant.now().getEpochSecond();
        for (EmailSummary summary : emails) {
            if (arrivalEpochByEmailId.putIfAbsent(summary.getId(), nowEpoch) == null) {
                LOG.info("AutoArchive: recorded arrival of email {}", summary.getId());
            }
        }
    }

    private Map<String, List<EmailSummary>> groupByThread(List<EmailSummary> emails) {
        Map<String, List<EmailSummary>> map = new HashMap<>();
        for (EmailSummary summary : emails) {
            map.computeIfAbsent(summary.getThreadId(), k -> new ArrayList<>()).add(summary);
        }
        return map;
    }

    private boolean isThreadSettled(
        List<EmailSummary> threadEmails,
        long nowSeconds,
        long settlingSeconds
    ) {
        for (EmailSummary summary : threadEmails) {
            long tagEpoch = extractArrivalEpoch(summary);
            if (tagEpoch < 0) {
                return false;
            }
            if (nowSeconds - tagEpoch < settlingSeconds) {
                return false;
            }
        }
        return true;
    }

    private long extractArrivalEpoch(EmailSummary summary) {
        return arrivalEpochByEmailId.getOrDefault(summary.getId(), -1L);
    }

    private void processSettledThread(
        String threadId,
        List<EmailSummary> threadEmailsInFolder,
        String archiveFolderId,
        String archivePromptName,
        MailKickConfig config,
        MailboxResolver resolver
    ) throws IOException {
        List<String> emailIds = new ArrayList<>();
        for (EmailSummary s : threadEmailsInFolder) {
            emailIds.add(s.getId());
        }

        EmailSummary rootSummary = findChainRoot(threadEmailsInFolder);
        String rootEmailId = rootSummary.getId();

        Optional<JsonNode> rootNode = jmapRetry("autoarchive.fetchRoot:" + rootEmailId,
            () -> fetcher.fetchEmailNode(rootEmailId));
        if (rootNode.isEmpty()) {
            LOG.warn("AutoArchive: root email {} not found, skipping thread {}", rootEmailId, threadId);
            return;
        }

        Email rootEmail = EmailNormaliser.normalise(rootNode.get(), threadEmailsInFolder.size());
        String emailXml = EmailNormaliser.toXml(rootEmail);

        if (anthropicAgent.checkInjection(config, rootEmail.getFrom(), rootEmail.getSubject(), rootEmail.getBody())) {
            LOG.warn(
                "AutoArchive: prompt injection detected in thread {} root email {} from={} — routing to Inbox flagged",
                threadId,
                rootEmailId,
                rootEmail.getFrom()
            );
            String inboxId = jmapRetry("autoarchive.resolveInbox", resolver::getInboxId);
            for (EmailSummary summary : threadEmailsInFolder) {
                jmapRetryVoid("autoarchive.flagInjection:" + summary.getId(), () -> {
                    moveToInboxFlagged(summary, inboxId);
                    return null;
                });
            }
            return;
        }

        MoveChainTool moveChainTool = new MoveChainTool(
            emailIds,
            archiveFolderId,
            resolver,
            mover
        );

        List<com.anthropic.models.messages.Tool> archiveTools = new ArrayList<>();
        archiveTools.add(moveChainTool.getToolDeclaration());
        Set<String> extras = config.getExtraToolsForPrompt(archivePromptName);
        Set<String> disallowed = config.getDisallowedToolsForPrompt(archivePromptName);
        archiveTools.addAll(toolRegistry.getTools(extras, disallowed));

        List<ToolCall> toolCalls = anthropicAgent.process(
            config,
            archivePromptName,
            emailXml,
            archiveTools,
            true
        );

        ToolCall moveCall = null;
        for (ToolCall tc : toolCalls) {
            if ("move_chain".equals(tc.getName())) {
                moveCall = tc;
                break;
            }
        }

        if (moveCall == null) {
            LOG.warn("AutoArchive: LLM did not call move_chain for thread {}, leaving in place", threadId);
            recordFailedAttempt(threadId, threadEmailsInFolder, resolver);
            return;
        }

        moveChainTool.execute(moveCall.getInput(), rootEmail);
        for (String emailId : emailIds) {
            arrivalEpochByEmailId.remove(emailId);
        }
        failedAttemptsByThreadId.remove(threadId);
        LOG.info("AutoArchive: filed thread {} ({} email(s))", threadId, emailIds.size());
    }

    /**
     * Increments the per-thread failed-settle-attempt counter, held in memory. Once the counter
     * reaches {@link #MAX_SETTLE_ATTEMPTS}, the thread is given up on and moved to the Inbox
     * unread and flagged so the user can triage it manually, instead of being re-submitted to the
     * LLM every cycle indefinitely.
     */
    private void recordFailedAttempt(
        String threadId,
        List<EmailSummary> threadEmailsInFolder,
        MailboxResolver resolver
    ) throws IOException {
        int attempts = failedAttemptsByThreadId.merge(threadId, 1, Integer::sum);

        if (attempts >= MAX_SETTLE_ATTEMPTS) {
            LOG.warn(
                "AutoArchive: thread {} failed to settle after {} attempt(s), moving to Inbox flagged",
                threadId,
                attempts
            );
            String inboxId = jmapRetry("autoarchive.resolveInbox", resolver::getInboxId);
            for (EmailSummary summary : threadEmailsInFolder) {
                jmapRetryVoid("autoarchive.giveUp:" + summary.getId(), () -> {
                    moveToInboxFlagged(summary, inboxId);
                    return null;
                });
            }
            failedAttemptsByThreadId.remove(threadId);
        }
    }

    /**
     * Moves an email to the inbox, unread and flagged, and clears its in-memory AutoArchive state
     * so it does not carry stale arrival tracking once it leaves the Archive folder.
     */
    private void moveToInboxFlagged(EmailSummary summary, String inboxId) throws IOException {
        mover.moveToInboxUnreadFlagged(summary.getId(), inboxId);
        arrivalEpochByEmailId.remove(summary.getId());
    }

    private <T> T jmapRetry(String name, JmapRetry.JmapOperation<T> op) {
        return JmapRetry.withRetry(
            name,
            op,
            msg -> healthTracker.recordFailure(HealthComponent.FASTMAIL, msg),
            () -> healthTracker.recordSuccess(HealthComponent.FASTMAIL)
        );
    }

    private void jmapRetryVoid(String name, JmapRetry.JmapOperation<Void> op) {
        JmapRetry.withRetry(
            name,
            op,
            msg -> healthTracker.recordFailure(HealthComponent.FASTMAIL, msg),
            () -> healthTracker.recordSuccess(HealthComponent.FASTMAIL)
        );
    }

    private EmailSummary findChainRoot(List<EmailSummary> threadEmails) {
        EmailSummary root = null;
        long minEpoch = Long.MAX_VALUE;
        for (EmailSummary summary : threadEmails) {
            long epoch = extractArrivalEpoch(summary);
            if (epoch >= 0 && epoch < minEpoch) {
                minEpoch = epoch;
                root = summary;
            }
        }
        return root != null ? root : threadEmails.get(0);
    }
}
