package co.kuznetsov.mailkick.jmap;

import co.kuznetsov.mailkick.model.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP client for JMAP API calls using OkHttp.
 *
 * <p>Handles session discovery against the FastMail JMAP session endpoint and execution
 * of JMAP method-call batches as defined in RFC 8620. All requests are authenticated
 * via a Bearer token supplied at construction time.</p>
 *
 * <p>Typical usage:</p>
 * <pre>
 *     JmapClient client = new JmapClient(bearerToken);
 *     JmapSession session = client.discoverSession();
 *
 *     ArrayNode calls = client.newMethodCalls();
 *     ObjectNode args = client.newArgs();
 *     args.put("accountId", session.getPrimaryAccountId());
 *     client.addMethodCall(calls, "Mailbox/get", args, "c1");
 *
 *     ArrayNode responses = client.execute(session.getApiUrl(), calls);
 * </pre>
 */
public final class JmapClient {

    private static final Logger LOG = LoggerFactory.getLogger(JmapClient.class);

    private static final String SESSION_URL =
        "https://api.fastmail.com/jmap/session";
    private static final String JMAP_CORE = "urn:ietf:params:jmap:core";
    private static final String JMAP_MAIL = "urn:ietf:params:jmap:mail";
    private static final MediaType JSON = MediaType.get(
        "application/json; charset=utf-8"
    );
    private static final int CONNECT_TIMEOUT_SECONDS = 30;
    private static final int READ_TIMEOUT_SECONDS = 60;

    private final String bearerToken;
    private final OkHttpClient httpClient;

    /**
     * Constructs a new {@code JmapClient} authenticated with the supplied Bearer token.
     *
     * @param bearerToken the OAuth2 Bearer token used for all outgoing requests
     */
    public JmapClient(String bearerToken) {
        this.bearerToken = bearerToken;
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build();
    }

    /**
     * Performs JMAP session discovery by issuing a GET to the well-known FastMail
     * session endpoint and parsing the returned JSON document.
     *
     * @return a {@link JmapSession} containing the API URL, event-source URL template,
     *         and the primary mail account ID
     * @throws IOException if the HTTP request fails, returns a non-2xx status, or the
     *                     response body cannot be parsed
     */
    public JmapSession discoverSession() throws IOException {
        Request request = new Request.Builder()
            .url(SESSION_URL)
            .header("Authorization", "Bearer " + bearerToken)
            .get()
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException(
                    "JMAP session discovery failed: HTTP " + response.code()
                );
            }

            ResponseBody body = response.body();
            String json = body != null ? body.string() : "";
            JsonNode root = JsonUtil.OBJECT_MAPPER.readTree(json);

            String apiUrl = root.path("apiUrl").asText();
            String eventSourceUrl = root.path("eventSourceUrl").asText();
            String primaryAccountId = root
                .path("primaryAccounts")
                .path(JMAP_MAIL)
                .asText();

            LOG.debug(
                "Discovered JMAP session: apiUrl={}, accountId={}",
                apiUrl,
                primaryAccountId
            );
            return new JmapSession(apiUrl, eventSourceUrl, primaryAccountId);
        }
    }

    /**
     * Executes a batch of JMAP method calls against the given API URL and returns the
     * {@code methodResponses} array from the server response.
     *
     * <p>The request envelope is built automatically: the {@code using} capability array
     * always includes {@code urn:ietf:params:jmap:core} and {@code urn:ietf:params:jmap:mail}.
     * Any JMAP-level {@code error} response within the returned array causes an
     * {@link IOException} to be thrown immediately.</p>
     *
     * @param apiUrl      the JMAP API endpoint URL (from {@link JmapSession#getApiUrl()})
     * @param methodCalls an {@link ArrayNode} of {@code [methodName, args, callId]} triples,
     *                    typically built with {@link #newMethodCalls()} and
     *                    {@link #addMethodCall(ArrayNode, String, ObjectNode, String)}
     * @return the {@code methodResponses} {@link ArrayNode} from the server
     * @throws IOException if the HTTP request fails, returns a non-2xx status, the body
     *                     cannot be parsed, or any method response contains a JMAP error
     */
    public ArrayNode execute(String apiUrl, ArrayNode methodCalls)
        throws IOException {
        ObjectNode requestBody = JsonUtil.OBJECT_MAPPER.createObjectNode();
        ArrayNode using = requestBody.putArray("using");
        using.add(JMAP_CORE);
        using.add(JMAP_MAIL);
        requestBody.set("methodCalls", methodCalls);
        String json = JsonUtil.OBJECT_MAPPER.writeValueAsString(requestBody);

        Request request = new Request.Builder()
            .url(apiUrl)
            .header("Authorization", "Bearer " + bearerToken)
            .post(RequestBody.create(json, JSON))
            .build();

        LOG.debug("JMAP request: {}", json);

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException(
                    "JMAP API call failed: HTTP " + response.code()
                );
            }

            ResponseBody body = response.body();
            String responseJson = body != null ? body.string() : "";
            LOG.debug("JMAP response: {}", responseJson);
            JsonNode root = JsonUtil.OBJECT_MAPPER.readTree(responseJson);

            JsonNode responses = root.path("methodResponses");
            for (JsonNode entry : responses) {
                String methodName = entry.path(0).asText();
                if ("error".equals(methodName)) {
                    String type = entry.path(1).path("type").asText();
                    String description = entry
                        .path(1)
                        .path("description")
                        .asText();
                    String arguments = entry
                        .path(1)
                        .path("arguments")
                        .toString();
                    throw new IOException(
                        "JMAP error: " +
                            type +
                            " - " +
                            description +
                            " arguments=" +
                            arguments
                    );
                }
            }

            LOG.debug("JMAP execute: {} method calls", methodCalls.size());
            return (ArrayNode) root.path("methodResponses");
        }
    }

    /**
     * Returns a new, empty {@link ArrayNode} for callers to populate with method call
     * triples before passing to {@link #execute(String, ArrayNode)}.
     *
     * @return a fresh empty {@link ArrayNode}
     */
    public ArrayNode newMethodCalls() {
        return JsonUtil.OBJECT_MAPPER.createArrayNode();
    }

    /**
     * Returns a new, empty {@link ObjectNode} for callers to populate with method
     * arguments before passing to {@link #addMethodCall(ArrayNode, String, ObjectNode, String)}.
     *
     * @return a fresh empty {@link ObjectNode}
     */
    public ObjectNode newArgs() {
        return JsonUtil.OBJECT_MAPPER.createObjectNode();
    }

    /**
     * Appends a single JMAP method call triple {@code [methodName, args, callId]} to the
     * supplied {@code methodCalls} array.
     *
     * @param methodCalls the array to append to (created via {@link #newMethodCalls()})
     * @param methodName  the JMAP method name, e.g. {@code "Email/query"}
     * @param args        the method arguments object (created via {@link #newArgs()})
     * @param callId      a client-chosen identifier echoed back in the response, e.g. {@code "c1"}
     */
    public void addMethodCall(
        ArrayNode methodCalls,
        String methodName,
        ObjectNode args,
        String callId
    ) {
        ArrayNode call = JsonUtil.OBJECT_MAPPER.createArrayNode();
        call.add(methodName);
        call.add(args);
        call.add(callId);
        methodCalls.add(call);
    }
}
