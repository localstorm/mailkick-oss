package co.kuznetsov.mailkick.agent;

import co.kuznetsov.mailkick.model.JsonUtil;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Plain HTTP client for submitting content to the media feed service.
 *
 * <p>Sends JSON POST requests to a configurable endpoint. No authentication is required.
 * The target URL is supplied either directly via the constructor or read from the
 * {@code MEDIA_FEED_URL} environment variable via {@link #fromEnv()}.</p>
 */
public class MediaFeedClient {

    private static final MediaType JSON = MediaType.get(
        "application/json; charset=utf-8"
    );

    private static final Logger LOG = LoggerFactory.getLogger(
        MediaFeedClient.class
    );

    private final String mediaFeedUrl;
    private final OkHttpClient httpClient;

    /**
     * Constructs a {@code MediaFeedClient} targeting the given URL.
     *
     * @param mediaFeedUrl the base URL of the media feed service endpoint
     */
    public MediaFeedClient(String mediaFeedUrl) {
        this.mediaFeedUrl = mediaFeedUrl;
        this.httpClient = new OkHttpClient();
    }

    /**
     * Creates a {@code MediaFeedClient} by reading the target URL from the
     * {@code MEDIA_FEED_URL} environment variable.
     *
     * @return a configured {@code MediaFeedClient}
     * @throws IllegalStateException if {@code MEDIA_FEED_URL} is not set or is blank
     */
    public static MediaFeedClient fromEnv() {
        String url = System.getenv("MEDIA_FEED_URL");
        if (url == null || url.isBlank()) {
            throw new IllegalStateException(
                "MEDIA_FEED_URL environment variable must be set"
            );
        }
        return new MediaFeedClient(url);
    }

    /**
     * Submits the given text to the media feed service with the specified compression factor.
     *
     * <p>Serialises the arguments as a JSON object and POSTs it to the configured endpoint.
     * Throws {@link IOException} if the server responds with a non-successful HTTP status.</p>
     *
     * @param text               the text content to submit
     * @param compressionFactor  the compression factor hint to include in the request payload
     * @throws IOException if the HTTP call fails or the server returns a non-2xx response
     */
    public void submit(String text, int compressionFactor) throws IOException {
        ObjectNode body = JsonUtil.OBJECT_MAPPER.createObjectNode();
        body.put("text", text);
        body.put("compressionFactor", compressionFactor);
        String json = JsonUtil.OBJECT_MAPPER.writeValueAsString(body);

        RequestBody requestBody = RequestBody.create(JSON, json);
        Request request = new Request.Builder()
            .url(mediaFeedUrl)
            .post(requestBody)
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                ResponseBody responseBody = response.body();
                if (responseBody != null) {
                    responseBody.close();
                }
                throw new IOException(
                    "Media feed POST failed: HTTP " + response.code()
                );
            }
        }

        LOG.info(
            "Submitted to media feed: compressionFactor={}, textLength={}",
            compressionFactor,
            text.length()
        );
    }
}
