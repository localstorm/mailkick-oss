package co.kuznetsov.mailkick.jmap;

import co.kuznetsov.mailkick.model.Email;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.furstenheim.CopyDown;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Converts a JMAP {@code Email/get} response node into a normalised {@link Email} model
 * and builds the XML document for LLM consumption.
 *
 * <p>This is a static utility class; it cannot be instantiated.</p>
 */
public final class EmailNormaliser {

    private static final Logger LOG = LoggerFactory.getLogger(
        EmailNormaliser.class
    );
    private static final CopyDown COPY_DOWN = new CopyDown();
    private static final java.util.regex.Pattern DKIM_PATTERN =
        java.util.regex.Pattern.compile(
            "\\bdkim=([a-zA-Z]+)",
            java.util.regex.Pattern.CASE_INSENSITIVE
        );
    private static final java.util.regex.Pattern SPF_PATTERN =
        java.util.regex.Pattern.compile(
            "\\bspf=([a-zA-Z]+)",
            java.util.regex.Pattern.CASE_INSENSITIVE
        );
    private static final java.util.regex.Pattern DMARC_PATTERN =
        java.util.regex.Pattern.compile(
            "\\bdmarc=([a-zA-Z]+)",
            java.util.regex.Pattern.CASE_INSENSITIVE
        );
    private static final String AUTH_NONE = "none";

    /**
     * MIME types classified as documents. Prefix-matched types (Office XML, ODF, archives)
     * are handled separately in {@link #classifyMimeType}.
     */
    private static final Set<String> DOCUMENT_EXACT_TYPES = Set.of(
        "application/pdf",
        "application/msword",
        "application/rtf",
        "application/vnd.ms-excel",
        "application/vnd.ms-powerpoint",
        "application/vnd.ms-outlook",
        "text/plain",
        "text/csv",
        "text/calendar",
        "application/zip",
        "application/x-zip-compressed",
        "application/x-zip",
        "application/gzip",
        "application/x-tar",
        "application/x-7z-compressed",
        "application/x-rar-compressed"
    );

    private EmailNormaliser() {}

    /**
     * Converts a single JMAP email {@link JsonNode} (from the {@code Email/get} list array)
     * to a normalised {@link Email} model.
     *
     * @param emailNode the JMAP email JSON node to normalise
     * @return a populated {@link Email} instance
     */
    public static Email normalise(JsonNode emailNode) {
        String id = emailNode.path("id").asText("");

        // Message-ID: JMAP exposes as String[] property "messageId"
        JsonNode messageIdArray = emailNode.path("messageId");
        String messageId = (messageIdArray.isArray() &&
            messageIdArray.size() > 0)
            ? messageIdArray.get(0).asText("")
            : "";

        // Date: JMAP exposes as UTCDate string "sentAt"
        String date = emailNode.path("sentAt").asText("");

        // receivedAt (ISO 8601 from JMAP)
        String receivedAt = emailNode.path("receivedAt").asText("");

        // From: extract bare email from first address
        String from = extractFirstEmail(emailNode.path("from"));

        // To: join all emails
        String to = joinEmails(emailNode.path("to"));

        // CC: join all emails (may be missing)
        String cc = joinEmails(emailNode.path("cc"));

        // Subject
        String subject = emailNode.path("subject").asText("");

        // Reply-To
        String replyTo = extractFirstEmail(emailNode.path("replyTo"));

        // In-Reply-To: JMAP exposes as String[] property "inReplyTo"
        JsonNode inReplyToArray = emailNode.path("inReplyTo");
        String inReplyTo = (inReplyToArray.isArray() &&
            inReplyToArray.size() > 0)
            ? inReplyToArray.get(0).asText("")
            : "";

        // Authentication results
        String authResults = emailNode
            .path("header:Authentication-Results:asText")
            .asText("");
        String dkim = parseAuthResult(authResults, DKIM_PATTERN);
        String spf = parseAuthResult(authResults, SPF_PATTERN);
        String dmarc = parseAuthResult(authResults, DMARC_PATTERN);

        // Body
        String body = extractBody(emailNode);

        LOG.debug(
            "Normalised email: id={}, from={}, subject={}",
            id,
            from,
            subject
        );

        List<String> documents = new ArrayList<>();
        List<String> media = new ArrayList<>();
        List<String> other = new ArrayList<>();
        extractAttachments(emailNode, documents, media, other);

        return new Email(
            id,
            messageId,
            date,
            receivedAt,
            from,
            to,
            cc,
            subject,
            replyTo,
            inReplyTo,
            dkim,
            spf,
            dmarc,
            body,
            documents,
            media,
            other
        );
    }

    /**
     * Builds an XML document string representing the given {@link Email} for LLM consumption.
     * The body is wrapped in a CDATA section to preserve its content verbatim.
     *
     * @param email the normalised email to serialise
     * @return an XML string encoding the email's metadata, authentication results, and body
     */
    public static String toXml(Email email) {
        StringBuilder sb = new StringBuilder();
        sb.append("<documents>\n");

        // Document 1: trusted metadata controlled by the mail server
        sb.append("  <document index=\"1\">\n");
        sb.append("    <source>email metadata</source>\n");
        sb.append("    <content>\n");
        sb.append("      <messageId>")
            .append(xmlEscape(email.getMessageId()))
            .append("</messageId>\n");
        sb.append("      <date>")
            .append(xmlEscape(email.getDate()))
            .append("</date>\n");
        sb.append("      <receivedAt>")
            .append(xmlEscape(email.getReceivedAt()))
            .append("</receivedAt>\n");
        sb.append("      <from>")
            .append(xmlEscape(email.getFrom()))
            .append("</from>\n");
        sb.append("      <to>")
            .append(xmlEscape(email.getTo()))
            .append("</to>\n");
        sb.append("      <cc>")
            .append(xmlEscape(email.getCc()))
            .append("</cc>\n");
        sb.append("      <subject>")
            .append(xmlEscape(email.getSubject()))
            .append("</subject>\n");
        sb.append("      <replyTo>")
            .append(xmlEscape(email.getReplyTo()))
            .append("</replyTo>\n");
        sb.append("      <inReplyTo>")
            .append(xmlEscape(email.getInReplyTo()))
            .append("</inReplyTo>\n");
        sb.append("      <authentication>\n");
        sb.append("        <dkim>").append(xmlEscape(email.getDkim())).append("</dkim>\n");
        sb.append("        <spf>").append(xmlEscape(email.getSpf())).append("</spf>\n");
        sb.append("        <dmarc>").append(xmlEscape(email.getDmarc())).append("</dmarc>\n");
        sb.append("      </authentication>\n");
        appendAttachmentsXml(sb, email);
        sb.append("    </content>\n");
        sb.append("  </document>\n");

        // Document 2: untrusted body supplied by the external sender
        sb.append("  <document index=\"2\">\n");
        sb.append("    <source>email body (untrusted content from external sender)</source>\n");
        sb.append("    <content>\n");
        sb.append(xmlEscape(email.getBody()));
        sb.append("\n    </content>\n");
        sb.append("  </document>\n");

        sb.append("</documents>");
        return sb.toString();
    }

    private static String extractFirstEmail(JsonNode addressArray) {
        if (
            addressArray == null ||
            !addressArray.isArray() ||
            addressArray.size() == 0
        ) {
            return "";
        }
        return addressArray.get(0).path("email").asText("");
    }

    private static String joinEmails(JsonNode addressArray) {
        if (addressArray == null || !addressArray.isArray()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode addr : addressArray) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(addr.path("email").asText(""));
        }
        return sb.toString();
    }

    private static String extractBody(JsonNode emailNode) {
        JsonNode bodyValues = emailNode.path("bodyValues");

        // textBody contains the best available text representation.
        // Check the part type: use plain text as-is, convert HTML to Markdown.
        JsonNode textBody = emailNode.path("textBody");
        if (textBody.isArray() && textBody.size() > 0) {
            JsonNode textPart = textBody.get(0);
            String partId = textPart.path("partId").asText("");
            String partType = textPart.path("type").asText("");
            JsonNode part = bodyValues.path(partId);
            if (!part.isMissingNode()) {
                String value = part.path("value").asText("");
                if (!value.isBlank()) {
                    if ("text/html".equalsIgnoreCase(partType)) {
                        return COPY_DOWN.convert(cleanHtml(value));
                    }
                    return value;
                }
            }
        }

        // Fall back to htmlBody explicitly, convert to Markdown
        JsonNode htmlBody = emailNode.path("htmlBody");
        if (htmlBody.isArray() && htmlBody.size() > 0) {
            String partId = htmlBody.get(0).path("partId").asText("");
            JsonNode part = bodyValues.path(partId);
            if (!part.isMissingNode()) {
                String html = part.path("value").asText("");
                if (!html.isBlank()) {
                    return COPY_DOWN.convert(cleanHtml(html));
                }
            }
        }

        return "";
    }

    /**
     * Removes invisible and non-content HTML elements before Markdown conversion.
     * Strips style/script blocks, elements hidden via inline CSS, tracking pixels,
     * and elements with the {@code hidden} or {@code aria-hidden} attribute.
     */
    private static String cleanHtml(String html) {
        org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parse(html);

        // Remove non-content structural elements
        doc.select("style, script, meta, link, noscript").remove();

        // Remove elements explicitly hidden via the HTML hidden attribute
        doc.select("[hidden]").remove();

        // Remove elements marked aria-hidden (assistive tech hides these too)
        doc.select("[aria-hidden=true]").remove();

        // Remove elements with inline CSS that hides them
        for (org.jsoup.nodes.Element el : doc.select("[style]")) {
            String style = el.attr("style").toLowerCase().replaceAll("\\s", "");
            if (
                style.contains("display:none") ||
                style.contains("visibility:hidden") ||
                style.contains("font-size:0") ||
                style.contains("max-height:0px") ||
                style.contains("mso-hide:all") ||
                style.contains("opacity:0")
            ) {
                el.remove();
            }
        }

        // Remove 1x1 tracking pixel images
        for (org.jsoup.nodes.Element img : doc.select("img[width][height]")) {
            String w = img.attr("width").replace("px", "").trim();
            String h = img.attr("height").replace("px", "").trim();
            if (
                ("0".equals(w) || "1".equals(w)) &&
                ("0".equals(h) || "1".equals(h))
            ) {
                img.remove();
            }
        }

        org.jsoup.nodes.Element body = doc.body();
        return body != null ? body.html() : doc.html();
    }

    private static String parseAuthResult(
        String authHeader,
        java.util.regex.Pattern pattern
    ) {
        if (authHeader == null || authHeader.isBlank()) {
            return AUTH_NONE;
        }
        java.util.regex.Matcher matcher = pattern.matcher(authHeader);
        if (matcher.find()) {
            return matcher.group(1).toLowerCase();
        }
        return AUTH_NONE;
    }

    private static void extractAttachments(
        JsonNode emailNode,
        List<String> documents,
        List<String> media,
        List<String> other
    ) {
        JsonNode attachments = emailNode.path("attachments");
        if (!attachments.isArray()) {
            return;
        }
        for (JsonNode part : attachments) {
            String type = part.path("type").asText("application/octet-stream");
            switch (classifyMimeType(type)) {
                case "document" -> documents.add(type);
                case "media" -> media.add(type);
                default -> other.add(type);
            }
        }
    }

    private static String classifyMimeType(String mimeType) {
        if (mimeType == null) {
            return "other";
        }
        String lower = mimeType.toLowerCase();
        if (
            lower.startsWith("image/") ||
            lower.startsWith("video/") ||
            lower.startsWith("audio/")
        ) {
            return "media";
        }
        if (
            DOCUMENT_EXACT_TYPES.contains(lower) ||
            lower.startsWith(
                "application/vnd.openxmlformats-officedocument."
            ) ||
            lower.startsWith("application/vnd.oasis.opendocument.")
        ) {
            return "document";
        }
        return "other";
    }

    private static void appendAttachmentsXml(StringBuilder sb, Email email) {
        List<String> docs = email.getDocumentAttachments();
        List<String> media = email.getMediaAttachments();
        List<String> other = email.getOtherAttachments();
        if (docs.isEmpty() && media.isEmpty() && other.isEmpty()) {
            return;
        }
        sb.append("  <attachments>\n");
        if (!docs.isEmpty()) {
            sb.append("    <documents>")
                .append(xmlEscape(String.join(", ", docs)))
                .append("</documents>\n");
        }
        if (!media.isEmpty()) {
            sb.append("    <media>")
                .append(xmlEscape(String.join(", ", media)))
                .append("</media>\n");
        }
        if (!other.isEmpty()) {
            sb.append("    <other>")
                .append(xmlEscape(String.join(", ", other)))
                .append("</other>\n");
        }
        sb.append("  </attachments>\n");
    }

    private static String xmlEscape(String value) {
        if (value == null) {
            return "";
        }
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;");
    }
}
