package co.kuznetsov.mailkick.jmap.tools;

import co.kuznetsov.mailkick.jmap.EmailFetcher;
import co.kuznetsov.mailkick.jmap.EmailNormaliser;
import co.kuznetsov.mailkick.jmap.JmapClient;
import co.kuznetsov.mailkick.jmap.JmapSession;
import co.kuznetsov.mailkick.jmap.MailboxResolver;
import co.kuznetsov.mailkick.model.Email;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * CLI tool: connects to FastMail, fetches the most recent email in the Triage folder,
 * prints its normalised XML (metadata + auth) to stdout, and writes the Markdown body
 * to a file named {@code <emailId>.md} in the current working directory.
 *
 * <p>Usage: {@code java -cp ... co.kuznetsov.mailkick.jmap.tools.TriageInspector <fastmail-api-token>}
 */
public final class TriageInspector {

    private static final PrintStream OUT = System.out;

    private TriageInspector() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            OUT.println("Usage: TriageInspector <fastmail-api-token> [triage-folder]");
            System.exit(1);
        }

        String token = args[0];
        String triageFolder = args.length > 1 ? args[1] : "Inbox/Triage";
        OUT.println("Connecting to FastMail...");

        JmapClient client = new JmapClient(token);
        JmapSession session = client.discoverSession();
        OUT.println(
            "Session discovered. Account ID: " + session.getPrimaryAccountId()
        );

        MailboxResolver resolver = new MailboxResolver(client, session);
        String triageId = resolver.getMailboxId(triageFolder);
        OUT.println("Triage mailbox ID: " + triageId);

        String emailId = queryMostRecentEmailId(client, session, triageId);
        if (emailId == null) {
            OUT.println("No emails found in Triage folder.");
            return;
        }
        OUT.println("Most recent email ID: " + emailId);

        EmailFetcher fetcher = new EmailFetcher(client, session);
        Optional<JsonNode> emailNode = fetcher.fetchEmailNode(emailId);
        if (emailNode.isEmpty()) {
            OUT.println("Email not found (may have been moved or deleted).");
            return;
        }

        Email email = EmailNormaliser.normalise(emailNode.get());
        String xml = EmailNormaliser.toXml(email);

        // Write body to a .md file
        Path mdFile = Path.of(emailId + ".md");
        Files.writeString(mdFile, email.getBody(), StandardCharsets.UTF_8);
        OUT.println("Markdown body written to: " + mdFile.toAbsolutePath());

        OUT.println();
        OUT.println("=== Normalised Email (metadata) ===");
        OUT.println();
        // Print XML with body replaced by file reference so terminal stays readable
        OUT.println(
            xml.replaceAll(
                "(?s)(<body><!\\[CDATA\\[).*?(\\]\\]></body>)",
                "$1... (see " + mdFile.getFileName() + ") ...$2"
            )
        );
    }

    private static String queryMostRecentEmailId(
        JmapClient client,
        JmapSession session,
        String triageMailboxId
    ) throws IOException {
        ArrayNode methodCalls = client.newMethodCalls();
        ObjectNode args = client.newArgs();
        args.put("accountId", session.getPrimaryAccountId());
        ObjectNode filter = args.putObject("filter");
        filter.put("inMailbox", triageMailboxId);
        ArrayNode sort = args.putArray("sort");
        ObjectNode sortEntry = sort.addObject();
        sortEntry.put("property", "receivedAt");
        sortEntry.put("isAscending", false);
        args.put("limit", 1);
        client.addMethodCall(methodCalls, "Email/query", args, "q0");

        ArrayNode responses = client.execute(session.getApiUrl(), methodCalls);
        JsonNode queryResult = responses.get(0).get(1);
        JsonNode ids = queryResult.path("ids");

        if (!ids.isArray() || ids.size() == 0) {
            return null;
        }
        return ids.get(0).asText();
    }
}
