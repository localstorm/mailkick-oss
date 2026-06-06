package co.kuznetsov.mailkick.agent.executor;

import co.kuznetsov.mailkick.agent.MediaFeedClient;
import co.kuznetsov.mailkick.agent.ToolExecutor;
import co.kuznetsov.mailkick.model.Email;
import co.kuznetsov.mailkick.model.JsonUtil;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.Tool.InputSchema;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Tool executor that strips Markdown from an email body and posts the plain text to the media feed.
 *
 * <p>If the HTTP call fails, this tool retries every {@value #RETRY_DELAY_MS}ms until success,
 * blocking the triage worker. {@code onFailure} and {@code onSuccess} callbacks allow the caller
 * to surface health status to the media feed health component.</p>
 */
public class SubmitToMediaFeedTool implements ToolExecutor {

    private static final String TOOL_NAME = "submit_to_media_feed";
    private static final int RETRY_DELAY_MS = 1000;

    private static final org.slf4j.Logger LOG =
        org.slf4j.LoggerFactory.getLogger(SubmitToMediaFeedTool.class);

    private final MediaFeedClient mediaFeedClient;
    private final Consumer<String> onFailure;
    private final Runnable onSuccess;

    /**
     * Constructs a {@code SubmitToMediaFeedTool} with the given media feed client and health callbacks.
     *
     * @param mediaFeedClient the {@link MediaFeedClient} used to submit content
     * @param onFailure       called with an error message on each failed attempt; may be {@code null}
     * @param onSuccess       called once after the first success following a failure; may be {@code null}
     */
    public SubmitToMediaFeedTool(MediaFeedClient mediaFeedClient, Consumer<String> onFailure, Runnable onSuccess) {
        this.mediaFeedClient = mediaFeedClient;
        this.onFailure = onFailure;
        this.onSuccess = onSuccess;
    }

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public Tool getToolDeclaration() {
        InputSchema schema = InputSchema.builder()
            .type(JsonValue.from("object"))
            .properties(JsonValue.from(Map.of(
                "compressionFactor", Map.of(
                    "type", "integer",
                    "description", "Compression ratio (e.g. 5 = summarise to 1/5th of original)"
                )
            )))
            .addRequired("compressionFactor")
            .build();

        return Tool.builder()
            .name(TOOL_NAME)
            .description("Extract plain text from email body and POST to media feed with compression factor")
            .inputSchema(schema)
            .build();
    }

    @Override
    public void execute(JsonValue input, Email email) throws IOException {
        JsonNode node = parseInput(input);
        int compressionFactor = node.path("compressionFactor").asInt();
        String plainText = stripMarkdown(email.getBody());
        submitWithRetry(plainText, compressionFactor);
    }

    private void submitWithRetry(String plainText, int compressionFactor) {
        boolean everFailed = false;
        while (true) {
            try {
                mediaFeedClient.submit(plainText, compressionFactor);
                if (everFailed && onSuccess != null) {
                    onSuccess.run();
                }
                return;
            } catch (Exception e) {
                everFailed = true;
                LOG.warn("Media feed submit failed, retrying in {}ms: {}", RETRY_DELAY_MS, e.getMessage());
                if (onFailure != null) {
                    onFailure.accept("submit_to_media_feed failed: " + e.getMessage());
                }
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Media feed retry interrupted", ie);
                }
            }
        }
    }

    private static String stripMarkdown(String markdown) {
        if (markdown == null) {
            return "";
        }
        return markdown
            .replaceAll("(?m)^#{1,6}\\s+", "")
            .replaceAll("\\*\\*([^*]+)\\*\\*", "$1")
            .replaceAll("\\*([^*]+)\\*", "$1")
            .replaceAll("__([^_]+)__", "$1")
            .replaceAll("_([^_]+)_", "$1")
            .replaceAll("!\\[[^]]*]\\([^)]*\\)", "")
            .replaceAll("\\[([^]]+)]\\([^)]*\\)", "$1")
            .replaceAll("(?s)```[^`]*```", "")
            .replaceAll("`([^`]+)`", "$1")
            .replaceAll("(?m)^[-*+]\\s+", "")
            .replaceAll("(?m)^\\d+\\.\\s+", "")
            .replaceAll("\\n{3,}", "\n\n")
            .strip();
    }

    private static JsonNode parseInput(JsonValue input) throws IOException {
        try {
            String json = JsonUtil.OBJECT_MAPPER.writeValueAsString(input);
            return JsonUtil.OBJECT_MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IOException("Failed to parse tool input: " + e.getMessage(), e);
        }
    }
}
