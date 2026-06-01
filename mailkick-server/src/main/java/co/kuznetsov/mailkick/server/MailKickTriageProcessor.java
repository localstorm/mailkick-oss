package co.kuznetsov.mailkick.server;

import co.kuznetsov.mailkick.agent.ActivityLogger;
import co.kuznetsov.mailkick.agent.AgentPromptLoader;
import co.kuznetsov.mailkick.agent.AnthropicAgent;
import co.kuznetsov.mailkick.agent.FolderReadResolver;
import co.kuznetsov.mailkick.agent.ToolCall;
import co.kuznetsov.mailkick.agent.ToolRegistry;
import co.kuznetsov.mailkick.jmap.EmailMover;
import co.kuznetsov.mailkick.jmap.EmailNormaliser;
import co.kuznetsov.mailkick.jmap.JmapRetry;
import co.kuznetsov.mailkick.jmap.MailboxResolver;
import co.kuznetsov.mailkick.jmap.TriageProcessor;
import co.kuznetsov.mailkick.model.Email;
import co.kuznetsov.mailkick.model.HealthComponent;
import co.kuznetsov.mailkick.model.MailKickConfig;
import co.kuznetsov.mailkick.model.Rule;
import co.kuznetsov.mailkick.model.ddb.LogDdbRepository;
import co.kuznetsov.mailkick.rules.RuleExecutionOutcome;
import co.kuznetsov.mailkick.rules.RuleExecutor;
import co.kuznetsov.mailkick.rules.RulesChecker;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Orchestrates the full Triage email processing pipeline:
 * rules check → oversize check → LLM reasoning → tool execution → finalise → error fallback.
 */
@Component
public final class MailKickTriageProcessor implements TriageProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(
        MailKickTriageProcessor.class
    );

    private static final Set<String> MOVE_TOOLS = Set.of(
        "move_to_folder",
        "archive",
        "spam",
        "trash"
    );

    private final AgentPromptLoader promptLoader;
    private final RulesChecker rulesChecker;
    private final RuleExecutor ruleExecutor;
    private final EmailMover mover;
    private final MailboxResolver resolver;
    private final AnthropicAgent anthropicAgent;
    private final ToolRegistry toolRegistry;
    private final FolderReadResolver folderReadResolver;
    private final ActivityLogger activityLogger;
    private final HealthTracker healthTracker;

    /** Constructs the processor, building internal collaborators from raw dependencies. */
    public MailKickTriageProcessor(
        AgentPromptLoader promptLoader,
        RulesChecker rulesChecker,
        RuleExecutor ruleExecutor,
        EmailMover mover,
        MailboxResolver resolver,
        AnthropicAgent anthropicAgent,
        ToolRegistry toolRegistry,
        LogDdbRepository logRepo,
        HealthTracker healthTracker
    ) {
        this.promptLoader = promptLoader;
        this.rulesChecker = rulesChecker;
        this.ruleExecutor = ruleExecutor;
        this.mover = mover;
        this.resolver = resolver;
        this.anthropicAgent = anthropicAgent;
        this.toolRegistry = toolRegistry;
        this.folderReadResolver = new FolderReadResolver(promptLoader);
        this.activityLogger = new ActivityLogger(logRepo, promptLoader.getConfig().getTimezone());
        this.healthTracker = healthTracker;
    }

    private <T> T withJmapRetry(String name, JmapRetry.JmapOperation<T> call) {
        return JmapRetry.withRetry(
            name,
            call,
            msg -> healthTracker.recordFailure(HealthComponent.TRIAGE, msg),
            () -> healthTracker.recordSuccess(HealthComponent.TRIAGE)
        );
    }

    @Override
    public void process(String emailId, JsonNode emailNode) {
        Email email;
        try {
            email = EmailNormaliser.normalise(emailNode);
        } catch (Exception e) {
            LOG.error(
                "Failed to normalise email {}: {}",
                emailId,
                e.getMessage()
            );
            safeErrorFallback(
                emailId,
                null,
                "Normalisation failed: " + e.getMessage()
            );
            return;
        }

        try {
            runPipeline(emailId, email);
        } catch (Exception e) {
            LOG.error(
                "Pipeline failed for email {} from={}: {}",
                emailId,
                email.getFrom(),
                e.getMessage()
            );
            safeErrorFallback(emailId, email, e.getMessage());
        }
    }

    private void runPipeline(String emailId, Email email) throws IOException {
        LOG.info(
            "Processing email: id={} from={} subject={}",
            emailId,
            email.getFrom(),
            email.getSubject()
        );
        MailKickConfig config = promptLoader.getConfig();

        // Step 1: Rules check
        Optional<Rule> rule = rulesChecker.findRule(email.getFrom());
        if (rule.isPresent()) {
            RuleExecutionOutcome outcome = withJmapRetry(
                "rule.execute:" + emailId,
                () -> ruleExecutor.execute(rule.get(), emailId, email)
            );
            healthTracker.recordSuccess(HealthComponent.FASTMAIL);
            activityLogger.logAction(
                email,
                rule.get().getRuleType().name(),
                "matched: " + rule.get().getSender()
            );
            if (outcome.isFullyHandled()) {
                return;
            }
            // MOVE_TO_FOLDER_WITH_PROCESSING: continue to LLM with named prompt
            runLlm(
                emailId,
                email,
                EmailNormaliser.toXml(email),
                config,
                outcome.getPromptName()
            );
            return;
        }

        // Step 2: Normalise to XML
        String emailXml = EmailNormaliser.toXml(email);

        // Step 3: Oversize check
        int estimatedTokens = anthropicAgent.estimateTokens(emailXml);
        if (estimatedTokens > config.getMaxEmailSizeTokens()) {
            LOG.warn(
                "Email {} oversized: {} tokens > {} limit",
                emailId,
                estimatedTokens,
                config.getMaxEmailSizeTokens()
            );
            String inboxId = resolver.getInboxId();
            withJmapRetry("oversize.move:" + emailId, () -> {
                mover.moveToInboxUnreadFlagged(emailId, inboxId);
                return null;
            });
            activityLogger.logOversize(
                email,
                estimatedTokens,
                config.getMaxEmailSizeTokens()
            );
            return;
        }

        // Step 4: LLM with default prompt
        runLlm(emailId, email, emailXml, config, config.getDefaultPromptName());
    }

    private void runLlm(
        String emailId,
        Email email,
        String emailXml,
        MailKickConfig config,
        String promptName
    ) throws IOException {
        // Guardrail: detect prompt injection in the email body before LLM triage
        try {
            if (anthropicAgent.checkInjection(config, email.getFrom(), email.getSubject(), email.getBody())) {
                LOG.warn(
                    "Prompt injection detected in email {} from={} — routing to Inbox flagged",
                    emailId,
                    email.getFrom()
                );
                String inboxId = resolver.getInboxId();
                withJmapRetry("injection.flag:" + emailId, () -> {
                    mover.moveToInboxUnreadFlagged(emailId, inboxId);
                    return null;
                });
                activityLogger.logAction(email, "INJECTION_DETECTED", "guardrail flagged email body");
                return;
            }
            healthTracker.recordSuccess(HealthComponent.ANTHROPIC);
        } catch (IOException e) {
            healthTracker.recordFailure(HealthComponent.ANTHROPIC, e.getMessage());
            throw e;
        }

        // Call Anthropic
        List<ToolCall> toolCalls;
        try {
            toolCalls = anthropicAgent.process(
                config,
                promptName,
                emailXml,
                toolRegistry.getTools(config.getExtraToolsForPrompt(promptName), config.getDisallowedToolsForPrompt(promptName))
            );
            healthTracker.recordSuccess(HealthComponent.ANTHROPIC);
        } catch (IOException e) {
            healthTracker.recordFailure(
                HealthComponent.ANTHROPIC,
                e.getMessage()
            );
            throw e;
        }

        // Step 5: Execute tools
        boolean anyMoveTool = false;
        boolean erased = false;

        for (ToolCall toolCall : toolCalls) {
            withJmapRetry("tool." + toolCall.getName() + ":" + emailId, () -> {
                toolRegistry.execute(toolCall, email, folderReadResolver);
                return null;
            });
            healthTracker.recordSuccess(HealthComponent.FASTMAIL);
            activityLogger.logAction(
                email,
                toolCall.getName(),
                toolCall.getInput().toString()
            );

            if ("erase".equals(toolCall.getName())) {
                erased = true;
            } else if (MOVE_TOOLS.contains(toolCall.getName())) {
                anyMoveTool = true;
            }
        }

        // Step 6: Finalise
        if (!erased && !anyMoveTool) {
            String inboxId = resolver.getInboxId();
            withJmapRetry("finalise.inbox:" + emailId, () -> {
                mover.moveToInboxUnread(emailId, inboxId);
                return null;
            });
            LOG.debug(
                "Finalise: no move tool called — routed to Inbox unread: {}",
                emailId
            );
        }
    }

    private void safeErrorFallback(
        String emailId,
        Email email,
        String errorDetail
    ) {
        try {
            withJmapRetry("errorFallback:" + emailId, () -> {
                String inboxId = resolver.getInboxId();
                mover.moveToInboxUnreadFlagged(emailId, inboxId);
                return null;
            });
            if (email != null) {
                activityLogger.logError(email, errorDetail);
            }
            LOG.info(
                "Error fallback: email {} moved to Inbox (unread + flagged)",
                emailId
            );
        } catch (RuntimeException e) {
            // withJmapRetry only throws RuntimeException when interrupted (app shutting down).
            // Email may remain in Triage; operator will see TRIAGE unhealthy in /health.
            LOG.error(
                "Safety move interrupted for {} — email may remain in Triage: {}",
                emailId,
                e.getMessage()
            );
            healthTracker.recordFailure(
                HealthComponent.TRIAGE,
                "Error fallback interrupted: " + e.getMessage()
            );
        }
    }
}
