package co.kuznetsov.mailkick.agent.executor;

import co.kuznetsov.mailkick.agent.FolderReadResolver;
import co.kuznetsov.mailkick.agent.ToolExecutor;
import co.kuznetsov.mailkick.jmap.EmailMover;
import co.kuznetsov.mailkick.jmap.MailboxResolver;
import co.kuznetsov.mailkick.model.Email;
import co.kuznetsov.mailkick.model.JsonUtil;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.Tool.InputSchema;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tool executor that moves an email to a named FastMail folder.
 */
public class MoveToFolderTool implements ToolExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(
        MoveToFolderTool.class
    );
    private static final String TOOL_NAME = "move_to_folder";

    private final EmailMover mover;
    private final MailboxResolver resolver;
    private final FolderReadResolver readResolver;

    /**
     * Constructs a {@code MoveToFolderTool} with the given mover and resolver.
     *
     * @param mover        the {@link EmailMover} used to move emails
     * @param resolver     the {@link MailboxResolver} used to resolve mailbox IDs
     * @param readResolver the {@link FolderReadResolver} used to determine read/unread state
     */
    public MoveToFolderTool(
        EmailMover mover,
        MailboxResolver resolver,
        FolderReadResolver readResolver
    ) {
        this.mover = mover;
        this.resolver = resolver;
        this.readResolver = readResolver;
    }

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public Tool getToolDeclaration() {
        InputSchema schema = InputSchema.builder()
            .type(JsonValue.from("object"))
            .properties(
                JsonValue.from(
                    Map.of(
                        "targetFolder",
                        Map.of(
                            "type",
                            "string",
                            "description",
                            "Target folder name"
                        )
                    )
                )
            )
            .addRequired("targetFolder")
            .build();

        return Tool.builder()
            .name(TOOL_NAME)
            .description("Move email to a named FastMail folder")
            .inputSchema(schema)
            .build();
    }

    @Override
    public void execute(JsonValue input, Email email) throws IOException {
        JsonNode inputNode = parseInput(input);
        String targetFolder = inputNode.path("targetFolder").asText("");
        if (readResolver != null) {
            boolean unread = readResolver.shouldMarkUnread(targetFolder);
            mover.setRead(email.getId(), !unread);
        }
        String mailboxId = resolver.getMailboxId(targetFolder);
        mover.moveToMailbox(email.getId(), mailboxId);
        LOG.info(
            "→ {} | from={} | subject={}",
            targetFolder,
            email.getFrom(),
            email.getSubject()
        );
    }

    private static JsonNode parseInput(JsonValue input) throws IOException {
        try {
            String json = JsonUtil.OBJECT_MAPPER.writeValueAsString(input);
            return JsonUtil.OBJECT_MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IOException(
                "Failed to parse tool input: " + e.getMessage(),
                e
            );
        }
    }
}
