package co.kuznetsov.mailkick.model;

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Configuration for the AutoSpam feature.
 *
 * <p>When configured, MailKick runs daily at midnight, scans {@link #getPurgatoryFolder()}
 * for emails older than {@link #getPurgatoryDays()} days, creates SPAM rules for senders
 * whose domain is not in {@link #getExcludedDomainsSet()}, moves those emails to the Spam
 * folder, and places a summary report in {@link #getSummaryFolder()}.
 */
public final class AutoSpamConfig {

    private String purgatoryFolder;
    private String excludedDomains;
    private int purgatoryDays;
    private String summaryFolder;
    private String reportSender;

    /** Returns the full path of the folder to scan for old emails (e.g. {@code Inbox/Untrusted}). */
    public String getPurgatoryFolder() {
        return purgatoryFolder;
    }

    public void setPurgatoryFolder(String purgatoryFolder) {
        this.purgatoryFolder = purgatoryFolder;
    }

    /** Returns the raw comma-separated excluded domain string. */
    public String getExcludedDomains() {
        return excludedDomains;
    }

    public void setExcludedDomains(String excludedDomains) {
        this.excludedDomains = excludedDomains;
    }

    /**
     * Returns the excluded domains as a lowercase {@link Set}.
     * Emails from these domains are never spam-listed.
     *
     * @return immutable set of excluded domain names, never {@code null}
     */
    public Set<String> getExcludedDomainsSet() {
        if (excludedDomains == null || excludedDomains.isBlank()) {
            return Collections.emptySet();
        }
        return Arrays.stream(excludedDomains.split(","))
            .map(String::trim)
            .map(String::toLowerCase)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toSet());
    }

    /** Returns how many days old an email must be to qualify for auto-spam. */
    public int getPurgatoryDays() {
        return purgatoryDays;
    }

    public void setPurgatoryDays(int purgatoryDays) {
        this.purgatoryDays = purgatoryDays;
    }

    /** Returns the folder path where the summary report email is placed. */
    public String getSummaryFolder() {
        return summaryFolder;
    }

    public void setSummaryFolder(String summaryFolder) {
        this.summaryFolder = summaryFolder;
    }

    /** Returns the email address used as both From and To in the summary report. */
    public String getReportSender() {
        return reportSender;
    }

    public void setReportSender(String reportSender) {
        this.reportSender = reportSender;
    }
}
