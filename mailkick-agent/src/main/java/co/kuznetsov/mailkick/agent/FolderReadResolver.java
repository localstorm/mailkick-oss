package co.kuznetsov.mailkick.agent;

import co.kuznetsov.mailkick.model.MailKickConfig;

/**
 * Resolves the read/unread status for a destination folder based on
 * the {@code markUnread} patterns configured in {@link MailKickConfig}.
 *
 * <p>A folder path should be marked <em>unread</em> if it matches any entry
 * in the {@code markUnread} list. Matching is case-insensitive and supports:
 * <ul>
 *   <li>Exact paths: {@code Inbox}</li>
 *   <li>Wildcard prefix: {@code Inbox/Feed/*} — matches any path starting with
 *       {@code Inbox/Feed/}</li>
 * </ul>
 *
 * <p>If no patterns match, or the list is empty/null, the folder defaults to
 * <em>read</em>.
 */
public final class FolderReadResolver {

    private final AgentPromptLoader promptLoader;

    /**
     * Constructs a {@code FolderReadResolver} backed by the given prompt loader.
     *
     * @param promptLoader the loader providing the current {@link MailKickConfig}
     */
    public FolderReadResolver(AgentPromptLoader promptLoader) {
        this.promptLoader = promptLoader;
    }

    /**
     * Returns {@code true} if emails moved to the given folder path should be
     * marked <em>unread</em>, based on the current {@code markUnread} configuration.
     *
     * @param folderPath the destination folder path (e.g. {@code Inbox/Papertrail/Shopping})
     * @return {@code true} if the email should be unread, {@code false} for read
     */
    public boolean shouldMarkUnread(String folderPath) {
        if (folderPath == null || promptLoader == null) {
            return false;
        }
        return promptLoader.getConfig().shouldMarkUnread(folderPath);
    }
}
