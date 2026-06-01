package co.kuznetsov.mailkick.model;

/**
 * Immutable value class representing an email-routing rule.
 *
 * <p>Each rule matches emails by sender address or domain and specifies what action
 * should be taken. Use the static factory methods to construct instances for the
 * most common rule types.</p>
 */
public final class Rule {

    private final String sender;
    private final RuleType ruleType;
    private final String targetFolder;
    private final String promptName;

    /**
     * All-args constructor.
     *
     * @param sender       email address or domain used as the DDB partition key
     * @param ruleType     the action to apply to matching emails
     * @param targetFolder destination folder for {@link RuleType#MOVE_TO_FOLDER_NO_PROCESSING}
     *                     and {@link RuleType#MOVE_TO_FOLDER_WITH_PROCESSING}, or {@code null}
     * @param promptName   LLM prompt name for {@link RuleType#MOVE_TO_FOLDER_WITH_PROCESSING},
     *                     or {@code null}
     */
    public Rule(String sender, RuleType ruleType, String targetFolder, String promptName) {
        this.sender = sender;
        this.ruleType = ruleType;
        this.targetFolder = targetFolder;
        this.promptName = promptName;
    }

    /**
     * Creates a rule that moves matching emails to the FastMail Spam folder.
     *
     * @param sender email address or domain
     * @return a new {@link Rule} with type {@link RuleType#SPAM}
     */
    public static Rule spam(String sender) {
        return new Rule(sender, RuleType.SPAM, null, null);
    }

    /**
     * Creates a rule that moves matching emails to the FastMail Trash folder.
     *
     * @param sender email address or domain
     * @return a new {@link Rule} with type {@link RuleType#TRASH}
     */
    public static Rule trash(String sender) {
        return new Rule(sender, RuleType.TRASH, null, null);
    }

    /**
     * Creates a rule that permanently deletes matching emails via JMAP destroy.
     *
     * @param sender email address or domain
     * @return a new {@link Rule} with type {@link RuleType#ERASE}
     */
    public static Rule erase(String sender) {
        return new Rule(sender, RuleType.ERASE, null, null);
    }

    /**
     * Creates a rule that moves matching emails to a named folder without LLM processing.
     *
     * @param sender       email address or domain
     * @param targetFolder destination folder name
     * @return a new {@link Rule} with type {@link RuleType#MOVE_TO_FOLDER_NO_PROCESSING}
     */
    public static Rule moveToFolder(String sender, String targetFolder) {
        return new Rule(sender, RuleType.MOVE_TO_FOLDER_NO_PROCESSING, targetFolder, null);
    }

    /**
     * Creates a rule that moves matching emails to a destination folder and then runs LLM processing.
     *
     * @param sender        email address or domain
     * @param targetFolder  destination folder name
     * @param promptName    name of the LLM prompt to apply
     * @return a new {@link Rule} with type {@link RuleType#MOVE_TO_FOLDER_WITH_PROCESSING}
     */
    public static Rule moveWithProcessing(String sender, String targetFolder, String promptName) {
        return new Rule(sender, RuleType.MOVE_TO_FOLDER_WITH_PROCESSING, targetFolder, promptName);
    }

    /**
     * Returns the email address or domain that this rule matches (DDB partition key).
     *
     * @return sender pattern
     */
    public String getSender() {
        return sender;
    }

    /**
     * Returns the action this rule applies to matching emails.
     *
     * @return rule type
     */
    public RuleType getRuleType() {
        return ruleType;
    }

    /**
     * Returns the destination folder for {@link RuleType#MOVE_TO_FOLDER_NO_PROCESSING} and
     * {@link RuleType#MOVE_TO_FOLDER_WITH_PROCESSING} rules, or {@code null}.
     *
     * @return target folder name, or {@code null}
     */
    public String getTargetFolder() {
        return targetFolder;
    }

    /**
     * Returns the LLM prompt name for {@link RuleType#MOVE_TO_FOLDER_WITH_PROCESSING} rules,
     * or {@code null}.
     *
     * @return prompt name, or {@code null}
     */
    public String getPromptName() {
        return promptName;
    }
}
