package co.kuznetsov.mailkick.model;

/**
 * Defines the type of action a rule applies to matching emails.
 */
public enum RuleType {
    /** Move to a named folder, mark as read, no LLM processing. */
    MOVE_TO_FOLDER_NO_PROCESSING,
    /** Move to a destination folder immediately, then run LLM with named prompt. */
    MOVE_TO_FOLDER_WITH_PROCESSING,
    /** Move to FastMail Spam folder. */
    SPAM,
    /** Move to FastMail Trash folder. */
    TRASH,
    /** Permanently delete via JMAP destroy. */
    ERASE
}
