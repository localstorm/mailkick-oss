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
 * Tool executor that moves an email to the FastMail Spam folder and marks it as read.
 */
public class SpamTool implements ToolExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(SpamTool.class);
    private static final String TOOL_NAME = "spam";

    private final EmailMover mover;
    private final MailboxResolver resolver;
    private final FolderReadResolver readResolver;

    /**
     * Constructs a {@code SpamTool} with the given mover and resolver.
     *
     * @param mover        the {@link EmailMover} used to move emails
     * @param resolver     the {@link MailboxResolver} used to resolve the spam mailbox ID
     * @param readResolver the {@link FolderReadResolver} used to determine read/unread state
     */
    public SpamTool(
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
            .properties(JsonValue.from(Map.of()))
            .build();

        return Tool.builder()
            .name(TOOL_NAME)
            .description("Move email to the FastMail Spam folder")
            .inputSchema(schema)
            .build();
    }

    @Override
    public void execute(JsonValue input, Email email) throws IOException {
        if (readResolver != null) {
            boolean unread = readResolver.shouldMarkUnread("spam");
            mover.setRead(email.getId(), !unread);
        }
        String spamId = resolver.getSpamId();
        mover.moveToMailbox(email.getId(), spamId);
        LOG.info(
            "→ Spam | from={} | subject={}",
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
