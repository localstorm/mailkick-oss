package co.kuznetsov.mailkick.model;

/**
 * Configuration for the AutoArchive feature.
 *
 * <p>When configured, MailKick monitors a designated archive folder, tags arriving emails
 * with a timestamp keyword, waits until all emails in a thread have settled for at least
 * {@link #getSettlingMinutes()} minutes, then asks the LLM to file the whole thread
 * by calling {@code move_chain}.
 */
public final class AutoArchiveConfig {

    private static final int DEFAULT_SETTLING_MINUTES = 10;

    private String archiveFolder;
    private int settlingMinutes = DEFAULT_SETTLING_MINUTES;
    private String archivePromptName;

    /** Returns the full path of the folder to monitor for incoming emails to archive. */
    public String getArchiveFolder() {
        return archiveFolder;
    }

    public void setArchiveFolder(String archiveFolder) {
        this.archiveFolder = archiveFolder;
    }

    /** Returns how many minutes all emails in a thread must have been tagged before filing. */
    public int getSettlingMinutes() {
        return settlingMinutes;
    }

    public void setSettlingMinutes(int settlingMinutes) {
        this.settlingMinutes = settlingMinutes;
    }

    /** Returns the prompt name to use when asking the LLM to file a thread. */
    public String getArchivePromptName() {
        return archivePromptName;
    }

    public void setArchivePromptName(String archivePromptName) {
        this.archivePromptName = archivePromptName;
    }
}
