package co.kuznetsov.mailkick.rules;

/**
 * Outcome of applying a rule to an email.
 *
 * <p>When {@link #isFullyHandled()} is {@code true}, no further processing is needed.
 * When {@code false}, the email has been moved to a staging folder and must be
 * processed by the LLM using {@link #getPromptName()}.
 */
public final class RuleExecutionOutcome {

    private final boolean fullyHandled;
    private final String promptName;

    private RuleExecutionOutcome(boolean fullyHandled, String promptName) {
        this.fullyHandled = fullyHandled;
        this.promptName = promptName;
    }

    /** Creates an outcome indicating no further processing is needed. */
    public static RuleExecutionOutcome handled() {
        return new RuleExecutionOutcome(true, null);
    }

    /**
     * Creates an outcome indicating LLM processing is required with the given prompt.
     *
     * @param promptName the prompt name to use (from the matched rule)
     */
    public static RuleExecutionOutcome processWithLlm(String promptName) {
        return new RuleExecutionOutcome(false, promptName);
    }

    /** Returns {@code true} if the rule fully handled the email with no LLM needed. */
    public boolean isFullyHandled() {
        return fullyHandled;
    }

    /**
     * Returns the prompt name for LLM processing, or {@code null} if fully handled.
     */
    public String getPromptName() {
        return promptName;
    }
}
