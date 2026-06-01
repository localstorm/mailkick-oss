package co.kuznetsov.mailkick.server;

import co.kuznetsov.mailkick.agent.AgentPromptLoader;
import co.kuznetsov.mailkick.jmap.EmailFetcher;
import co.kuznetsov.mailkick.jmap.EmailMover;
import co.kuznetsov.mailkick.jmap.JmapRetry;
import co.kuznetsov.mailkick.jmap.MailboxResolver;
import co.kuznetsov.mailkick.model.AutoSpamConfig;
import co.kuznetsov.mailkick.model.HealthComponent;
import co.kuznetsov.mailkick.model.MailKickConfig;
import co.kuznetsov.mailkick.model.Rule;
import co.kuznetsov.mailkick.model.ddb.RulesDdbRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

/**
 * Scheduled task that runs daily at midnight and auto-spams senders whose emails
 * have been sitting in the purgatory folder longer than the configured threshold.
 *
 * <p>For each qualifying email: if the sender domain is not in the excluded-domains list,
 * a SPAM rule is created in DynamoDB for that exact sender address and the email is moved
 * to Spam. A Thymeleaf-formatted summary report is then placed in the summary folder.
 */
public final class AutoSpamRunner {

    private static final Logger LOG = LoggerFactory.getLogger(
        AutoSpamRunner.class
    );

    private static final int RUN_INTERVAL_MS = 300_000; // 5 minutes

    private final AgentPromptLoader promptLoader;
    private final MailboxResolver resolver;
    private final EmailFetcher fetcher;
    private final EmailMover mover;
    private final RulesDdbRepository rulesRepository;
    private final TemplateEngine templateEngine;
    private final HealthTracker healthTracker;
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * Constructs an {@code AutoSpamRunner} with all required dependencies.
     */
    public AutoSpamRunner(
        AgentPromptLoader promptLoader,
        MailboxResolver resolver,
        EmailFetcher fetcher,
        EmailMover mover,
        RulesDdbRepository rulesRepository,
        TemplateEngine templateEngine,
        HealthTracker healthTracker
    ) {
        this.promptLoader = promptLoader;
        this.resolver = resolver;
        this.fetcher = fetcher;
        this.mover = mover;
        this.rulesRepository = rulesRepository;
        this.templateEngine = templateEngine;
        this.healthTracker = healthTracker;
    }

    /** Starts the background midnight-scheduling thread. */
    public void start() {
        running.set(true);
        Thread thread = new Thread(this::run, "mailkick-autospam");
        thread.setDaemon(false);
        thread.start();
        LOG.info("AutoSpamRunner started");
    }

    /** Signals the scheduling thread to stop. */
    public void stop() {
        running.set(false);
        LOG.info("AutoSpamRunner stopped");
    }

    private void run() {
        // Run once immediately at startup, then every RUN_INTERVAL_MS
        try {
            executeAutoSpam();
        } catch (Exception e) {
            LOG.error("AutoSpam bootstrap run failed: {}", e.getMessage(), e);
        }

        while (running.get()) {
            try {
                Thread.sleep(RUN_INTERVAL_MS);
                if (!running.get()) {
                    break;
                }
                executeAutoSpam();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                LOG.error("AutoSpam run failed: {}", e.getMessage(), e);
            }
        }
    }

    private void executeAutoSpam() throws IOException {
        MailKickConfig config = promptLoader.getConfig();
        AutoSpamConfig autoSpam = config.getAutoSpam();
        if (autoSpam == null) {
            LOG.info("AutoSpam not configured, skipping");
            return;
        }

        LOG.info(
            "AutoSpam starting — folder={} days={}",
            autoSpam.getPurgatoryFolder(),
            autoSpam.getPurgatoryDays()
        );

        String spamFolder = config.getResolvedSpamFolder();
        String spamId = jmapRetry("autospam.resolveSpam", () -> (spamFolder != null)
            ? resolver.getMailboxId(spamFolder)
            : resolver.getSpamId());
        String spamFolderKey = (spamFolder != null) ? spamFolder : "spam";
        Set<String> excludedDomains = autoSpam.getExcludedDomainsSet();

        // --- Purgatory processing ---
        String purgatoryId = jmapRetry("autospam.resolvePurgatory",
            () -> resolver.getMailboxId(autoSpam.getPurgatoryFolder()));
        List<String> emailIds = jmapRetry("autospam.queryEmails",
            () -> fetcher.queryEmailsOlderThan(purgatoryId, autoSpam.getPurgatoryDays()));
        LOG.info("AutoSpam found {} qualifying email(s)", emailIds.size());

        if (!emailIds.isEmpty()) {
            java.util.Set<String> processedSenders = new java.util.HashSet<>();
            List<String> newSpamSenders = new ArrayList<>();

            for (String emailId : emailIds) {
                try {
                    Optional<JsonNode> emailNode = jmapRetry(
                        "autospam.fetchEmail:" + emailId,
                        () -> fetcher.fetchEmailNode(emailId));
                    if (emailNode.isEmpty()) {
                        continue;
                    }

                    JsonNode fromArr = emailNode.get().path("from");
                    String senderEmail = (fromArr.isArray() &&
                        fromArr.size() > 0)
                        ? fromArr.get(0).path("email").asText("").toLowerCase()
                        : "";

                    if (!senderEmail.isBlank()) {
                        String domain = extractDomain(senderEmail);
                        if (
                            domain != null &&
                            !excludedDomains.contains(domain) &&
                            processedSenders.add(senderEmail)
                        ) {
                            boolean alreadyInDdb = rulesRepository
                                .findBySender(senderEmail)
                                .isPresent();
                            if (!alreadyInDdb) {
                                rulesRepository.save(Rule.spam(senderEmail));
                                newSpamSenders.add(senderEmail);
                                LOG.info(
                                    "AutoSpam: SPAM rule created for {}",
                                    senderEmail
                                );
                            } else {
                                LOG.debug(
                                    "AutoSpam: rule already exists for {}, skipping",
                                    senderEmail
                                );
                            }
                        }
                    }

                    boolean unread = config.shouldMarkUnread(spamFolderKey);
                    jmapRetryVoid("autospam.setRead:" + emailId, () -> {
                        mover.setRead(emailId, !unread);
                        return null;
                    });
                    jmapRetryVoid("autospam.move:" + emailId, () -> {
                        mover.moveToMailbox(emailId, spamId);
                        return null;
                    });
                } catch (RuntimeException e) {
                    LOG.error(
                        "AutoSpam failed to process email {} (interrupted): {}",
                        emailId,
                        e.getMessage()
                    );
                    throw e;
                }
            }

            if (!newSpamSenders.isEmpty()) {
                placeSummaryReport(autoSpam, newSpamSenders, config);
            } else {
                LOG.info("AutoSpam: no new spam rules created");
            }
        }

        // --- Always: fix read/unread inconsistencies in spam folder ---
        fixReadConsistency(spamId, spamFolderKey, config);
    }

    private void placeSummaryReport(
        AutoSpamConfig autoSpam,
        List<String> spamSenders,
        MailKickConfig config
    ) {
        try {
            String summaryFolderId = jmapRetry("autospam.resolveSummaryFolder",
                () -> resolver.getMailboxId(autoSpam.getSummaryFolder()));

            ZoneId zone = config.getZoneId();
            String dateStr = LocalDate.now(zone).toString();

            Context ctx = new Context();
            ctx.setVariable("senders", spamSenders);
            ctx.setVariable("purgatoryFolder", autoSpam.getPurgatoryFolder());
            ctx.setVariable("purgatoryDays", autoSpam.getPurgatoryDays());
            ctx.setVariable("date", dateStr);
            String html = templateEngine.process("autospam-report", ctx);

            boolean markAsRead = !config.shouldMarkUnread(
                autoSpam.getSummaryFolder()
            );
            String subject =
                "AutoSpam Report \u2014 " +
                spamSenders.size() +
                " sender(s) blocked";

            jmapRetryVoid("autospam.createReport", () -> {
                fetcher.createEmailInFolder(
                    summaryFolderId,
                    autoSpam.getReportSender(),
                    autoSpam.getReportSender(),
                    subject,
                    html,
                    markAsRead
                );
                return null;
            });

            LOG.info(
                "AutoSpam: summary report placed in {} ({} senders)",
                autoSpam.getSummaryFolder(),
                spamSenders.size()
            );
        } catch (RuntimeException e) {
            LOG.error(
                "AutoSpam: failed to place summary report (interrupted): {}",
                e.getMessage(),
                e
            );
            throw e;
        }
    }

    /**
     * Scans the spam folder and corrects any emails whose read/unread state is
     * inconsistent with the configured {@code markUnread} policy.
     *
     * <p>For example, if Spam should be read and the mail provider delivered some
     * emails as unread, this method marks them as read.
     */
    private void fixReadConsistency(
        String spamId,
        String spamFolderKey,
        MailKickConfig config
    ) {
        try {
            boolean shouldBeUnread = config.shouldMarkUnread(spamFolderKey);
            List<String> inconsistentIds = jmapRetry("autospam.queryReadState",
                () -> fetcher.queryEmailsByReadState(spamId, !shouldBeUnread));
            if (inconsistentIds.isEmpty()) {
                LOG.debug("Spam folder read consistency OK");
                return;
            }
            LOG.info(
                "Fixing {} email(s) in spam with wrong read state (correcting to {})",
                inconsistentIds.size(),
                shouldBeUnread ? "unread" : "read"
            );
            for (String id : inconsistentIds) {
                jmapRetryVoid("autospam.fixRead:" + id, () -> {
                    mover.setRead(id, !shouldBeUnread);
                    return null;
                });
            }
        } catch (RuntimeException e) {
            LOG.error(
                "Failed to fix spam read consistency (interrupted): {}",
                e.getMessage()
            );
        }
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

    private static String extractDomain(String email) {
        if (email == null) {
            return null;
        }
        int atIdx = email.indexOf('@');
        if (atIdx > 0 && atIdx < email.length() - 1) {
            return email.substring(atIdx + 1).toLowerCase();
        }
        return null;
    }
}
