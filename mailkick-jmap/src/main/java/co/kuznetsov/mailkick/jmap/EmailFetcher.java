package co.kuznetsov.mailkick.jmap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fetches emails from FastMail using the JMAP protocol.
 *
 * <p>Provides methods to query for email state changes, fetch full email details,
 * and obtain the initial state token required to begin change tracking.
 */
public class EmailFetcher {

    private static final Logger LOG = LoggerFactory.getLogger(
        EmailFetcher.class
    );

    private static final int MAX_BODY_VALUE_BYTES = 1048576;

    private final JmapClient client;
    private final JmapSession session;

    /**
     * Constructs an {@code EmailFetcher} with the given JMAP client and session.
     *
     * @param client  the JMAP client used to execute requests
     * @param session the active JMAP session providing the API URL and account ID
     */
    public EmailFetcher(JmapClient client, JmapSession session) {
        this.client = client;
        this.session = session;
    }

    /**
     * Holds the result of an {@code Email/changes} JMAP call, containing the new state token
     * and the list of created or updated email IDs.
     */
    public static final class EmailChangesResult {

        private final String newState;
        private final List<String> createdIds;

        /**
         * Constructs an {@code EmailChangesResult}.
         *
         * @param newState  the new state token to use in the next {@code Email/changes} call
         * @param createdIds the IDs of emails that were created or updated since the previous state
         */
        public EmailChangesResult(String newState, List<String> createdIds) {
            this.newState = newState;
            this.createdIds = createdIds;
        }

        /**
         * Returns the new JMAP state token.
         *
         * @return the new state string
         */
        public String getNewState() {
            return newState;
        }

        /**
         * Returns the list of created and updated email IDs.
         *
         * @return an unmodifiable list of email IDs
         */
        public List<String> getCreatedIds() {
            return createdIds;
        }
    }

    /**
     * Queries the JMAP server for email changes since the given state token.
     *
     * <p>Both created and updated email IDs are returned, as both may represent
     * emails that need to be processed.
     *
     * @param sinceState the state token from the previous call (or from {@link #getInitialState()})
     * @return an {@link EmailChangesResult} containing the new state and changed email IDs
     * @throws IOException if the JMAP request fails or the response cannot be parsed
     */
    public EmailChangesResult getChanges(String sinceState) throws IOException {
        ArrayNode methodCalls = client.newMethodCalls();
        ObjectNode args = client.newArgs();
        args.put("accountId", session.getPrimaryAccountId());
        args.put("sinceState", sinceState);
        client.addMethodCall(methodCalls, "Email/changes", args, "c0");
        ArrayNode responses = client.execute(session.getApiUrl(), methodCalls);

        JsonNode result = responses.get(0).get(1);
        String newState = result.path("newState").asText();

        List<String> ids = new ArrayList<>();
        for (JsonNode idNode : result.path("created")) {
            ids.add(idNode.asText());
        }
        for (JsonNode idNode : result.path("updated")) {
            ids.add(idNode.asText());
        }

        LOG.debug(
            "Email/changes: {} new/updated IDs, newState={}",
            ids.size(),
            newState
        );
        return new EmailChangesResult(newState, ids);
    }

    /**
     * Fetches the full JMAP email node for the given email ID.
     *
     * <p>Returns {@link Optional#empty()} if the email no longer exists on the server
     * (e.g., it was deleted between the time it appeared in a changes response and now).
     *
     * @param emailId the JMAP email ID to fetch
     * @return an {@link Optional} containing the raw {@link JsonNode} for the email,
     *         or empty if the email was not found
     * @throws IOException if the JMAP request fails or the response cannot be parsed
     */
    public Optional<JsonNode> fetchEmailNode(String emailId)
        throws IOException {
        ArrayNode methodCalls = client.newMethodCalls();
        ObjectNode args = client.newArgs();
        args.put("accountId", session.getPrimaryAccountId());
        ArrayNode ids = args.putArray("ids");
        ids.add(emailId);
        ArrayNode props = args.putArray("properties");
        props.add("id");
        props.add("mailboxIds");
        props.add("subject");
        props.add("from");
        props.add("to");
        props.add("cc");
        props.add("replyTo");
        props.add("receivedAt");
        props.add("bodyValues");
        props.add("htmlBody");
        props.add("textBody");
        props.add("messageId");
        props.add("sentAt");
        props.add("inReplyTo");
        props.add("header:Authentication-Results:asText");
        props.add("attachments");
        ArrayNode bodyProps = args.putArray("bodyProperties");
        bodyProps.add("partId");
        bodyProps.add("type");
        args.put("fetchTextBodyValues", true);
        args.put("fetchHTMLBodyValues", true);
        args.put("maxBodyValueBytes", MAX_BODY_VALUE_BYTES);
        client.addMethodCall(methodCalls, "Email/get", args, "e0");

        ArrayNode responses = client.execute(session.getApiUrl(), methodCalls);
        JsonNode result = responses.get(0).get(1);
        JsonNode list = result.path("list");

        if (list.size() == 0) {
            return Optional.empty();
        }

        LOG.debug("Fetched email: id={}", emailId);
        return Optional.of(list.get(0));
    }

    /**
     * Queries the Triage mailbox for all currently present email IDs.
     *
     * <p>Used on startup to enqueue any emails already sitting in Triage before
     * the SSE/poll loop begins, so they are not missed.
     *
     * @param mailboxId the JMAP mailbox ID of the folder to query
     * @return list of email IDs currently in the folder
     * @throws IOException if the JMAP request fails
     */
    public java.util.List<String> queryFolderEmailIds(String mailboxId)
        throws IOException {
        ArrayNode methodCalls = client.newMethodCalls();
        ObjectNode args = client.newArgs();
        args.put("accountId", session.getPrimaryAccountId());
        ObjectNode filter = args.putObject("filter");
        filter.put("inMailbox", mailboxId);
        client.addMethodCall(methodCalls, "Email/query", args, "q0");

        ArrayNode responses = client.execute(session.getApiUrl(), methodCalls);
        JsonNode result = responses.get(0).get(1);
        JsonNode ids = result.path("ids");

        java.util.List<String> emailIds = new java.util.ArrayList<>();
        for (JsonNode id : ids) {
            emailIds.add(id.asText());
        }
        LOG.debug("queryFolderEmailIds mailbox={} found={}", mailboxId, emailIds.size());
        return emailIds;
    }

    /**
     * Queries a mailbox for email IDs where {@code receivedAt} is older than
     * the given number of days from now.
     *
     * @param mailboxId the JMAP mailbox ID to search
     * @param daysOld   emails received more than this many days ago are returned
     * @return list of email IDs matching the criteria
     * @throws IOException if the JMAP request fails
     */
    public java.util.List<String> queryEmailsOlderThan(
        String mailboxId,
        int daysOld
    ) throws IOException {
        String cutoff = java.time.Instant.now()
            .minus(daysOld, java.time.temporal.ChronoUnit.DAYS)
            .toString();

        ArrayNode methodCalls = client.newMethodCalls();
        ObjectNode args = client.newArgs();
        args.put("accountId", session.getPrimaryAccountId());
        ObjectNode filter = args.putObject("filter");
        filter.put("inMailbox", mailboxId);
        filter.put("before", cutoff);
        client.addMethodCall(methodCalls, "Email/query", args, "q0");

        ArrayNode responses = client.execute(session.getApiUrl(), methodCalls);
        JsonNode result = responses.get(0).get(1);
        JsonNode ids = result.path("ids");

        java.util.List<String> emailIds = new java.util.ArrayList<>();
        for (JsonNode id : ids) {
            emailIds.add(id.asText());
        }
        LOG.debug(
            "queryEmailsOlderThan mailbox={} daysOld={} found={}",
            mailboxId,
            daysOld,
            emailIds.size()
        );
        return emailIds;
    }

    /**
     * Queries emails in a mailbox filtered by read state.
     *
     * <p>Uses the JMAP {@code hasKeyword}/{ @code notKeyword} filter on {@code $seen}:
     * an email with {@code $seen} is read; without it, unread.
     *
     * @param mailboxId  the JMAP mailbox ID to search
     * @param unreadOnly if {@code true}, returns only unread emails; if {@code false}, only read emails
     * @return list of matching email IDs
     * @throws IOException if the JMAP request fails
     */
    public java.util.List<String> queryEmailsByReadState(
        String mailboxId,
        boolean unreadOnly
    ) throws IOException {
        ArrayNode methodCalls = client.newMethodCalls();
        ObjectNode args = client.newArgs();
        args.put("accountId", session.getPrimaryAccountId());
        ObjectNode filter = args.putObject("filter");
        filter.put("inMailbox", mailboxId);
        if (unreadOnly) {
            filter.put("notKeyword", "$seen");
        } else {
            filter.put("hasKeyword", "$seen");
        }
        client.addMethodCall(methodCalls, "Email/query", args, "q0");

        ArrayNode responses = client.execute(session.getApiUrl(), methodCalls);
        JsonNode result = responses.get(0).get(1);
        JsonNode ids = result.path("ids");

        java.util.List<String> emailIds = new java.util.ArrayList<>();
        for (JsonNode id : ids) {
            emailIds.add(id.asText());
        }
        LOG.debug(
            "queryEmailsByReadState mailbox={} unreadOnly={} found={}",
            mailboxId,
            unreadOnly,
            emailIds.size()
        );
        return emailIds;
    }

    /**
     * Creates an HTML email directly in the given mailbox using JMAP {@code Email/set}.
     *
     * <p>The email is not sent via SMTP — it is placed directly in the specified folder,
     * useful for placing summary reports and digest emails.
     *
     * @param mailboxId  the JMAP mailbox ID of the destination folder
     * @param from       the From address (e.g. {@code mailkick@example.com})
     * @param to         the To address
     * @param subject    the email subject
     * @param htmlBody   the HTML body content
     * @param markAsRead whether to mark the created email as read ({@code true}) or unread
     * @throws IOException if the JMAP request fails
     */
    public void createEmailInFolder(
        String mailboxId,
        String from,
        String to,
        String subject,
        String htmlBody,
        boolean markAsRead
    ) throws IOException {
        ArrayNode methodCalls = client.newMethodCalls();
        ObjectNode args = client.newArgs();
        args.put("accountId", session.getPrimaryAccountId());

        ObjectNode create = args.putObject("create");
        ObjectNode email = create.putObject("new-1");

        ObjectNode mailboxIds = email.putObject("mailboxIds");
        mailboxIds.put(mailboxId, true);

        ObjectNode keywords = email.putObject("keywords");
        if (markAsRead) {
            keywords.put("$seen", true);
        }

        ArrayNode fromArr = email.putArray("from");
        fromArr.addObject().put("email", from);

        ArrayNode toArr = email.putArray("to");
        toArr.addObject().put("email", to);

        email.put("subject", subject);

        ObjectNode bodyStructure = email.putObject("bodyStructure");
        bodyStructure.put("type", "text/html");
        bodyStructure.put("partId", "1");

        ObjectNode bodyValues = email.putObject("bodyValues");
        ObjectNode bodyValue = bodyValues.putObject("1");
        bodyValue.put("value", htmlBody);
        bodyValue.put("isEncodingProblem", false);
        bodyValue.put("isTruncated", false);

        client.addMethodCall(methodCalls, "Email/set", args, "e0");
        client.execute(session.getApiUrl(), methodCalls);

        LOG.info("Created email in mailbox={} subject={}", mailboxId, subject);
    }

    /**
     * Queries all emails in a mailbox and returns their id, threadId, and keywords.
     *
     * <p>Uses {@code Email/query} to retrieve all IDs in the mailbox, then {@code Email/get}
     * with a minimal property set to fetch metadata for each email.
     *
     * @param mailboxId the JMAP mailbox ID to query
     * @return list of {@link EmailSummary} objects for every email in the mailbox
     * @throws IOException if any JMAP request fails
     */
    public java.util.List<EmailSummary> queryAllEmailsInMailbox(String mailboxId)
        throws IOException {
        ArrayNode queryMethodCalls = client.newMethodCalls();
        ObjectNode queryArgs = client.newArgs();
        queryArgs.put("accountId", session.getPrimaryAccountId());
        ObjectNode queryFilter = queryArgs.putObject("filter");
        queryFilter.put("inMailbox", mailboxId);
        client.addMethodCall(queryMethodCalls, "Email/query", queryArgs, "q0");
        ArrayNode queryResponses = client.execute(session.getApiUrl(), queryMethodCalls);
        JsonNode queryResult = queryResponses.get(0).get(1);
        JsonNode idsNode = queryResult.path("ids");

        java.util.List<String> emailIds = new java.util.ArrayList<>();
        for (JsonNode idNode : idsNode) {
            emailIds.add(idNode.asText());
        }

        if (emailIds.isEmpty()) {
            LOG.debug("queryAllEmailsInMailbox mailbox={} found=0", mailboxId);
            return java.util.Collections.emptyList();
        }

        ArrayNode getMethodCalls = client.newMethodCalls();
        ObjectNode getArgs = client.newArgs();
        getArgs.put("accountId", session.getPrimaryAccountId());
        ArrayNode getIds = getArgs.putArray("ids");
        for (String id : emailIds) {
            getIds.add(id);
        }
        ArrayNode props = getArgs.putArray("properties");
        props.add("id");
        props.add("threadId");
        props.add("keywords");
        client.addMethodCall(getMethodCalls, "Email/get", getArgs, "e0");
        ArrayNode getResponses = client.execute(session.getApiUrl(), getMethodCalls);
        JsonNode getResult = getResponses.get(0).get(1);
        JsonNode emailList = getResult.path("list");

        java.util.List<EmailSummary> summaries = new java.util.ArrayList<>();
        for (JsonNode emailNode : emailList) {
            String id = emailNode.path("id").asText();
            String threadId = emailNode.path("threadId").asText();
            java.util.Map<String, Boolean> keywords = new java.util.LinkedHashMap<>();
            JsonNode kwNode = emailNode.path("keywords");
            if (kwNode.isObject()) {
                kwNode.fields().forEachRemaining(e -> keywords.put(e.getKey(), e.getValue().asBoolean(true)));
            }
            summaries.add(new EmailSummary(id, threadId, keywords));
        }

        LOG.debug("queryAllEmailsInMailbox mailbox={} found={}", mailboxId, summaries.size());
        return summaries;
    }

    /**
     * Fetches all email IDs belonging to the given thread via {@code Thread/get}.
     *
     * @param threadId the JMAP thread ID
     * @return list of email IDs in the thread, in thread order
     * @throws IOException if the JMAP request fails
     */
    public java.util.List<String> fetchThreadEmailIds(String threadId) throws IOException {
        ArrayNode methodCalls = client.newMethodCalls();
        ObjectNode args = client.newArgs();
        args.put("accountId", session.getPrimaryAccountId());
        ArrayNode ids = args.putArray("ids");
        ids.add(threadId);
        client.addMethodCall(methodCalls, "Thread/get", args, "t0");
        ArrayNode responses = client.execute(session.getApiUrl(), methodCalls);
        JsonNode result = responses.get(0).get(1);
        JsonNode list = result.path("list");

        java.util.List<String> emailIds = new java.util.ArrayList<>();
        if (list.isArray() && list.size() > 0) {
            JsonNode threadNode = list.get(0);
            for (JsonNode idNode : threadNode.path("emailIds")) {
                emailIds.add(idNode.asText());
            }
        }
        LOG.debug("fetchThreadEmailIds threadId={} found={}", threadId, emailIds.size());
        return emailIds;
    }

    /**
     * Holds a brief summary of an email: its JMAP ID, thread ID, and keyword map.
     */
    public static final class EmailSummary {

        private final String id;
        private final String threadId;
        private final java.util.Map<String, Boolean> keywords;

        /**
         * Constructs an {@code EmailSummary}.
         *
         * @param id       JMAP email ID
         * @param threadId JMAP thread ID
         * @param keywords map of keyword name to boolean value
         */
        public EmailSummary(
            String id,
            String threadId,
            java.util.Map<String, Boolean> keywords
        ) {
            this.id = id;
            this.threadId = threadId;
            this.keywords = keywords;
        }

        /** Returns the JMAP email ID. */
        public String getId() {
            return id;
        }

        /** Returns the JMAP thread ID. */
        public String getThreadId() {
            return threadId;
        }

        /** Returns the keyword map for this email. */
        public java.util.Map<String, Boolean> getKeywords() {
            return keywords;
        }
    }

    /**
     * Returns the current JMAP email state string for the account.
     *
     * <p>This is used on first startup to obtain an initial state token before any
     * {@code Email/changes} calls can be made. The returned token should be persisted
     * and passed to subsequent {@link #getChanges(String)} calls.
     *
     * @return the current JMAP state string
     * @throws IOException if the JMAP request fails or the response cannot be parsed
     */
    public String getInitialState() throws IOException {
        ArrayNode methodCalls = client.newMethodCalls();
        ObjectNode args = client.newArgs();
        args.put("accountId", session.getPrimaryAccountId());
        args.putArray("ids");
        ArrayNode props = args.putArray("properties");
        props.add("id");
        client.addMethodCall(methodCalls, "Email/get", args, "e0");

        ArrayNode responses = client.execute(session.getApiUrl(), methodCalls);
        String state = responses.get(0).get(1).path("state").asText();

        LOG.debug("Initial email state: {}", state);
        return state;
    }
}
