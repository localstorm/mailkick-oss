package co.kuznetsov.mailkick;

import co.kuznetsov.mailkick.agent.AgentPromptLoader;
import co.kuznetsov.mailkick.jmap.EmailFetcher;
import co.kuznetsov.mailkick.jmap.EmailMover;
import co.kuznetsov.mailkick.jmap.JmapClient;
import co.kuznetsov.mailkick.jmap.JmapSession;
import co.kuznetsov.mailkick.jmap.MailboxResolver;
import co.kuznetsov.mailkick.jmap.MailKickBootstrap;
import co.kuznetsov.mailkick.model.HealthComponent;
import co.kuznetsov.mailkick.model.MailKickConfig;
import co.kuznetsov.mailkick.server.AutoArchiveRunner;
import co.kuznetsov.mailkick.server.AutoSpamRunner;
import co.kuznetsov.mailkick.server.DigestRunner;
import co.kuznetsov.mailkick.server.HealthTracker;
import co.kuznetsov.mailkick.server.MailKickTriageProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * MailKick application entry point.
 */
@SpringBootApplication
public class MailKickApplication {

    private static final Logger LOG = LoggerFactory.getLogger(
        MailKickApplication.class
    );

    public static void main(String[] args) {
        LOG.info("Starting MailKick...");
        SpringApplication.run(MailKickApplication.class, args);
    }

    /**
     * Starts the SSE monitor and background prompt reloader after Spring context is ready.
     * Registers a JVM shutdown hook to stop them cleanly.
     */
    @Bean
    public CommandLineRunner startMailKick(
        AgentPromptLoader promptLoader,
        JmapClient jmapClient,
        JmapSession session,
        MailboxResolver resolver,
        EmailFetcher fetcher,
        EmailMover mover,
        MailKickTriageProcessor processor,
        AutoSpamRunner autoSpamRunner,
        AutoArchiveRunner autoArchiveRunner,
        DigestRunner digestRunner,
        HealthTracker healthTracker
    ) {
        return args -> {
            promptLoader.start();
            LOG.info("Config reloader started.");

            MailKickConfig config = promptLoader.getConfig();
            MailKickBootstrap monitor = new MailKickBootstrap();
            String fastmailToken = System.getenv("FASTMAIL_API_TOKEN");
            monitor.start(
                fastmailToken,
                session,
                resolver,
                fetcher,
                mover,
                processor,
                config.getResolvedTriageFolder(),
                config,
                () -> healthTracker.recordSuccess(HealthComponent.FASTMAIL),
                msg -> healthTracker.recordFailure(HealthComponent.FASTMAIL, msg)
            );
            LOG.info("MailKickBootstrap started.");

            autoSpamRunner.start();
            LOG.info("AutoSpamRunner started.");

            autoArchiveRunner.start();
            LOG.info("AutoArchiveRunner started.");

            digestRunner.start();
            LOG.info("DigestRunner started.");

            Runtime.getRuntime().addShutdownHook(
                new Thread(() -> {
                    LOG.info("Shutting down MailKick...");
                    monitor.stop();
                    autoSpamRunner.stop();
                    autoArchiveRunner.stop();
                    digestRunner.stop();
                    promptLoader.stop();
                }, "mailkick-shutdown")
            );

            LOG.info("MailKick is running. Monitoring Inbox/Triage for emails.");
        };
    }
}
