package co.kuznetsov.mailkick.server;

import co.kuznetsov.mailkick.agent.AgentPromptLoader;
import co.kuznetsov.mailkick.model.HealthComponent;
import co.kuznetsov.mailkick.agent.AnthropicAgent;
import co.kuznetsov.mailkick.agent.MediaFeedClient;
import co.kuznetsov.mailkick.agent.ToolRegistry;
import co.kuznetsov.mailkick.agent.ToolRegistry.ToolEntry;
import co.kuznetsov.mailkick.agent.executor.AddRuleTool;
import co.kuznetsov.mailkick.agent.executor.ArchiveTool;
import co.kuznetsov.mailkick.agent.executor.MarkAsReadTool;
import co.kuznetsov.mailkick.agent.executor.MarkAsUnreadTool;
import co.kuznetsov.mailkick.agent.executor.MoveToFolderTool;
import co.kuznetsov.mailkick.agent.executor.RemoveRuleTool;
import co.kuznetsov.mailkick.agent.executor.SpamTool;
import co.kuznetsov.mailkick.agent.executor.SubmitToMediaFeedTool;
import co.kuznetsov.mailkick.agent.executor.TrashTool;
import co.kuznetsov.mailkick.jmap.EmailFetcher;
import co.kuznetsov.mailkick.jmap.EmailMover;
import co.kuznetsov.mailkick.jmap.JmapClient;
import co.kuznetsov.mailkick.jmap.JmapSession;
import co.kuznetsov.mailkick.jmap.MailboxResolver;
import co.kuznetsov.mailkick.model.config.S3ClientFactory;
import co.kuznetsov.mailkick.model.ddb.DdbClientFactory;
import co.kuznetsov.mailkick.model.ddb.LogDdbRepository;
import co.kuznetsov.mailkick.model.ddb.RulesDdbRepository;
import co.kuznetsov.mailkick.rules.RuleExecutor;
import co.kuznetsov.mailkick.rules.RulesChecker;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.thymeleaf.TemplateEngine;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Spring configuration for MailKick Phase 9.
 *
 * <p>Wires together all JMAP, DynamoDB, S3, agent, and triage beans required
 * by the application. Secrets are sourced exclusively from environment variables;
 * a missing or blank variable causes an {@link IllegalStateException} at startup.
 */
@Configuration
public class MailKickConfiguration {

    /**
     * Returns the value of the given environment variable, or throws
     * {@link IllegalStateException} if it is absent or blank.
     *
     * @param name the environment variable name
     * @return the non-blank value
     */
    private static String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                "Required env var not set: " + name
            );
        }
        return value;
    }

    /**
     * Creates the JMAP client authenticated with the Fastmail API token.
     *
     * @return a configured {@link JmapClient}
     */
    @Bean
    public JmapClient jmapClient() {
        return new JmapClient(requireEnv("FASTMAIL_API_TOKEN"));
    }

    /**
     * Discovers and returns the active JMAP session.
     *
     * @param client the JMAP client
     * @return the discovered {@link JmapSession}
     * @throws IOException if session discovery fails
     */
    @Bean
    public JmapSession jmapSession(JmapClient client) throws IOException {
        return client.discoverSession();
    }

    /**
     * Creates the mailbox resolver.
     *
     * @param client  the JMAP client
     * @param session the active JMAP session
     * @return a configured {@link MailboxResolver}
     * @throws IOException if mailbox data cannot be loaded
     */
    @Bean
    public MailboxResolver mailboxResolver(
        JmapClient client,
        JmapSession session
    ) throws IOException {
        return new MailboxResolver(client, session);
    }

    /**
     * Creates the email fetcher.
     *
     * @param client  the JMAP client
     * @param session the active JMAP session
     * @return a configured {@link EmailFetcher}
     */
    @Bean
    public EmailFetcher emailFetcher(JmapClient client, JmapSession session) {
        return new EmailFetcher(client, session);
    }

    /**
     * Creates the email mover.
     *
     * @param client  the JMAP client
     * @param session the active JMAP session
     * @return a configured {@link EmailMover}
     */
    @Bean
    public EmailMover emailMover(JmapClient client, JmapSession session) {
        return new EmailMover(client, session);
    }

    /**
     * Creates the DynamoDB client via factory.
     *
     * @return a configured {@link DynamoDbClient}
     */
    @Bean
    public DynamoDbClient dynamoDbClient() {
        return DdbClientFactory.create();
    }

    /**
     * Creates the rules DynamoDB repository wired to the health tracker.
     *
     * @param ddbClient     the DynamoDB client
     * @param healthTracker the health tracker
     * @return a configured {@link RulesDdbRepository}
     */
    @Bean
    public RulesDdbRepository rulesRepository(DynamoDbClient ddbClient, HealthTracker healthTracker) {
        return new RulesDdbRepository(
            ddbClient,
            msg -> healthTracker.recordFailure(HealthComponent.DYNAMODB, msg),
            () -> healthTracker.recordSuccess(HealthComponent.DYNAMODB)
        );
    }

    /**
     * Creates the log DynamoDB repository wired to the health tracker.
     *
     * @param ddbClient     the DynamoDB client
     * @param healthTracker the health tracker
     * @return a configured {@link LogDdbRepository}
     */
    @Bean
    public LogDdbRepository logRepository(DynamoDbClient ddbClient, HealthTracker healthTracker) {
        return new LogDdbRepository(
            ddbClient,
            msg -> healthTracker.recordFailure(HealthComponent.DYNAMODB, msg),
            () -> healthTracker.recordSuccess(HealthComponent.DYNAMODB)
        );
    }

    /**
     * Creates the S3 client via factory.
     *
     * @return a configured {@link S3Client}
     */
    @Bean
    public S3Client s3Client() {
        return S3ClientFactory.create();
    }

    /**
     * Loads the agent prompt configuration from S3 and returns the loader.
     *
     * <p>Tool names are passed explicitly to avoid a circular Spring dependency;
     * the registry constructs tool executors lazily and does not need to exist yet.
     *
     * @param s3Client the S3 client
     * @return a primed {@link AgentPromptLoader}
     * @throws IOException if the configuration cannot be loaded
     */
    @Bean
    public AgentPromptLoader agentPromptLoader(S3Client s3Client) throws IOException {
        co.kuznetsov.mailkick.model.config.MailKickConfigLoader loader =
            co.kuznetsov.mailkick.model.config.MailKickConfigLoader.fromEnv(s3Client);
        AgentPromptLoader promptLoader = new AgentPromptLoader(loader, knownToolNames());
        promptLoader.loadNow();
        return promptLoader;
    }

    private static Set<String> knownToolNames() {
        return Set.of(
            "move_to_folder", "mark_as_read", "mark_as_unread",
            "archive", "spam", "trash",
            "add_rule", "remove_rule", "submit_to_media_feed"
        );
    }

    /**
     * Creates the health tracker.
     *
     * @return a new {@link HealthTracker}
     */
    @Bean
    public HealthTracker healthTracker() {
        return new HealthTracker();
    }

    /**
     * Creates the rules checker.
     *
     * @param rulesRepo the rules DynamoDB repository
     * @return a configured {@link RulesChecker}
     */
    @Bean
    public RulesChecker rulesChecker(RulesDdbRepository rulesRepo) {
        return new RulesChecker(rulesRepo);
    }

    /**
     * Creates the rule executor.
     *
     * @param mover        the email mover
     * @param resolver     the mailbox resolver
     * @param promptLoader the agent prompt loader (provides live config)
     * @return a configured {@link RuleExecutor}
     */
    @Bean
    public RuleExecutor ruleExecutor(
        EmailMover mover,
        MailboxResolver resolver,
        AgentPromptLoader promptLoader
    ) {
        return new RuleExecutor(mover, resolver, promptLoader::getConfig);
    }

    /**
     * Creates the Anthropic agent authenticated with the API key.
     *
     * @return a configured {@link AnthropicAgent}
     */
    @Bean
    public AnthropicAgent anthropicAgent() {
        return new AnthropicAgent(requireEnv("ANTHROPIC_API_KEY"));
    }

    /**
     * Assembles the tool registry with lazy-constructed tool entries.
     * {@code SubmitToMediaFeedTool} is included only when {@code MEDIA_FEED_URL} is set.
     * Tools requiring {@link FolderReadResolver} receive it at execution time via their factory.
     *
     * @param mover     the email mover
     * @param resolver  the mailbox resolver
     * @param rulesRepo the rules DynamoDB repository
     * @return a fully populated {@link ToolRegistry}
     */
    @Bean
    public ToolRegistry toolRegistry(
        EmailMover mover,
        MailboxResolver resolver,
        RulesDdbRepository rulesRepo
    ) {
        MoveToFolderTool moveProto = new MoveToFolderTool(mover, resolver, null);
        MarkAsReadTool markRead = new MarkAsReadTool(mover);
        MarkAsUnreadTool markUnread = new MarkAsUnreadTool(mover);
        ArchiveTool archiveProto = new ArchiveTool(mover, resolver, null);
        SpamTool spamProto = new SpamTool(mover, resolver, null);
        TrashTool trashProto = new TrashTool(mover, resolver, null);
        AddRuleTool addRule = new AddRuleTool(rulesRepo);
        RemoveRuleTool removeRule = new RemoveRuleTool(rulesRepo);

        List<ToolEntry> entries = new ArrayList<>();
        entries.add(new ToolEntry("move_to_folder", moveProto.getToolDeclaration(),
            r -> new MoveToFolderTool(mover, resolver, r)));
        entries.add(new ToolEntry("mark_as_read", markRead.getToolDeclaration(), r -> markRead));
        entries.add(new ToolEntry("mark_as_unread", markUnread.getToolDeclaration(), r -> markUnread));
        entries.add(new ToolEntry("archive", archiveProto.getToolDeclaration(),
            r -> new ArchiveTool(mover, resolver, r)));
        entries.add(new ToolEntry("spam", spamProto.getToolDeclaration(),
            r -> new SpamTool(mover, resolver, r)));
        entries.add(new ToolEntry("trash", trashProto.getToolDeclaration(),
            r -> new TrashTool(mover, resolver, r)));
        entries.add(new ToolEntry("add_rule", addRule.getToolDeclaration(), r -> addRule));
        entries.add(new ToolEntry("remove_rule", removeRule.getToolDeclaration(), r -> removeRule));

        List<ToolEntry> extraEntries = new ArrayList<>();
        String mediaFeedUrl = System.getenv("MEDIA_FEED_URL");
        if (mediaFeedUrl != null && !mediaFeedUrl.isBlank()) {
            SubmitToMediaFeedTool submitTool = new SubmitToMediaFeedTool(new MediaFeedClient(mediaFeedUrl));
            extraEntries.add(new ToolEntry("submit_to_media_feed", submitTool.getToolDeclaration(), r -> submitTool));
        }
        return new ToolRegistry(entries, extraEntries);
    }

    /**
     * Creates the AutoSpam runner that daily promotes purgatory-folder senders to spam rules.
     *
     * @param promptLoader    the agent prompt loader (provides AutoSpam configuration)
     * @param resolver        the mailbox resolver
     * @param fetcher         the email fetcher
     * @param mover           the email mover
     * @param rulesRepository the rules DynamoDB repository
     * @param templateEngine  the Thymeleaf template engine
     * @return a configured {@link AutoSpamRunner}
     */
    @Bean
    public DigestRunner digestRunner(
        AgentPromptLoader promptLoader,
        LogDdbRepository logRepository,
        AnthropicAgent anthropicAgent,
        EmailFetcher emailFetcher,
        MailboxResolver mailboxResolver,
        HealthTracker healthTracker
    ) {
        return new DigestRunner(
            promptLoader,
            logRepository,
            anthropicAgent,
            emailFetcher,
            mailboxResolver,
            healthTracker
        );
    }

    @Bean
    public AutoSpamRunner autoSpamRunner(
        AgentPromptLoader promptLoader,
        MailboxResolver resolver,
        EmailFetcher fetcher,
        EmailMover mover,
        RulesDdbRepository rulesRepository,
        TemplateEngine templateEngine,
        HealthTracker healthTracker
    ) {
        return new AutoSpamRunner(
            promptLoader,
            resolver,
            fetcher,
            mover,
            rulesRepository,
            templateEngine,
            healthTracker
        );
    }

    /**
     * Creates the AutoArchive runner that settles and files thread chains from the archive folder.
     *
     * @param promptLoader   the agent prompt loader (provides AutoArchive configuration)
     * @param resolver       the mailbox resolver
     * @param fetcher        the email fetcher
     * @param mover          the email mover
     * @param anthropicAgent the Anthropic agent
     * @return a configured {@link AutoArchiveRunner}
     */
    @Bean
    public AutoArchiveRunner autoArchiveRunner(
        AgentPromptLoader promptLoader,
        JmapClient client,
        JmapSession session,
        EmailFetcher fetcher,
        EmailMover mover,
        AnthropicAgent anthropicAgent,
        ToolRegistry toolRegistry,
        HealthTracker healthTracker
    ) {
        return new AutoArchiveRunner(promptLoader, client, session, fetcher, mover, anthropicAgent, toolRegistry,
            healthTracker);
    }
}
