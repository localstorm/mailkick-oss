package co.kuznetsov.mailkick.agent;

import com.anthropic.core.JsonValue;

/**
 * Represents a single tool call returned by the Anthropic model.
 * Contains the tool name and its input arguments as a JsonValue.
 */
public final class ToolCall {

    private final String name;
    private final JsonValue input;

    /**
     * Constructs a ToolCall with the given name and input.
     *
     * @param name  the tool name
     * @param input the tool input arguments as a JsonValue
     */
    public ToolCall(String name, JsonValue input) {
        this.name = name;
        this.input = input;
    }

    /**
     * Returns the tool name.
     *
     * @return tool name
     */
    public String getName() {
        return name;
    }

    /**
     * Returns the tool input arguments.
     *
     * @return input as JsonValue
     */
    public JsonValue getInput() {
        return input;
    }

    @Override
    public String toString() {
        return "ToolCall{name='" + name + "', input=" + input + '}';
    }
}
