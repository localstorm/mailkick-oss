package co.kuznetsov.mailkick.agent.executor;

import co.kuznetsov.mailkick.agent.ToolExecutor;
import co.kuznetsov.mailkick.model.Email;
import co.kuznetsov.mailkick.model.JsonUtil;
import co.kuznetsov.mailkick.model.Rule;
import co.kuznetsov.mailkick.model.RuleType;
import co.kuznetsov.mailkick.model.ddb.RulesDdbRepository;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.Tool.InputSchema;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.Map;

/**
 * Tool executor that adds a sender rule to DynamoDB for future email processing.
 */
public class AddRuleTool implements ToolExecutor {

    private static final String TOOL_NAME = "add_rule";

    private final RulesDdbRepository rulesRepository;

    /**
     * Constructs an {@code AddRuleTool} with the given rules repository.
     *
     * @param rulesRepository the {@link RulesDdbRepository} used to persist rules
     */
    public AddRuleTool(RulesDdbRepository rulesRepository) {
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
                "sender", Map.of(
                    "type", "string",
                    "description", "Email address or domain (e.g. user@example.com or example.com)"
                ),
                "ruleType", Map.of(
                    "type", "string",
                    "description", "Rule type: MOVE_TO_FOLDER_NO_PROCESSING, MOVE_TO_FOLDER_WITH_PROCESSING, SPAM, TRASH, ERASE"
                ),
                "targetFolder", Map.of(
                    "type", "string",
                    "description", "Required for MOVE_TO_FOLDER_NO_PROCESSING and MOVE_TO_FOLDER_WITH_PROCESSING"
                ),
                "promptName", Map.of(
                    "type", "string",
                    "description", "Required for MOVE_TO_FOLDER_WITH_PROCESSING"
                )
            )))
            .addRequired("sender")
            .addRequired("ruleType")
            .build();

        return Tool.builder()
            .name(TOOL_NAME)
            .description("Add a sender rule to DynamoDB; applies to future emails only")
            .inputSchema(schema)
            .build();
    }

    @Override
    public void execute(JsonValue input, Email email) throws IOException {
        JsonNode node = parseInput(input);
        String sender = node.path("sender").asText();
        String ruleType = node.path("ruleType").asText();
        String targetFolder = getOptionalString(node, "targetFolder");
        String promptName = getOptionalString(node, "promptName");

        Rule rule;
        switch (RuleType.valueOf(ruleType)) {
            case MOVE_TO_FOLDER_NO_PROCESSING:
                rule = Rule.moveToFolder(sender, targetFolder);
                break;
            case MOVE_TO_FOLDER_WITH_PROCESSING:
                rule = Rule.moveWithProcessing(sender, targetFolder, promptName);
                break;
            case SPAM:
                rule = Rule.spam(sender);
                break;
            case TRASH:
                rule = Rule.trash(sender);
                break;
            case ERASE:
                rule = Rule.erase(sender);
                break;
            default:
                throw new IOException("Unknown ruleType: " + ruleType);
        }

        rulesRepository.save(rule);
    }

    private static String getOptionalString(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText("");
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
