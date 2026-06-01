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

/**
 * Tool executor that strips Markdown from an email body and posts the plain text to the media feed.
 */
public class SubmitToMediaFeedTool implements ToolExecutor {

    private static final String TOOL_NAME = "submit_to_media_feed";

    private final MediaFeedClient mediaFeedClient;

    /**
     * Constructs a {@code SubmitToMediaFeedTool} with the given media feed client.
     *
     * @param mediaFeedClient the {@link MediaFeedClient} used to submit content
     */
    public SubmitToMediaFeedTool(MediaFeedClient mediaFeedClient) {
        this.mediaFeedClient = mediaFeedClient;
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
        mediaFeedClient.submit(plainText, compressionFactor);
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
