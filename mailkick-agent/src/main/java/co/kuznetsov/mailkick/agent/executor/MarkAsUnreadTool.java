package co.kuznetsov.mailkick.agent.executor;

import co.kuznetsov.mailkick.agent.ToolExecutor;
import co.kuznetsov.mailkick.jmap.EmailMover;
import co.kuznetsov.mailkick.model.Email;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;
import java.io.IOException;
import java.util.Map;

/**
 * Marks the email as unread without moving it.
 */
public final class MarkAsUnreadTool implements ToolExecutor {

    private static final String TOOL_NAME = "mark_as_unread";

    private final EmailMover mover;

    /**
     * Constructs a {@code MarkAsUnreadTool}.
     *
     * @param mover the JMAP email mover
     */
    public MarkAsUnreadTool(EmailMover mover) {
        this.mover = mover;
    }

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public Tool getToolDeclaration() {
        return Tool.builder()
            .name(TOOL_NAME)
            .description("Mark the email as unread without moving it")
            .inputSchema(
                Tool.InputSchema.builder()
                    .type(JsonValue.from("object"))
                    .properties(JsonValue.from(Map.of()))
                    .build()
            )
            .build();
    }

    @Override
    public void execute(JsonValue input, Email email) throws IOException {
        mover.setRead(email.getId(), false);
    }
}
