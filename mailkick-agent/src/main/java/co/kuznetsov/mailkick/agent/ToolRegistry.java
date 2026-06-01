package co.kuznetsov.mailkick.agent;

import com.anthropic.models.messages.Tool;
import co.kuznetsov.mailkick.model.Email;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Declarative registry of available tools.
 *
 * <p>Tool declarations (names and schemas) are stored eagerly so they can be sent to the LLM
 * without any JMAP or config dependencies. Executors are constructed lazily at execution time
 * via per-tool factories, receiving the {@link FolderReadResolver} only when needed.</p>
 */
public class ToolRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(ToolRegistry.class);

    /**
     * Holds the declaration and lazy factory for a single tool.
     *
     * @param name        the tool name as declared to the LLM
     * @param declaration the Anthropic SDK tool declaration
     * @param factory     produces a ready-to-execute {@link ToolExecutor} given a
     *                    {@link FolderReadResolver}; may ignore the resolver if not needed
     */
    public record ToolEntry(String name, Tool declaration, Function<FolderReadResolver, ToolExecutor> factory) {}

    private final Map<String, ToolEntry> entries;
    private final Set<String> extraToolNames;

    /**
     * Constructs a {@code ToolRegistry} with no extra tools.
     *
     * @param entryList standard tool entries
     */
    public ToolRegistry(List<ToolEntry> entryList) {
        this(entryList, List.of());
    }

    /**
     * Constructs a {@code ToolRegistry} from standard and extra tool entries.
     * Extra tools are opt-in per prompt via {@link #getTools(Set, Set)}.
     *
     * @param entryList      standard tool entries, always available
     * @param extraEntryList extra tool entries, included only when explicitly requested
     */
    public ToolRegistry(List<ToolEntry> entryList, List<ToolEntry> extraEntryList) {
        this.entries = new LinkedHashMap<>();
        for (ToolEntry entry : entryList) {
            entries.put(entry.name(), entry);
        }
        this.extraToolNames = new LinkedHashSet<>();
        for (ToolEntry entry : extraEntryList) {
            entries.put(entry.name(), entry);
            extraToolNames.add(entry.name());
        }
    }

    /**
     * Returns the names of all registered tools, including extras.
     *
     * @return unmodifiable set of all known tool names
     */
    public Set<String> getToolNames() {
        return Collections.unmodifiableSet(entries.keySet());
    }

    /**
     * Returns tool declarations for all standard (non-extra) tools.
     *
     * @return ordered list of tool declarations
     */
    public List<Tool> getTools() {
        return getTools(Set.of(), Set.of());
    }

    /**
     * Returns tool declarations for standard tools plus any requested extras, minus disallowed ones.
     *
     * @param requestedExtras names of extra tools to include
     * @param disallowed      names of tools to exclude
     * @return ordered list of tool declarations
     */
    public List<Tool> getTools(Set<String> requestedExtras, Set<String> disallowed) {
        List<Tool> tools = new ArrayList<>();
        for (Map.Entry<String, ToolEntry> e : entries.entrySet()) {
            String name = e.getKey();
            if (disallowed.contains(name)) {
                continue;
            }
            if (!extraToolNames.contains(name) || requestedExtras.contains(name)) {
                tools.add(e.getValue().declaration());
            }
        }
        return tools;
    }

    /**
     * Constructs the executor for the named tool and executes the tool call.
     *
     * @param toolCall     the tool call to execute
     * @param email        the email being processed
     * @param readResolver the folder-read resolver, supplied at execution time
     * @throws IOException if no executor is registered for the tool name, or execution fails
     */
    public void execute(ToolCall toolCall, Email email, FolderReadResolver readResolver) throws IOException {
        ToolEntry entry = entries.get(toolCall.getName());
        if (entry == null) {
            throw new IOException("Unknown tool: " + toolCall.getName());
        }
        entry.factory().apply(readResolver).execute(toolCall.getInput(), email);
        LOG.debug("Executed tool: {} for email: {}", toolCall.getName(), email.getId());
    }
}
