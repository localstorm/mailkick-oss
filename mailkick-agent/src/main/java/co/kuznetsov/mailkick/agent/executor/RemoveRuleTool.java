package co.kuznetsov.mailkick.agent.executor;

import co.kuznetsov.mailkick.agent.ToolExecutor;
import co.kuznetsov.mailkick.model.Email;
import co.kuznetsov.mailkick.model.JsonUtil;
import co.kuznetsov.mailkick.model.ddb.RulesDdbRepository;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.Tool.InputSchema;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.Map;

/**
 * Tool executor that removes a sender rule from DynamoDB by sender key.
 */
public class RemoveRuleTool implements ToolExecutor {

    private static final String TOOL_NAME = "remove_rule";

    private final RulesDdbRepository rulesRepository;

    /**
     * Constructs a {@code RemoveRuleTool} with the given rules repository.
     *
     * @param rulesRepository the {@link RulesDdbRepository} used to delete rules
     */
    public RemoveRuleTool(RulesDdbRepository rulesRepository) {
        this.rulesRepository = rulesRepository;
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
                "sender", Map.of("type", "string", "description", "Email address or domain")
            )))
            .addRequired("sender")
            .build();

        return Tool.builder()
            .name(TOOL_NAME)
            .description("Remove a sender rule by sender key")
            .inputSchema(schema)
            .build();
    }

    @Override
    public void execute(JsonValue input, Email email) throws IOException {
        JsonNode node = parseInput(input);
        String sender = node.path("sender").asText();
        rulesRepository.delete(sender);
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
