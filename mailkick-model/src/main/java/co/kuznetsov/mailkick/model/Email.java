package co.kuznetsov.mailkick.model;

import java.util.List;

/**
 * Immutable value class representing a normalised email message.
 *
 * <p>All fields are {@code String}; nullable fields are documented as such.
 * Authentication results ({@code dkim}, {@code spf}, {@code dmarc}) carry the
 * raw verdict strings as received from the mail server.</p>
 */
public final class Email {

    private final String id;
    private final String messageId;
    private final String date;
    private final String receivedAt;
    private final String from;
    private final String to;
    private final String cc;
    private final String subject;
    private final String replyTo;
    private final String inReplyTo;
    private final String dkim;
    private final String spf;
    private final String dmarc;
    private final String body;
    private final String threadId;
    private final int threadSize;
    private final List<String> documentAttachments;
    private final List<String> mediaAttachments;
    private final List<String> otherAttachments;

    /**
     * All-args constructor.
     *
     * @param id          JMAP email ID, used for JMAP set operations
     * @param messageId   Message-ID header value
     * @param date        Date header value
     * @param receivedAt  JMAP {@code receivedAt} field value
     * @param from        bare sender email address (display name stripped)
     * @param to          To header value
     * @param cc          CC header value, nullable
     * @param subject     Subject header value
     * @param replyTo     Reply-To header value, nullable
     * @param inReplyTo   In-Reply-To header value, nullable
     * @param dkim        DKIM result: pass, fail, or none
     * @param spf         SPF result: pass, fail, softfail, or none
     * @param dmarc       DMARC result: pass, fail, or none
     * @param body                email body rendered as Markdown
     * @param threadId            JMAP thread ID this email belongs to
     * @param threadSize          number of emails in the same JMAP thread (1 means no prior messages)
     * @param documentAttachments MIME types of document attachments (PDF, Office, etc.)
     * @param mediaAttachments    MIME types of image/video/audio attachments
     * @param otherAttachments    MIME types of all other attachments
     */
    public Email(
        String id,
        String messageId,
        String date,
        String receivedAt,
        String from,
        String to,
        String cc,
        String subject,
        String replyTo,
        String inReplyTo,
        String dkim,
        String spf,
        String dmarc,
        String body,
        String threadId,
        int threadSize,
        List<String> documentAttachments,
        List<String> mediaAttachments,
        List<String> otherAttachments
    ) {
        this.id = id;
        this.messageId = messageId;
        this.date = date;
        this.receivedAt = receivedAt;
        this.from = from;
        this.to = to;
        this.cc = cc;
        this.subject = subject;
        this.replyTo = replyTo;
        this.inReplyTo = inReplyTo;
        this.dkim = dkim;
        this.spf = spf;
        this.dmarc = dmarc;
        this.body = body;
        this.threadId = threadId;
        this.threadSize = threadSize;
        this.documentAttachments =
            documentAttachments != null ? documentAttachments : List.of();
        this.mediaAttachments =
            mediaAttachments != null ? mediaAttachments : List.of();
        this.otherAttachments =
            otherAttachments != null ? otherAttachments : List.of();
    }

    /**
     * Returns the JMAP email ID.
     *
     * @return JMAP email ID
     */
    public String getId() {
        return id;
    }

    /**
     * Returns the Message-ID header value.
     *
     * @return Message-ID
     */
    public String getMessageId() {
        return messageId;
    }

    /**
     * Returns the Date header value.
     *
     * @return Date header
     */
    public String getDate() {
        return date;
    }

    /**
     * Returns the JMAP {@code receivedAt} field value.
     *
     * @return receivedAt timestamp
     */
    public String getReceivedAt() {
        return receivedAt;
    }

    /**
     * Returns the bare sender email address with any display name stripped.
     *
     * @return sender address
     */
    public String getFrom() {
        return from;
    }

    /**
     * Returns the To header value.
     *
     * @return To header
     */
    public String getTo() {
        return to;
    }

    /**
     * Returns the CC header value, or {@code null} if absent.
     *
     * @return CC header, or {@code null}
     */
    public String getCc() {
        return cc;
    }

    /**
     * Returns the Subject header value.
     *
     * @return subject
     */
    public String getSubject() {
        return subject;
    }

    /**
     * Returns the Reply-To header value, or {@code null} if absent.
     *
     * @return Reply-To header, or {@code null}
     */
    public String getReplyTo() {
        return replyTo;
    }

    /**
     * Returns the In-Reply-To header value, or {@code null} if absent.
     *
     * @return In-Reply-To header, or {@code null}
     */
    public String getInReplyTo() {
        return inReplyTo;
    }

    /**
     * Returns the DKIM authentication result (pass, fail, or none).
     *
     * @return DKIM result
     */
    public String getDkim() {
        return dkim;
    }

    /**
     * Returns the SPF authentication result (pass, fail, softfail, or none).
     *
     * @return SPF result
     */
    public String getSpf() {
        return spf;
    }

    /**
     * Returns the DMARC authentication result (pass, fail, or none).
     *
     * @return DMARC result
     */
    public String getDmarc() {
        return dmarc;
    }

    /**
     * Returns the email body rendered as Markdown.
     *
     * @return Markdown body
     */
    public String getBody() {
        return body;
    }

    /**
     * Returns the JMAP thread ID this email belongs to.
     *
     * @return JMAP thread ID
     */
    public String getThreadId() {
        return threadId;
    }

    /**
     * Returns the number of emails in the same JMAP thread.
     * A value of 1 means this is a standalone message with no prior messages in the thread.
     *
     * @return thread size
     */
    public int getThreadSize() {
        return threadSize;
    }

    /**
     * Returns the MIME types of document attachments (PDF, Office files, archives, etc.).
     *
     * @return list of MIME types; empty if none
     */
    public List<String> getDocumentAttachments() {
        return documentAttachments;
    }

    /**
     * Returns the MIME types of media attachments (images, video, audio).
     *
     * @return list of MIME types; empty if none
     */
    public List<String> getMediaAttachments() {
        return mediaAttachments;
    }

    /**
     * Returns the MIME types of attachments that are neither documents nor media.
     *
     * @return list of MIME types; empty if none
     */
    public List<String> getOtherAttachments() {
        return otherAttachments;
    }
}
