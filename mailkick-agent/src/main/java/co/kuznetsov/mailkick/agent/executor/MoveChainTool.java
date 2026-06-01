package co.kuznetsov.mailkick.agent.executor;

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
 * Tool executor that moves all emails in a chain (thread) from the archive staging folder
 * to a destination folder resolved by the LLM.
 *
 * <p>After moving, the arrival-time keyword is stripped from every email in the batch so that
 * subsequent AutoArchive cycles do not attempt to re-process them.
 */
public final class MoveChainTool implements ToolExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(MoveChainTool.class);
    private static final String TOOL_NAME = "move_chain";

    private final Map<String, String> emailIdToArrivalKeyword;
    private final String archiveFolderId;
    private final MailboxResolver resolver;
    private final EmailMover mover;

    /**
     * Constructs a {@code MoveChainTool} for a specific thread in the archive folder.
     *
     * @param emailIdToArrivalKeyword map of email ID to its individual {@code mailkick-archived-<ts>}
     *                                keyword; each email carries its own epoch timestamp
     * @param archiveFolderId         the JMAP mailbox ID of the archive staging folder
     * @param resolver                mailbox resolver for looking up the destination folder
     * @param mover                   email mover used to execute the batched move
     */
    public MoveChainTool(
        Map<String, String> emailIdToArrivalKeyword,
        String archiveFolderId,
        MailboxResolver resolver,
        EmailMover mover
    ) {
        this.emailIdToArrivalKeyword = emailIdToArrivalKeyword;
        this.archiveFolderId = archiveFolderId;
        this.resolver = resolver;
        this.mover = mover;
    }

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public Tool getToolDeclaration() {
        InputSchema schema = InputSchema.builder()
            .type(JsonValue.from("object"))
            .properties(JsonValue.from(Map.of(
                "destinationFolder", Map.of(
                    "type", "string",
                    "description", "The full folder path to move this email chain into (e.g. Archive/Projects/Alpha)"
                )
            )))
            .addRequired("destinationFolder")
            .build();

        return Tool.builder()
            .name(TOOL_NAME)
            .description("Move the entire email chain from the archive staging folder to the given destination folder")
            .inputSchema(schema)
            .build();
    }

    @Override
    public void execute(JsonValue input, Email email) throws IOException {
        JsonNode node = parseInput(input);
        String destinationFolder = node.path("destinationFolder").asText();
        String destMailboxId = resolver.getMailboxId(destinationFolder);
        mover.moveAllToMailboxAndRemoveKeyword(emailIdToArrivalKeyword, destMailboxId);
        LOG.info(
            "move_chain: moved {} email(s) from archive folder {} to '{}'",
            emailIdToArrivalKeyword.size(),
            archiveFolderId,
            destinationFolder
        );
    }

    private static JsonNode parseInput(JsonValue input) throws IOException {
        try {
            String json = JsonUtil.OBJECT_MAPPER.writeValueAsString(input);
            return JsonUtil.OBJECT_MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IOException("Failed to parse tool input: " + e.getMessage(), e);
        }
    }
}
