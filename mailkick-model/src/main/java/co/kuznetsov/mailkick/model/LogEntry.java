package co.kuznetsov.mailkick.model;

/**
 * Immutable value class representing a single audit-log entry written to DynamoDB.
 *
 * <p>Entries are keyed by {@code date} (partition key) and {@code timestamp} (sort key).
 * The {@code action} field records the tool name, rule-type name, or one of the
 * sentinel values {@code ERROR} and {@code OVERSIZE}.</p>
 *
 * <p>The {@code to} and {@code cc} fields capture recipient addresses from the original
 * email and are optional (may be {@code null}).</p>
 */
public final class LogEntry {

    private final String date;
    private final String timestamp;
    private final String messageId;
    private final String from;
    private final String to;
    private final String cc;
    private final String subject;
    private final String action;
    private final String detail;

    /**
     * All-args constructor.
     *
     * @param date      DDB partition key, e.g. {@code "2025-01-15"}
     * @param timestamp DDB sort key, ISO 8601 with millis, e.g. {@code "2025-01-15T08:42:13.001Z"}
     * @param messageId Message-ID of the email being logged
     * @param from      sender address of the email being logged
     * @param to        TO recipients of the email being logged, nullable
     * @param cc        CC recipients of the email being logged, nullable
     * @param subject   subject of the email being logged
     * @param action    tool name, rule-type name, {@code ERROR}, or {@code OVERSIZE}
     * @param detail    optional extra context, nullable
     */
    public LogEntry(
        String date,
        String timestamp,
        String messageId,
        String from,
        String to,
        String cc,
        String subject,
        String action,
        String detail
    ) {
        this.date = date;
        this.timestamp = timestamp;
        this.messageId = messageId;
        this.from = from;
        this.to = to;
        this.cc = cc;
        this.subject = subject;
        this.action = action;
        this.detail = detail;
    }

    /**
     * Creates a log entry for a generic action.
     *
     * @param date      DDB partition key
     * @param timestamp DDB sort key
     * @param messageId Message-ID of the email
     * @param from      sender address
     * @param to        TO recipients, nullable
     * @param cc        CC recipients, nullable
     * @param subject   email subject
     * @param action    tool name or rule-type name
     * @param detail    optional extra context, nullable
     * @return a new {@link LogEntry}
     */
    public static LogEntry forAction(
        String date,
        String timestamp,
        String messageId,
        String from,
        String to,
        String cc,
        String subject,
        String action,
        String detail
    ) {
        return new LogEntry(
            date,
            timestamp,
            messageId,
            from,
            to,
            cc,
            subject,
            action,
            detail
        );
    }

    /**
     * Creates a log entry representing a processing error.
     *
     * @param date      DDB partition key
     * @param timestamp DDB sort key
     * @param messageId Message-ID of the email
     * @param from      sender address
     * @param to        TO recipients, nullable
     * @param cc        CC recipients, nullable
     * @param subject   email subject
     * @param detail    error description, nullable
     * @return a new {@link LogEntry} with action {@code "ERROR"}
     */
    public static LogEntry error(
        String date,
        String timestamp,
        String messageId,
        String from,
        String to,
        String cc,
        String subject,
        String detail
    ) {
        return new LogEntry(
            date,
            timestamp,
            messageId,
            from,
            to,
            cc,
            subject,
            "ERROR",
            detail
        );
    }

    /**
     * Creates a log entry representing an email that was skipped because it exceeded
     * the configured token limit.
     *
     * @param date      DDB partition key
     * @param timestamp DDB sort key
     * @param messageId Message-ID of the email
     * @param from      sender address
     * @param to        TO recipients, nullable
     * @param cc        CC recipients, nullable
     * @param subject   email subject
     * @param detail    optional extra context, nullable
     * @return a new {@link LogEntry} with action {@code "OVERSIZE"}
     */
    public static LogEntry oversize(
        String date,
        String timestamp,
        String messageId,
        String from,
        String to,
        String cc,
        String subject,
        String detail
    ) {
        return new LogEntry(
            date,
            timestamp,
            messageId,
            from,
            to,
            cc,
            subject,
            "OVERSIZE",
            detail
        );
    }

    /**
     * Returns the DDB partition key (calendar date, e.g. {@code "2025-01-15"}).
     *
     * @return date partition key
     */
    public String getDate() {
        return date;
    }

    /**
     * Returns the DDB sort key (ISO 8601 timestamp with millis).
     *
     * @return timestamp sort key
     */
    public String getTimestamp() {
        return timestamp;
    }

    /**
     * Returns the Message-ID of the email being logged.
     *
     * @return Message-ID
     */
    public String getMessageId() {
        return messageId;
    }

    /**
     * Returns the sender address of the email being logged.
     *
     * @return from address
     */
    public String getFrom() {
        return from;
    }

    /**
     * Returns the TO recipients of the email being logged.
     *
     * @return TO recipients, or {@code null}
     */
    public String getTo() {
        return to;
    }

    /**
     * Returns the CC recipients of the email being logged.
     *
     * @return CC recipients, or {@code null}
     */
    public String getCc() {
        return cc;
    }

    /**
     * Returns the subject of the email being logged.
     *
     * @return subject
     */
    public String getSubject() {
        return subject;
    }

    /**
     * Returns the action label: a tool name, rule-type name, {@code ERROR}, or {@code OVERSIZE}.
     *
     * @return action
     */
    public String getAction() {
        return action;
    }

    /**
     * Returns optional extra context, or {@code null}.
     *
     * @return detail, or {@code null}
     */
    public String getDetail() {
        return detail;
    }
}
