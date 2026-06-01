package co.kuznetsov.mailkick.server;

import co.kuznetsov.mailkick.agent.AgentPromptLoader;
import co.kuznetsov.mailkick.agent.AnthropicAgent;
import co.kuznetsov.mailkick.jmap.EmailFetcher;
import co.kuznetsov.mailkick.jmap.JmapRetry;
import co.kuznetsov.mailkick.jmap.MailboxResolver;
import co.kuznetsov.mailkick.model.HealthComponent;
import co.kuznetsov.mailkick.model.LogEntry;
import co.kuznetsov.mailkick.model.MailKickConfig;
import co.kuznetsov.mailkick.model.ddb.LogDdbRepository;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Scheduled runner that generates a daily digest email from accumulated activity log entries.
 *
 * <p>Every minute, this runner checks whether the configured {@code digestTime} has passed
 * today without a digest already being sent. If so, it:
 * <ol>
 *   <li>Reads all entries from {@code mailkick.log} via DynamoDB (all dates, not just today,
 *       so missed days accumulate until the next successful run).</li>
 *   <li>Serialises the entries to XML and calls Anthropic with the digest prompt.</li>
 *   <li>Creates the digest email as unread in Inbox via JMAP {@code Email/set}.</li>
 *   <li>Deletes all fetched log entries only after the email is successfully created.</li>
 * </ol>
 *
 * <p>If {@code digestTime} or {@code digestPromptName} are absent from the config, the runner
 * is effectively dormant.  If the log is empty, no email is created.
 */
public final class DigestRunner {

    private static final Logger LOG = LoggerFactory.getLogger(
        DigestRunner.class
    );

    /** How often (in milliseconds) the runner wakes to check whether a digest is due. */
    private static final long CHECK_INTERVAL_MS = 60_000L;

    /**
     * Maximum estimated input tokens for the digest LLM call. Entries are included
     * newest-first; oldest overflow when truncated.
     */
    private static final int MAX_DIGEST_INPUT_TOKENS = 100_000;

    /** Rough character-to-token ratio used for input size estimation. */
    private static final int CHARS_PER_TOKEN = 4;

    private final AgentPromptLoader promptLoader;
    private final LogDdbRepository logRepository;
    private final AnthropicAgent anthropicAgent;
    private final EmailFetcher emailFetcher;
    private final MailboxResolver mailboxResolver;
    private final HealthTracker healthTracker;
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** Date of the most-recent successful digest run in the configured timezone. */
    private volatile LocalDate lastRunDate = null;

    /**
     * Constructs a {@code DigestRunner}.
     *
     * @param promptLoader    provides the latest {@link MailKickConfig} (reloaded every 5 min)
     * @param logRepository   DynamoDB repository for reading and deleting log entries
     * @param anthropicAgent  Anthropic client for text-generation calls
     * @param emailFetcher    JMAP helper for creating emails in a mailbox
     * @param mailboxResolver used to resolve the Inbox mailbox ID
     * @param healthTracker   health tracker for recording JMAP failures and recoveries
     */
    public DigestRunner(
        AgentPromptLoader promptLoader,
        LogDdbRepository logRepository,
        AnthropicAgent anthropicAgent,
        EmailFetcher emailFetcher,
        MailboxResolver mailboxResolver,
        HealthTracker healthTracker
    ) {
        this.promptLoader = promptLoader;
        this.logRepository = logRepository;
        this.anthropicAgent = anthropicAgent;
        this.emailFetcher = emailFetcher;
        this.mailboxResolver = mailboxResolver;
        this.healthTracker = healthTracker;
    }

    /** Starts the background digest-scheduler thread. */
    public void start() {
        running.set(true);
        Thread thread = new Thread(this::run, "mailkick-digest");
        thread.setDaemon(false);
        thread.start();
        LOG.info("DigestRunner started");
    }

    /** Signals the background thread to stop at the next wake-up. */
    public void stop() {
        running.set(false);
        LOG.info("DigestRunner stopped");
    }

    // ------------------------------------------------------------------------------------------
    // Internal loop
    // ------------------------------------------------------------------------------------------

    private void run() {
        while (running.get()) {
            try {
                Thread.sleep(CHECK_INTERVAL_MS);
                if (!running.get()) {
                    break;
                }
                checkAndRunDigest();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                LOG.error("Digest check/run failed: {}", e.getMessage(), e);
            }
        }
    }

    private void checkAndRunDigest() throws IOException {
        MailKickConfig config = promptLoader.getConfig();

        String digestTimeStr = config.getDigestTime();
        if (digestTimeStr == null || digestTimeStr.isBlank()) {
            LOG.debug("Digest time not configured — skipping");
            return;
        }

        String digestPromptName = config.getDigestPromptName();
        if (digestPromptName == null || digestPromptName.isBlank()) {
            LOG.debug("Digest prompt name not configured — skipping");
            return;
        }

        ZoneId zone = config.getZoneId();
        LocalTime digestTime = LocalTime.parse(digestTimeStr);
        ZonedDateTime now = ZonedDateTime.now(zone);
        LocalDate today = now.toLocalDate();

        boolean timeHasPassed = now.toLocalTime().isAfter(digestTime);
        boolean notYetRunToday = !today.equals(lastRunDate);

        if (timeHasPassed && notYetRunToday) {
            lastRunDate = today;
            runDigest(config);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Digest execution
    // ------------------------------------------------------------------------------------------

    private void runDigest(MailKickConfig config) throws IOException {
        LOG.info("Running daily digest...");

        List<LogEntry> entries = logRepository.findAll();
        if (entries.isEmpty()) {
            LOG.info("Digest: no log entries found — skipping email creation");
            return;
        }

        LOG.info("Digest: processing {} log entries", entries.size());

        EntriesXml built = buildEntriesXml(entries);
        if (built.omitted > 0) {
            LOG.warn(
                "Digest: {} of {} log entries omitted — input size limit ({} tokens)",
                built.omitted,
                entries.size(),
                MAX_DIGEST_INPUT_TOKENS
            );
        }

        String digestText = anthropicAgent.generateText(
            config,
            config.getDigestPromptName(),
            built.xml
        );

        if (digestText == null || digestText.isBlank()) {
            LOG.warn(
                "Digest: LLM returned empty response — aborting (log entries kept)"
            );
            return;
        }

        String inboxId = jmapRetry("digest.resolveInbox", mailboxResolver::getInboxId);

        ZoneId zone = config.getZoneId();
        String dateStr = LocalDate.now(zone).toString();
        String subject = "MailKick Daily Digest \u2014 " + dateStr;

        String from = config.getDigestSenderAddress();
        String htmlBody = textToHtml(
            digestText,
            dateStr,
            entries.size(),
            built.omitted
        );

        jmapRetryVoid("digest.createEmail", () -> {
            emailFetcher.createEmailInFolder(inboxId, from, from, subject, htmlBody, false);
            return null;
        });
        LOG.info("Digest: email created in Inbox — subject=\"{}\"", subject);

        logRepository.deleteAll(entries);
        LOG.info("Digest: deleted {} log entries", entries.size());
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

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

    /** Carrier for the built XML and the count of entries that were omitted. */
    private record EntriesXml(String xml, int omitted) {}

    /**
     * Serialises log entries to XML for the digest LLM call.
     *
     * <p>Entries are processed newest-first so that the most recent activity is always
     * included. If the estimated token count would exceed {@link #MAX_DIGEST_INPUT_TOKENS},
     * older entries are omitted and a note is appended to the XML.
     */
    private static EntriesXml buildEntriesXml(List<LogEntry> entries) {
        // entries is sorted oldest-first; iterate in reverse to prefer recent ones.
        int charBudget = MAX_DIGEST_INPUT_TOKENS * CHARS_PER_TOKEN;
        List<String> selected = new ArrayList<>();
        int used = "<activityLog>\n</activityLog>".length();

        for (int i = entries.size() - 1; i >= 0; i--) {
            String chunk = buildEntryXml(entries.get(i));
            if (used + chunk.length() > charBudget) {
                break;
            }
            selected.add(chunk);
            used += chunk.length();
        }

        // Restore chronological order.
        Collections.reverse(selected);

        int omitted = entries.size() - selected.size();
        StringBuilder sb = new StringBuilder();
        sb.append("<activityLog>\n");
        for (String chunk : selected) {
            sb.append(chunk);
        }
        if (omitted > 0) {
            sb.append("  <!-- ")
                .append(omitted)
                .append(
                    " older action(s) omitted: input size limit reached -->\n"
                );
        }
        sb.append("</activityLog>");
        return new EntriesXml(sb.toString(), omitted);
    }

    private static String buildEntryXml(LogEntry entry) {
        StringBuilder sb = new StringBuilder();
        sb.append("  <entry>\n");
        appendXmlElement(sb, "date", entry.getDate());
        appendXmlElement(sb, "timestamp", entry.getTimestamp());
        appendXmlElement(sb, "messageId", entry.getMessageId());
        appendXmlElement(sb, "from", entry.getFrom());
        if (entry.getTo() != null && !entry.getTo().isBlank()) {
            appendXmlElement(sb, "to", entry.getTo());
        }
        if (entry.getCc() != null && !entry.getCc().isBlank()) {
            appendXmlElement(sb, "cc", entry.getCc());
        }
        appendXmlElement(sb, "subject", entry.getSubject());
        appendXmlElement(sb, "action", entry.getAction());
        if (entry.getDetail() != null && !entry.getDetail().isBlank()) {
            appendXmlElement(sb, "detail", entry.getDetail());
        }
        sb.append("  </entry>\n");
        return sb.toString();
    }

    private static void appendXmlElement(
        StringBuilder sb,
        String tag,
        String value
    ) {
        if (value != null) {
            sb.append("    <")
                .append(tag)
                .append('>')
                .append(xmlEscape(value))
                .append("</")
                .append(tag)
                .append(">\n");
        }
    }

    private static String xmlEscape(String value) {
        if (value == null) {
            return "";
        }
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;");
    }

    /**
     * Renders the digest text as a styled HTML email matching the AutoSpam report card layout.
     * When entries were omitted due to the input size limit, an amber warning banner is appended.
     */
    private static String textToHtml(
        String text,
        String dateStr,
        int entryCount,
        int omitted
    ) {
        String escapedText = xmlEscape(text);
        StringBuilder html = new StringBuilder();
        html.append("<!doctype html>\n")
            .append("<html lang=\"en\"><head><meta charset=\"UTF-8\"/>\n")
            .append("<style>\n")
            .append("body{font-family:Arial,Helvetica,sans-serif;color:#333;")
            .append(
                "max-width:600px;margin:0 auto;padding:24px;background:#f5f5f5}\n"
            )
            .append(".card{background:#fff;border-radius:6px;padding:24px;")
            .append("box-shadow:0 1px 4px rgba(0,0,0,0.12)}\n")
            .append("h1{color:#1a5276;margin:0 0 16px;font-size:22px}\n")
            .append(
                ".summary{background:#eaf4fb;border-left:4px solid #2e86c1;"
            )
            .append("padding:12px 16px;border-radius:0 4px 4px 0;")
            .append("margin-bottom:20px;font-size:14px}\n")
            .append(
                ".body{font-family:Arial,Helvetica,sans-serif;font-size:14px;"
            )
            .append("white-space:pre-wrap;line-height:1.6;margin:0}\n")
            .append(
                ".omission{background:#fef9e7;border-left:4px solid #f39c12;"
            )
            .append("padding:10px 16px;border-radius:0 4px 4px 0;")
            .append("margin-top:20px;font-size:13px}\n")
            .append("</style></head>\n")
            .append("<body><div class=\"card\">\n")
            .append("<h1>&#128203; MailKick Daily Digest</h1>\n")
            .append("<div class=\"summary\"><strong>")
            .append(xmlEscape(dateStr))
            .append("</strong> &mdash; ")
            .append(entryCount)
            .append(" log ")
            .append(entryCount == 1 ? "entry" : "entries")
            .append("</div>\n")
            .append("<pre class=\"body\">")
            .append(escapedText)
            .append("</pre>\n");
        if (omitted > 0) {
            html.append("<div class=\"omission\">&#9888; ")
                .append(omitted)
                .append(" older action")
                .append(omitted == 1 ? "" : "s")
                .append(" omitted — input size limit reached.</div>\n");
        }
        html.append("</div></body></html>");
        return html.toString();
    }
}
