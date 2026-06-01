package co.kuznetsov.mailkick.jmap;

/**
 * Immutable value class holding the discovered JMAP session information.
 *
 * <p>Populated by {@link JmapClient#discoverSession()} from the JMAP session endpoint
 * (RFC 8620 §2). Contains all URLs and the primary account identifier needed to
 * issue JMAP method calls and subscribe to server-sent events.</p>
 */
public final class JmapSession {

    private final String apiUrl;
    private final String eventSourceUrl;
    private final String primaryAccountId;

    /**
     * Constructs a new {@code JmapSession} with all required fields.
     *
     * @param apiUrl           the URL to POST JMAP method calls to
     * @param eventSourceUrl   the SSE URL template containing {@code {types}},
     *                         {@code {closeafter}}, and {@code {ping}} placeholders
     * @param primaryAccountId the account ID for {@code urn:ietf:params:jmap:mail}
     */
    public JmapSession(String apiUrl, String eventSourceUrl, String primaryAccountId) {
        this.apiUrl = apiUrl;
        this.eventSourceUrl = eventSourceUrl;
        this.primaryAccountId = primaryAccountId;
    }

    /**
     * Returns the URL to POST JMAP method calls to.
     *
     * @return the JMAP API URL
     */
    public String getApiUrl() {
        return apiUrl;
    }

    /**
     * Returns the SSE URL template with {@code {types}}, {@code {closeafter}},
     * and {@code {ping}} placeholders.
     *
     * @return the event source URL template
     */
    public String getEventSourceUrl() {
        return eventSourceUrl;
    }

    /**
     * Returns the account ID associated with {@code urn:ietf:params:jmap:mail}.
     *
     * @return the primary mail account ID
     */
    public String getPrimaryAccountId() {
        return primaryAccountId;
    }
}
