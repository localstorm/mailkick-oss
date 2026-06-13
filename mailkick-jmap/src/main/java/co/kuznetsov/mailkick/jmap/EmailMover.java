package co.kuznetsov.mailkick.jmap;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Performs {@code Email/set} JMAP operations: move, flag, mark as read, and destroy.
 *
 * <p>Convenience methods are provided for common multi-step operations such as
 * moving an email and simultaneously changing its read or flagged state.
 */
public class EmailMover {

    private static final Logger LOG = LoggerFactory.getLogger(EmailMover.class);

    private final JmapClient client;
    private final JmapSession session;

    /**
     * Constructs an {@code EmailMover} with the given JMAP client and session.
     *
     * @param client  the JMAP client used to execute requests
     * @param session the active JMAP session providing the API URL and account ID
     */
    public EmailMover(JmapClient client, JmapSession session) {
        this.client = client;
        this.session = session;
    }

    /**
     * Moves an email to the specified mailbox by setting its {@code mailboxIds}.
     *
     * <p>The email will be placed exclusively in the target mailbox; any previous
     * mailbox memberships are replaced.
     *
     * @param emailId         the JMAP ID of the email to move
     * @param targetMailboxId the JMAP ID of the destination mailbox
     * @throws IOException if the JMAP request fails
     */
    public void moveToMailbox(String emailId, String targetMailboxId)
        throws IOException {
        ArrayNode methodCalls = client.newMethodCalls();
        ObjectNode args = client.newArgs();
        args.put("accountId", session.getPrimaryAccountId());
        ObjectNode update = args.putObject("update");
        ObjectNode emailUpdate = update.putObject(emailId);
        ObjectNode mailboxIds = emailUpdate.putObject("mailboxIds");
        mailboxIds.put(targetMailboxId, true);
        client.addMethodCall(methodCalls, "Email/set", args, "s0");
        client.execute(session.getApiUrl(), methodCalls);
        LOG.debug("Moved email {} to mailbox {}", emailId, targetMailboxId);
    }

    /**
     * Sets the {@code $seen} keyword on an email, marking it as read or unread.
     *
     * @param emailId the JMAP ID of the email
     * @param read    {@code true} to mark as read, {@code false} to mark as unread
     * @throws IOException if the JMAP request fails
     */
    public void setRead(String emailId, boolean read) throws IOException {
        ArrayNode methodCalls = client.newMethodCalls();
        ObjectNode args = client.newArgs();
        args.put("accountId", session.getPrimaryAccountId());
        ObjectNode update = args.putObject("update");
        ObjectNode emailUpdate = update.putObject(emailId);
        emailUpdate.put("keywords/$seen", read);
        client.addMethodCall(methodCalls, "Email/set", args, "s0");
        client.execute(session.getApiUrl(), methodCalls);
        LOG.debug("Set email {} read={}", emailId, read);
    }

    /**
     * Sets the {@code $flagged} keyword on an email.
     *
     * @param emailId the JMAP ID of the email
     * @param flagged {@code true} to flag the email, {@code false} to unflag it
     * @throws IOException if the JMAP request fails
     */
    public void setFlagged(String emailId, boolean flagged) throws IOException {
        ArrayNode methodCalls = client.newMethodCalls();
        ObjectNode args = client.newArgs();
        args.put("accountId", session.getPrimaryAccountId());
        ObjectNode update = args.putObject("update");
        ObjectNode emailUpdate = update.putObject(emailId);
        emailUpdate.put("keywords/$flagged", flagged);
        client.addMethodCall(methodCalls, "Email/set", args, "s0");
        client.execute(session.getApiUrl(), methodCalls);
        LOG.debug("Set email {} flagged={}", emailId, flagged);
    }

    /**
     * Sets or removes a single keyword on an email via {@code Email/set} patch.
     *
     * @param emailId  the JMAP email ID
     * @param keyword  the keyword to set or clear
     * @param value    {@code true} to add the keyword, {@code false} to remove it
     * @throws IOException if the JMAP request fails
     */
    public void setKeyword(String emailId, String keyword, boolean value)
        throws IOException {
        ArrayNode methodCalls = client.newMethodCalls();
        ObjectNode args = client.newArgs();
        args.put("accountId", session.getPrimaryAccountId());
        ObjectNode update = args.putObject("update");
        ObjectNode emailUpdate = update.putObject(emailId);
        emailUpdate.put("keywords/" + keyword, value);
        client.addMethodCall(methodCalls, "Email/set", args, "s0");
        client.execute(session.getApiUrl(), methodCalls);
        LOG.debug("Set keyword '{}' on email {} to {}", keyword, emailId, value);
    }

    /**
     * Moves all given emails to a target mailbox in a single batched {@code Email/set} call.
     * Read/unread state is not changed.
     *
     * @param emailIds        the JMAP IDs of the emails to move
     * @param targetMailboxId the destination JMAP mailbox ID
     * @throws IOException if the JMAP request fails
     */
    public void moveAllToMailbox(java.util.List<String> emailIds, String targetMailboxId)
        throws IOException {
        if (emailIds.isEmpty()) {
            return;
        }
        ArrayNode methodCalls = client.newMethodCalls();
        ObjectNode args = client.newArgs();
        args.put("accountId", session.getPrimaryAccountId());
        ObjectNode update = args.putObject("update");
        for (String emailId : emailIds) {
            ObjectNode emailUpdate = update.putObject(emailId);
            ObjectNode mailboxIds = emailUpdate.putObject("mailboxIds");
            mailboxIds.put(targetMailboxId, true);
        }
        client.addMethodCall(methodCalls, "Email/set", args, "s0");
        client.execute(session.getApiUrl(), methodCalls);
        LOG.info("Moved {} email(s) to mailbox {}", emailIds.size(), targetMailboxId);
    }

    /**
     * Moves all given emails to a target mailbox and removes each email's own arrival keyword,
     * in a single batched {@code Email/set} call.
     *
     * <p>Each email may carry a different {@code mailkick-archived-<ts>} keyword (tagged at
     * different epoch seconds), so the keyword to remove is supplied per email.
     *
     * @param emailIdToKeyword map of JMAP email ID to the keyword to remove from that email
     * @param targetMailboxId  the destination JMAP mailbox ID
     * @throws IOException if the JMAP request fails
     */
    public void moveAllToMailboxAndRemoveKeyword(
        java.util.Map<String, String> emailIdToKeyword,
        String targetMailboxId
    ) throws IOException {
        if (emailIdToKeyword.isEmpty()) {
            return;
        }
        ArrayNode methodCalls = client.newMethodCalls();
        ObjectNode args = client.newArgs();
        args.put("accountId", session.getPrimaryAccountId());
        ObjectNode update = args.putObject("update");
        for (java.util.Map.Entry<String, String> entry : emailIdToKeyword.entrySet()) {
            ObjectNode emailUpdate = update.putObject(entry.getKey());
            ObjectNode mailboxIds = emailUpdate.putObject("mailboxIds");
            mailboxIds.put(targetMailboxId, true);
            emailUpdate.putNull("keywords/" + entry.getValue());
        }
        client.addMethodCall(methodCalls, "Email/set", args, "s0");
        client.execute(session.getApiUrl(), methodCalls);
        LOG.info(
            "Moved {} email(s) to mailbox {}",
            emailIdToKeyword.size(),
            targetMailboxId
        );
    }

    /**
     * Permanently deletes an email from the server.
     *
     * @param emailId the JMAP ID of the email to destroy
     * @throws IOException if the JMAP request fails
     */
    public void destroy(String emailId) throws IOException {
        ArrayNode methodCalls = client.newMethodCalls();
        ObjectNode args = client.newArgs();
        args.put("accountId", session.getPrimaryAccountId());
        ArrayNode destroyIds = args.putArray("destroy");
        destroyIds.add(emailId);
        client.addMethodCall(methodCalls, "Email/set", args, "s0");
        client.execute(session.getApiUrl(), methodCalls);
        LOG.info("Destroyed email {}", emailId);
    }

    /**
     * Moves an email to the specified mailbox and marks it as read.
     *
     * <p>Equivalent to calling {@link #moveToMailbox} followed by {@link #setRead} with
     * {@code true}.
     *
     * @param emailId         the JMAP ID of the email
     * @param targetMailboxId the JMAP ID of the destination mailbox
     * @throws IOException if any JMAP request fails
     */
    public void moveToMailboxAndSetRead(String emailId, String targetMailboxId)
        throws IOException {
        moveToMailbox(emailId, targetMailboxId);
        setRead(emailId, true);
    }

    /**
     * Moves an email to the inbox and marks it as unread.
     *
     * <p>Equivalent to calling {@link #moveToMailbox} followed by {@link #setRead} with
     * {@code false}.
     *
     * @param emailId        the JMAP ID of the email
     * @param inboxMailboxId the JMAP ID of the inbox mailbox
     * @throws IOException if any JMAP request fails
     */
    public void moveToInboxUnread(String emailId, String inboxMailboxId)
        throws IOException {
        moveToMailbox(emailId, inboxMailboxId);
        setRead(emailId, false);
    }

    /**
     * Moves an email to the inbox, marks it as unread, and flags it.
     *
     * <p>This is the error-fallback path: when an email cannot be classified, it is surfaced
     * in the inbox as unread and flagged so the user can triage it manually.
     *
     * @param emailId        the JMAP ID of the email
     * @param inboxMailboxId the JMAP ID of the inbox mailbox
     * @throws IOException if any JMAP request fails
     */
    public void moveToInboxUnreadFlagged(String emailId, String inboxMailboxId)
        throws IOException {
        moveToMailbox(emailId, inboxMailboxId);
        setRead(emailId, false);
        setFlagged(emailId, true);
    }
}
