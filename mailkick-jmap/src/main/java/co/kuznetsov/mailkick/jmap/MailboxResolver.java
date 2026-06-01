package co.kuznetsov.mailkick.jmap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves FastMail folder names and roles to JMAP mailbox IDs.
 *
 * <p>Resolution is performed once at construction time via a single {@code Mailbox/get} JMAP call.
 * The resolved mappings are stored in immutable maps and reused for all subsequent lookups.
 *
 * <p>Full slash-delimited paths (e.g. {@code Inbox/Papertrail/Shopping}) are the
 * canonical form. Bare leaf names (e.g. {@code Shopping}) are also supported as a
 * fallback for unambiguous cases.
 */
public final class MailboxResolver {

    private static final Logger LOG = LoggerFactory.getLogger(
        MailboxResolver.class
    );

    private static final String ROLE_INBOX = "inbox";
    private static final String ROLE_TRASH = "trash";
    private static final String ROLE_JUNK = "junk";
    private static final String ROLE_ARCHIVE = "archive";

    /** Maps lowercase full path (e.g. {@code papertrail/shopping}) to mailbox ID. */
    private final Map<String, String> pathToId;
    /** Maps lowercase leaf name (e.g. {@code shopping}) to mailbox ID — fallback only. */
    private final Map<String, String> nameToId;
    private final Map<String, String> roleToId;

    /**
     * Constructs a {@code MailboxResolver} by fetching all mailboxes from the JMAP server.
     *
     * @param client  the JMAP client used to execute requests
     * @param session the active JMAP session providing the API URL and account ID
     * @throws IOException if the JMAP request fails or the response cannot be parsed
     */
    public MailboxResolver(JmapClient client, JmapSession session)
        throws IOException {
        ArrayNode methodCalls = client.newMethodCalls();
        ObjectNode args = client.newArgs();
        args.put("accountId", session.getPrimaryAccountId());
        args.putNull("ids");
        // Explicitly request parentId — some servers omit it unless asked
        ArrayNode props = args.putArray("properties");
        props.add("id");
        props.add("name");
        props.add("parentId");
        props.add("role");
        client.addMethodCall(methodCalls, "Mailbox/get", args, "m0");
        ArrayNode responses = client.execute(session.getApiUrl(), methodCalls);

        JsonNode mailboxGetResponse = responses.get(0).get(1);
        JsonNode list = mailboxGetResponse.path("list");

        // First pass: collect id → name and id → parentId
        Map<String, String> idToName = new HashMap<>();
        Map<String, String> idToParentId = new HashMap<>();
        Map<String, String> roleMap = new HashMap<>();

        for (JsonNode mailbox : list) {
            String id = mailbox.path("id").asText();
            String name = mailbox.path("name").asText();
            idToName.put(id, name);

            JsonNode parentNode = mailbox.path("parentId");
            if (!parentNode.isNull() && !parentNode.isMissingNode()) {
                idToParentId.put(id, parentNode.asText());
            }

            JsonNode roleNode = mailbox.path("role");
            if (!roleNode.isNull() && !roleNode.isMissingNode()) {
                String role = roleNode.asText();
                if (!role.isEmpty()) {
                    roleMap.put(role, id);
                }
            }
        }

        // Second pass: build full-path map and leaf-name map
        Map<String, String> pathMap = new HashMap<>();
        Map<String, String> nameMap = new HashMap<>();
        for (JsonNode mailbox : list) {
            String id = mailbox.path("id").asText();
            String fullPath = buildPath(id, idToName, idToParentId);
            String lower = fullPath.toLowerCase();
            pathMap.put(lower, id);
            // leaf name fallback (last segment)
            String leafName = idToName.getOrDefault(id, "");
            nameMap.put(leafName.toLowerCase(), id);
        }

        this.pathToId = Collections.unmodifiableMap(pathMap);
        this.nameToId = Collections.unmodifiableMap(nameMap);
        this.roleToId = Collections.unmodifiableMap(roleMap);

        LOG.info("Resolved {} mailboxes", pathToId.size());
        LOG.debug("Mailbox paths: {}", pathToId.keySet());
    }

    /**
     * Returns the JMAP mailbox ID for the given folder name or path (case-insensitive).
     *
     * <p>Accepts full slash-delimited paths (e.g. {@code Inbox/Papertrail/Shopping})
     * and bare leaf names (e.g. {@code Shopping}) as a fallback.
     * Path lookup is tried first.
     *
     * @param name the full folder path (e.g. {@code Inbox/Papertrail/Shopping}) or bare name
     * @return the JMAP mailbox ID
     * @throws IOException if no mailbox matching the given name or path was found
     */
    public String getMailboxId(String name) throws IOException {
        String lower = name.toLowerCase();
        // Try full path first (e.g. "papertrail/shopping")
        String id = pathToId.get(lower);
        // Fall back to leaf name (e.g. "shopping")
        if (id == null) {
            id = nameToId.get(lower);
        }
        if (id == null) {
            throw new IOException("Mailbox not found: " + name);
        }
        return id;
    }

    /**
     * Builds the full slash-delimited path for a mailbox by traversing its parent chain.
     */
    private static String buildPath(
        String id,
        Map<String, String> idToName,
        Map<String, String> idToParentId
    ) {
        String name = idToName.getOrDefault(id, id);
        String parentId = idToParentId.get(id);
        if (parentId == null) {
            return name;
        }
        return buildPath(parentId, idToName, idToParentId) + "/" + name;
    }

    /**
     * Returns the JMAP mailbox ID for the inbox.
     *
     * @return the JMAP mailbox ID of the inbox
     * @throws IOException if the inbox mailbox was not found
     */
    public String getInboxId() throws IOException {
        String id = roleToId.get(ROLE_INBOX);
        if (id == null) {
            throw new IOException("Inbox mailbox not found");
        }
        return id;
    }

    /**
     * Returns the JMAP mailbox ID for the trash folder.
     *
     * @return the JMAP mailbox ID of the trash folder
     * @throws IOException if the trash mailbox was not found
     */
    public String getTrashId() throws IOException {
        String id = roleToId.get(ROLE_TRASH);
        if (id == null) {
            throw new IOException("Trash mailbox not found");
        }
        return id;
    }

    /**
     * Returns the JMAP mailbox ID for the spam/junk folder.
     *
     * @return the JMAP mailbox ID of the spam/junk folder
     * @throws IOException if the spam/junk mailbox was not found
     */
    public String getSpamId() throws IOException {
        String id = roleToId.get(ROLE_JUNK);
        if (id == null) {
            throw new IOException("Spam/Junk mailbox not found");
        }
        return id;
    }

    /**
     * Returns the JMAP mailbox ID for the archive folder.
     *
     * @return the JMAP mailbox ID of the archive folder
     * @throws IOException if the archive mailbox was not found
     */
    public String getArchiveId() throws IOException {
        String id = roleToId.get(ROLE_ARCHIVE);
        if (id == null) {
            throw new IOException("Archive mailbox not found");
        }
        return id;
    }
}
