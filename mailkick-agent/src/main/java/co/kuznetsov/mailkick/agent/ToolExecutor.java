package co.kuznetsov.mailkick.agent;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;
import co.kuznetsov.mailkick.model.Email;
import java.io.IOException;

/**
 * Executes a single named tool on behalf of the LLM agent.
 */
public interface ToolExecutor {

    /** Returns the tool name as declared to the Anthropic SDK. */
    String getName();

    /** Returns the tool declaration for the Anthropic SDK. */
    Tool getToolDeclaration();

    /**
     * Executes the tool using the given input and email context.
     *
     * @param input   the tool input arguments as returned by the LLM
     * @param email   the email being processed
     * @throws IOException if the underlying JMAP, DynamoDB, or HTTP call fails
     */
    void execute(JsonValue input, Email email) throws IOException;
}
