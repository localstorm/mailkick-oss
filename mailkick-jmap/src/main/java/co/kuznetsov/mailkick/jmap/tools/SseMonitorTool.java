package co.kuznetsov.mailkick.jmap.tools;

import co.kuznetsov.mailkick.jmap.EmailFetcher;
import co.kuznetsov.mailkick.jmap.EmailNormaliser;
import co.kuznetsov.mailkick.jmap.EventSourceListener;
import co.kuznetsov.mailkick.jmap.JmapClient;
import co.kuznetsov.mailkick.jmap.JmapSession;
import co.kuznetsov.mailkick.jmap.MailboxResolver;
import co.kuznetsov.mailkick.model.Email;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.PrintStream;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * CLI tool that uses SSE exclusively to monitor the Triage folder.
 * No polling fallback. Prints email details as they arrive via SSE StateChange events.
 *
 * <p>Usage: {@code java ... SseMonitorTool <fastmail-api-token>}
 */
public final class SseMonitorTool {

    private static final PrintStream OUT = System.out;
    private static final int QUEUE_POLL_TIMEOUT_SECONDS = 5;

    private SseMonitorTool() {}

    /**
     * Entry point.
     *
     * @param args args[0] must be the FastMail API token
     * @throws Exception on any startup failure
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            OUT.println("Usage: SseMonitorTool <fastmail-api-token> [triage-folder]");
            System.exit(1);
        }

        String token = args[0];
        String triageFolder = args.length > 1 ? args[1] : "Inbox/Triage";

        JmapClient client = new JmapClient(token);
        JmapSession session = client.discoverSession();
        OUT.println("Session: accountId=" + session.getPrimaryAccountId());

        MailboxResolver resolver = new MailboxResolver(client, session);
        String triageId = resolver.getMailboxId(triageFolder);
        OUT.println("Triage mailbox ID: " + triageId);

        EmailFetcher fetcher = new EmailFetcher(client, session);
        String initialState = fetcher.getInitialState();
        OUT.println("Initial email state: " + initialState);

        AtomicBoolean running = new AtomicBoolean(true);
        AtomicReference<String> lastEmailState = new AtomicReference<>(
            initialState
        );
        BlockingQueue<String> signalQueue = new LinkedBlockingQueue<>();

        EventSourceListener listener = new EventSourceListener(
            token,
            session,
            fetcher,
            triageId,
            java.util.List.of(signalQueue),
            lastEmailState,
            running,
            () -> {},
            msg -> {}
        );
        Thread sseThread = new Thread(listener, "mailkick-sse");
        sseThread.start();

        OUT.println(
            "SSE-only monitor started. Drop an email into Triage to test. Ctrl+C to stop."
        );
        OUT.println();

        Runtime.getRuntime().addShutdownHook(
            new Thread(() -> {
                running.set(false);
                sseThread.interrupt();
                OUT.println("Stopped.");
            })
        );

        while (running.get()) {
            String emailId = signalQueue.poll(
                QUEUE_POLL_TIMEOUT_SECONDS,
                TimeUnit.SECONDS
            );
            if (emailId == null) {
                continue;
            }

            Optional<JsonNode> emailNode = fetcher.fetchEmailNode(emailId);
            if (emailNode.isEmpty()) {
                OUT.println("[SSE] Email " + emailId + " no longer exists.");
                continue;
            }

            JsonNode mailboxIds = emailNode.get().path("mailboxIds");
            if (!mailboxIds.has(triageId)) {
                OUT.println(
                    "[SSE] Email " + emailId + " not in Triage, skipping."
                );
                continue;
            }

            Email email = EmailNormaliser.normalise(emailNode.get());
            OUT.println("─────────────────────────────────────────");
            OUT.println("[SSE] New Triage email received:");
            OUT.println("  From:    " + email.getFrom());
            OUT.println("  Subject: " + email.getSubject());
            OUT.println("  Date:    " + email.getDate());
            OUT.println(
                "  DKIM:    " +
                    email.getDkim() +
                    "  SPF: " +
                    email.getSpf() +
                    "  DMARC: " +
                    email.getDmarc()
            );
            OUT.println("─────────────────────────────────────────");
        }
    }
}
