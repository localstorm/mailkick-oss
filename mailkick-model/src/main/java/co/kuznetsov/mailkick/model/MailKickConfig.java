package co.kuznetsov.mailkick.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * Configuration POJO for the MailKick service.
 *
 * <p>Instances are typically deserialized from an XML file stored in S3.</p>
 */
public final class MailKickConfig {

    private String model;

    private String timezone;

    private int maxEmailSizeTokens;

    private String defaultPromptName;

    private String digestTime;

    private String digestPromptName;

    private String digestSenderAddress;

    private Map<String, String> prompts;

    private Map<String, java.util.Set<String>> promptExtraTools = new java.util.LinkedHashMap<>();

    private Map<String, java.util.Set<String>> promptDisallowTools = new java.util.LinkedHashMap<>();

    @JsonProperty("markUnread")
    private java.util.List<String> markUnread;

    @JsonProperty("triageFolder")
    private String triageFolder;

    @JsonProperty("spamFolder")
    private String spamFolder;

    @JsonProperty("autoSpam")
    private co.kuznetsov.mailkick.model.AutoSpamConfig autoSpam;

    @JsonProperty("autoArchive")
    private co.kuznetsov.mailkick.model.AutoArchiveConfig autoArchive;

    @JsonProperty("returnSentToInbox")
    private boolean returnSentToInbox;

    @JsonProperty("sentFolder")
    private String sentFolder;

    /**
     * No-arg constructor.
     */
    public MailKickConfig() {}

    /**
     * Returns the Anthropic model identifier.
     *
     * @return model identifier
     */
    public String getModel() {
        return model;
    }

    /**
     * Sets the Anthropic model identifier.
     *
     * @param model model identifier
     */
    public void setModel(String model) {
        this.model = model;
    }

    /**
     * Returns the timezone used for scheduling, e.g. {@code "America/New_York"}.
     *
     * @return timezone string
     */
    public String getTimezone() {
        return timezone;
    }

    /**
     * Sets the timezone.
     *
     * @param timezone timezone string
     */
    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    /**
     * Returns the maximum email size in tokens before the email is skipped.
     *
     * @return max token count
     */
    public int getMaxEmailSizeTokens() {
        return maxEmailSizeTokens;
    }

    /**
     * Sets the maximum email size in tokens.
     *
     * @param maxEmailSizeTokens max token count
     */
    public void setMaxEmailSizeTokens(int maxEmailSizeTokens) {
        this.maxEmailSizeTokens = maxEmailSizeTokens;
    }

    /**
     * Returns the name of the default LLM prompt to use when no rule specifies one.
     *
     * @return default prompt name
     */
    public String getDefaultPromptName() {
        return defaultPromptName;
    }

    /**
     * Sets the default prompt name.
     *
     * @param defaultPromptName default prompt name
     */
    public void setDefaultPromptName(String defaultPromptName) {
        this.defaultPromptName = defaultPromptName;
    }

    /**
     * Returns the scheduled digest send time in {@code HH:mm} format, or {@code null}.
     *
     * @return digest time, or {@code null}
     */
    public String getDigestTime() {
        return digestTime;
    }

    /**
     * Sets the digest send time.
     *
     * @param digestTime time in {@code HH:mm} format, or {@code null}
     */
    public void setDigestTime(String digestTime) {
        this.digestTime = digestTime;
    }

    /**
     * Returns the prompt name used for digest generation, or {@code null}.
     *
     * @return digest prompt name, or {@code null}
     */
    public String getDigestPromptName() {
        return digestPromptName;
    }

    /**
     * Sets the digest prompt name.
     *
     * @param digestPromptName digest prompt name, or {@code null}
     */
    public void setDigestPromptName(String digestPromptName) {
        this.digestPromptName = digestPromptName;
    }

    /**
     * Returns the sender address used when delivering digest emails, or {@code null}.
     *
     * @return digest sender address, or {@code null}
     */
    public String getDigestSenderAddress() {
        return digestSenderAddress;
    }

    /**
     * Sets the digest sender address.
     *
     * @param digestSenderAddress sender address, or {@code null}
     */
    public void setDigestSenderAddress(String digestSenderAddress) {
        this.digestSenderAddress = digestSenderAddress;
    }

    /**
     * Returns the map of prompt names to prompt text bodies.
     *
     * @return prompts map
     */
    public Map<String, String> getPrompts() {
        return prompts;
    }

    /**
     * Sets the prompts map.
     *
     * @param prompts map of prompt name to prompt text
     */
    public void setPrompts(Map<String, String> prompts) {
        this.prompts = prompts;
    }

    /**
     * Returns the set of extra tool names enabled for the given prompt, or an empty set
     * if none are configured.
     *
     * @param promptName the prompt name to look up
     * @return set of extra tool names, never {@code null}
     */
    public java.util.Set<String> getExtraToolsForPrompt(String promptName) {
        if (promptExtraTools == null || promptName == null) {
            return java.util.Set.of();
        }
        return promptExtraTools.getOrDefault(promptName, java.util.Set.of());
    }

    /**
     * Sets the map of prompt name to extra tool name sets.
     *
     * @param promptExtraTools map of prompt name to extra tool names
     */
    public void setPromptExtraTools(Map<String, java.util.Set<String>> promptExtraTools) {
        this.promptExtraTools = promptExtraTools;
    }

    /**
     * Returns the set of tool names explicitly disallowed for the given prompt, or an empty set.
     *
     * @param promptName the prompt name to look up
     * @return set of disallowed tool names, never {@code null}
     */
    public java.util.Set<String> getDisallowedToolsForPrompt(String promptName) {
        if (promptDisallowTools == null || promptName == null) {
            return java.util.Set.of();
        }
        return promptDisallowTools.getOrDefault(promptName, java.util.Set.of());
    }

    /**
     * Sets the map of prompt name to disallowed tool name sets.
     *
     * @param promptDisallowTools map of prompt name to disallowed tool names
     */
    public void setPromptDisallowTools(Map<String, java.util.Set<String>> promptDisallowTools) {
        this.promptDisallowTools = promptDisallowTools;
    }

    /**
     * Returns the list of folder path patterns that should result in emails being
     * marked unread. Supports exact paths (e.g. {@code Inbox}) and wildcard prefixes
     * (e.g. {@code Inbox/Feed/*}). May be {@code null} or empty.
     *
     * @return list of markUnread patterns, or {@code null}
     */
    public java.util.List<String> getMarkUnread() {
        return markUnread;
    }

    public void setMarkUnread(java.util.List<String> markUnread) {
        this.markUnread = markUnread;
    }

    /**
     * Returns the full path of the Triage mailbox to monitor (e.g. {@code Inbox/Triage}).
     * Defaults to {@code Inbox/Triage} if not set.
     *
     * @return configured triage folder path, or {@code null}
     */
    public String getTriageFolder() {
        return triageFolder;
    }

    public void setTriageFolder(String triageFolder) {
        this.triageFolder = triageFolder;
    }

    /**
     * Returns the full path of the Spam destination folder (e.g. {@code Spam}).
     * Falls back to the role-based Spam/Junk mailbox if not set.
     *
     * @return configured spam folder path, or {@code null}
     */
    public String getSpamFolder() {
        return spamFolder;
    }

    public void setSpamFolder(String spamFolder) {
        this.spamFolder = spamFolder;
    }

    /**
     * Returns the AutoSpam configuration, or {@code null} if not configured.
     *
     * @return auto-spam config, or {@code null}
     */
    public co.kuznetsov.mailkick.model.AutoSpamConfig getAutoSpam() {
        return autoSpam;
    }

    public void setAutoSpam(
        co.kuznetsov.mailkick.model.AutoSpamConfig autoSpam
    ) {
        this.autoSpam = autoSpam;
    }

    /**
     * Returns the resolved triage folder path, defaulting to {@code Inbox/Triage} if not configured.
     *
     * @return triage folder path, never {@code null}
     */
    public String getResolvedTriageFolder() {
        return (triageFolder != null && !triageFolder.isBlank())
            ? triageFolder
            : "Inbox/Triage";
    }

    /**
     * Returns the resolved spam folder path, or {@code null} if not configured
     * (callers should fall back to the role-based Spam/Junk mailbox in that case).
     *
     * @return spam folder path, or {@code null}
     */
    public String getResolvedSpamFolder() {
        return (spamFolder != null && !spamFolder.isBlank())
            ? spamFolder
            : null;
    }

    /**
     * Returns the configured timezone as a {@link java.time.ZoneId}, defaulting to UTC.
     *
     * @return zone, never {@code null}
     */
    public java.time.ZoneId getZoneId() {
        if (timezone != null && !timezone.isBlank()) {
            return java.time.ZoneId.of(timezone);
        }
        return java.time.ZoneId.of("UTC");
    }

    /**
     * Returns the AutoArchive configuration, or {@code null} if not configured.
     *
     * @return auto-archive config, or {@code null}
     */
    public co.kuznetsov.mailkick.model.AutoArchiveConfig getAutoArchive() {
        return autoArchive;
    }

    public void setAutoArchive(
        co.kuznetsov.mailkick.model.AutoArchiveConfig autoArchive
    ) {
        this.autoArchive = autoArchive;
    }

    /**
     * Returns {@code true} if emails sent by this account should be moved to Inbox on arrival.
     *
     * @return {@code true} when sent-mail-to-inbox is enabled
     */
    public boolean isReturnSentToInbox() {
        return returnSentToInbox;
    }

    public void setReturnSentToInbox(boolean returnSentToInbox) {
        this.returnSentToInbox = returnSentToInbox;
    }

    /**
     * Returns the full path of the Sent mailbox (e.g. {@code Sent}).
     *
     * @return configured sent folder path, or {@code null}
     */
    public String getSentFolder() {
        return sentFolder;
    }

    public void setSentFolder(String sentFolder) {
        this.sentFolder = sentFolder;
    }

    /**
     * Returns {@code true} if the AutoSpam feature is configured.
     *
     * @return {@code true} when {@code autoSpam} is non-null
     */
    public boolean isAutoSpamEnabled() {
        return autoSpam != null;
    }

    /**
     * Returns {@code true} if the AutoArchive feature is configured.
     *
     * @return {@code true} when {@code autoArchive} is non-null
     */
    public boolean isAutoArchiveEnabled() {
        return autoArchive != null;
    }

    /**
     * Returns {@code true} if emails moved to the given folder should be marked unread,
     * based on the {@code markUnread} pattern list.
     *
     * <p>Supports exact paths ({@code Inbox}) and wildcard prefixes ({@code Inbox/Feed/*}).
     * Matching is case-insensitive. Returns {@code false} when the list is empty or null.
     *
     * @param folderPath the destination folder path
     * @return {@code true} = mark unread, {@code false} = mark read
     */
    public boolean shouldMarkUnread(String folderPath) {
        if (folderPath == null || markUnread == null || markUnread.isEmpty()) {
            return false;
        }
        String lower = folderPath.toLowerCase();
        for (String pattern : markUnread) {
            if (matchesFolderPattern(lower, pattern.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesFolderPattern(String path, String pattern) {
        if (pattern.endsWith("/*")) {
            return path.startsWith(pattern.substring(0, pattern.length() - 1));
        }
        return path.equals(pattern);
    }
}
