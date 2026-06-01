package co.kuznetsov.mailkick.agent;

import co.kuznetsov.mailkick.model.Email;
import co.kuznetsov.mailkick.model.LogEntry;
import co.kuznetsov.mailkick.model.ddb.LogDdbRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes activity log entries to the {@code mailkick.log} DynamoDB table.
 *
 * <p>All logging methods are fire-and-forget: any failure to write a log entry is caught,
 * recorded as a WARN-level message, and then silently discarded. A logging failure must
 * never propagate and interrupt normal email processing.</p>
 */
public class ActivityLogger {

    private static final Logger LOG = LoggerFactory.getLogger(
        ActivityLogger.class
    );

    private final LogDdbRepository logRepository;
    private final ZoneId timezone;

    /**
     * Constructs an {@code ActivityLogger} with the given repository and timezone.
     *
     * @param logRepository the DynamoDB repository used to persist log entries
     * @param timezone      a timezone string (e.g. {@code "America/New_York"}) used to
     *                      compute the local date for partitioning log entries
     */
    public ActivityLogger(LogDdbRepository logRepository, String timezone) {
        this.logRepository = logRepository;
        this.timezone = ZoneId.of(timezone);
    }

    /**
     * Logs a successful action taken on the given email.
     *
     * <p>Builds a {@link LogEntry#forAction} entry and persists it. Swallows all exceptions
     * and logs a WARN if persistence fails.</p>
     *
     * @param email   the email being processed
     * @param action  a short identifier for the action taken (e.g. {@code "UNSUBSCRIBE"})
     * @param detail  additional freeform detail to attach to the log entry
     */
    public void logAction(Email email, String action, String detail) {
        try {
            String date = LocalDate.now(timezone).toString();
            String timestamp = Instant.now().toString();
            LogEntry entry = LogEntry.forAction(
                date,
                timestamp,
                email.getMessageId(),
                email.getFrom(),
                email.getTo(),
                email.getCc(),
                email.getSubject(),
                action,
                detail
            );
            logRepository.save(entry);
            LOG.debug(
                "Activity logged: action={} from={}",
                action,
                email.getFrom()
            );
        } catch (Exception e) {
            LOG.warn(
                "Failed to write activity log for action={}: {}",
                action,
                e.getMessage()
            );
        }
    }

    /**
     * Logs an error encountered while processing the given email.
     *
     * <p>Builds a {@link LogEntry#error} entry and persists it. Swallows all exceptions
     * and logs a WARN if persistence fails.</p>
     *
     * @param email        the email being processed when the error occurred
     * @param errorDetail  a description of the error to record
     */
    public void logError(Email email, String errorDetail) {
        try {
            String date = LocalDate.now(timezone).toString();
            String timestamp = Instant.now().toString();
            LogEntry entry = LogEntry.error(
                date,
                timestamp,
                email.getMessageId(),
                email.getFrom(),
                email.getTo(),
                email.getCc(),
                email.getSubject(),
                errorDetail
            );
            logRepository.save(entry);
            LOG.debug("Error logged for email from={}", email.getFrom());
        } catch (Exception e) {
            LOG.warn("Failed to write error log entry: {}", e.getMessage());
        }
    }

    /**
     * Logs an oversize rejection for the given email.
     *
     * <p>Builds a {@link LogEntry#oversize} entry with a detail string encoding the estimated
     * token count and the configured limit. Swallows all exceptions and logs a WARN if
     * persistence fails.</p>
     *
     * @param email            the email that exceeded the size limit
     * @param estimatedTokens  the estimated token count of the email body
     * @param limit            the maximum token limit that was exceeded
     */
    public void logOversize(Email email, int estimatedTokens, int limit) {
        try {
            String date = LocalDate.now(timezone).toString();
            String timestamp = Instant.now().toString();
            String detail =
                "estimatedTokens=" + estimatedTokens + " limit=" + limit;
            LogEntry entry = LogEntry.oversize(
                date,
                timestamp,
                email.getMessageId(),
                email.getFrom(),
                email.getTo(),
                email.getCc(),
                email.getSubject(),
                detail
            );
            logRepository.save(entry);
            LOG.debug(
                "Oversize logged for email from={} estimatedTokens={}",
                email.getFrom(),
                estimatedTokens
            );
        } catch (Exception e) {
            LOG.warn("Failed to write oversize log entry: {}", e.getMessage());
        }
    }
}
