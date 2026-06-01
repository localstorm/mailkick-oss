package co.kuznetsov.mailkick.jmap;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;

/**
 * Processes an email confirmed to be in the Triage mailbox.
 * Implementations perform rule checking, LLM reasoning, and tool execution.
 */
public interface TriageProcessor {

    /**
     * Process the given email.
     *
     * @param emailId   the JMAP email ID
     * @param emailNode the full JMAP Email/get response node for this email
     * @throws IOException if any JMAP or downstream call fails
     */
    void process(String emailId, JsonNode emailNode) throws IOException;
}
