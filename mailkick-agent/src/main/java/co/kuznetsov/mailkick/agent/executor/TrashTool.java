package co.kuznetsov.mailkick.agent.executor;

import co.kuznetsov.mailkick.agent.FolderReadResolver;
import co.kuznetsov.mailkick.agent.ToolExecutor;
import co.kuznetsov.mailkick.jmap.EmailMover;
import co.kuznetsov.mailkick.jmap.MailboxResolver;
import co.kuznetsov.mailkick.model.Email;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;
import java.io.IOException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Moves the email to the FastMail Trash folder.
 */
public final class TrashTool implements ToolExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(TrashTool.class);
    private static final String TOOL_NAME = "trash";

    private final EmailMover mover;
    private final MailboxResolver resolver;
    private final FolderReadResolver readResolver;

    /**
     * Constructs a {@code TrashTool}.
     *
     * @param mover        the JMAP email mover
     * @param resolver     the mailbox resolver for looking up the Trash folder ID
     * @param readResolver the {@link FolderReadResolver} used to determine read/unread state
     */
    public TrashTool(
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
        return Tool.builder()
            .name(TOOL_NAME)
            .description("Move email to the FastMail Trash folder")
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
        if (readResolver != null) {
            boolean unread = readResolver.shouldMarkUnread("trash");
            mover.setRead(email.getId(), !unread);
        }
        String trashId = resolver.getTrashId();
        mover.moveToMailbox(email.getId(), trashId);
        LOG.info(
            "→ Trash | from={} | subject={}",
            email.getFrom(),
            email.getSubject()
        );
    }
}
