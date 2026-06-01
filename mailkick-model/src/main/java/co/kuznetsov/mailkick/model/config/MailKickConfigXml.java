package co.kuznetsov.mailkick.model.config;

import co.kuznetsov.mailkick.model.MailKickConfig;
import java.io.IOException;
import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * Parses and serialises {@link MailKickConfig} to and from XML format.
 *
 * <p>The expected XML format is:
 * <pre>{@code
 * <?xml version="1.0" encoding="UTF-8"?>
 * <config>
 *     <model>claude-sonnet-4-5</model>
 *     <timezone>America/New_York</timezone>
 *     <maxEmailSizeTokens>100000</maxEmailSizeTokens>
 *     <defaultPromptName>general</defaultPromptName>
 *     <digestTime>07:00</digestTime>
 *     <digestPromptName>digest</digestPromptName>
 *     <digestSenderAddress>mailkick@example.com</digestSenderAddress>
 *     <prompts>
 *         <prompt name="general"><![CDATA[
 * You are an email assistant...
 *         ]]></prompt>
 *         <prompt name="digest"><![CDATA[
 * Summarize today's email activity...
 *         ]]></prompt>
 *     </prompts>
 * </config>
 * }</pre>
 *
 * <p>CDATA sections in prompt elements are handled transparently by the DOM parser —
 * {@code getTextContent()} returns the raw text regardless of CDATA wrapping.
 */
public final class MailKickConfigXml {

    private MailKickConfigXml() {}

    /**
     * Parses a {@link MailKickConfig} from an XML string.
     *
     * @param xml the XML string to parse
     * @return the parsed {@link MailKickConfig}
     * @throws IOException if the XML cannot be parsed or is structurally invalid
     */
    public static MailKickConfig fromXml(String xml) throws IOException {
        try {
            DocumentBuilderFactory factory =
                DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(
                new InputSource(new StringReader(xml))
            );
            doc.getDocumentElement().normalize();

            MailKickConfig config = new MailKickConfig();
            config.setModel(getTextContent(doc, "model"));
            config.setTimezone(getTextContent(doc, "timezone"));

            String maxTokensStr = getTextContent(doc, "maxEmailSizeTokens");
            if (maxTokensStr != null) {
                config.setMaxEmailSizeTokens(Integer.parseInt(maxTokensStr));
            }

            config.setDefaultPromptName(
                getTextContent(doc, "defaultPromptName")
            );
            config.setDigestTime(getTextContent(doc, "digestTime"));
            config.setDigestPromptName(getTextContent(doc, "digestPromptName"));
            config.setDigestSenderAddress(
                getTextContent(doc, "digestSenderAddress")
            );

            Map<String, String> prompts = new LinkedHashMap<>();
            Map<String, java.util.Set<String>> promptExtraTools = new LinkedHashMap<>();
            Map<String, java.util.Set<String>> promptDisallowTools = new LinkedHashMap<>();
            NodeList promptNodes = doc.getElementsByTagName("prompt");
            for (int i = 0; i < promptNodes.getLength(); i++) {
                Element prompt = (Element) promptNodes.item(i);
                String name = prompt.getAttribute("name");
                String text = prompt.getTextContent().strip();
                if (!name.isBlank() && !text.isEmpty()) {
                    prompts.put(name, text);
                    java.util.Set<String> extras = parseToolAttribute(prompt.getAttribute("extraTools"));
                    if (!extras.isEmpty()) {
                        promptExtraTools.put(name, extras);
                    }
                    java.util.Set<String> disallowed = parseToolAttribute(prompt.getAttribute("disallowTools"));
                    if (!disallowed.isEmpty()) {
                        promptDisallowTools.put(name, disallowed);
                    }
                }
            }
            config.setPrompts(prompts);
            config.setPromptExtraTools(promptExtraTools);
            config.setPromptDisallowTools(promptDisallowTools);

            // Parse markUnread folder patterns
            java.util.List<String> markUnread = new java.util.ArrayList<>();
            NodeList markUnreadNodes = doc.getElementsByTagName("markUnread");
            if (markUnreadNodes.getLength() > 0) {
                Element markUnreadEl = (Element) markUnreadNodes.item(0);
                NodeList folderNodes = markUnreadEl.getElementsByTagName(
                    "folder"
                );
                for (int i = 0; i < folderNodes.getLength(); i++) {
                    String pattern = folderNodes
                        .item(i)
                        .getTextContent()
                        .strip();
                    if (!pattern.isBlank()) {
                        markUnread.add(pattern);
                    }
                }
            }
            config.setMarkUnread(markUnread);
            config.setTriageFolder(getTextContent(doc, "triageFolder"));
            config.setSpamFolder(getTextContent(doc, "spamFolder"));

            // Parse autoSpam configuration
            NodeList autoSpamNodes = doc.getElementsByTagName("autoSpam");
            if (autoSpamNodes.getLength() > 0) {
                Element autoSpamEl = (Element) autoSpamNodes.item(0);
                co.kuznetsov.mailkick.model.AutoSpamConfig autoSpam =
                    new co.kuznetsov.mailkick.model.AutoSpamConfig();
                autoSpam.setPurgatoryFolder(
                    getChildText(autoSpamEl, "purgatoryFolder")
                );
                autoSpam.setExcludedDomains(
                    getChildText(autoSpamEl, "excludedDomains")
                );
                String daysStr = getChildText(autoSpamEl, "purgatoryDays");
                if (daysStr != null) {
                    autoSpam.setPurgatoryDays(Integer.parseInt(daysStr));
                }
                autoSpam.setSummaryFolder(
                    getChildText(autoSpamEl, "summaryFolder")
                );
                autoSpam.setReportSender(
                    getChildText(autoSpamEl, "reportSender")
                );
                config.setAutoSpam(autoSpam);
            }

            NodeList autoArchiveNodes = doc.getElementsByTagName("autoArchive");
            if (autoArchiveNodes.getLength() > 0) {
                Element el = (Element) autoArchiveNodes.item(0);
                co.kuznetsov.mailkick.model.AutoArchiveConfig autoArchive =
                    new co.kuznetsov.mailkick.model.AutoArchiveConfig();
                autoArchive.setArchiveFolder(getChildText(el, "archiveFolder"));
                autoArchive.setArchivePromptName(getChildText(el, "archivePromptName"));
                String minsStr = getChildText(el, "settlingMinutes");
                if (minsStr != null) {
                    autoArchive.setSettlingMinutes(Integer.parseInt(minsStr));
                }
                config.setAutoArchive(autoArchive);
            }

            String returnSentStr = getTextContent(doc, "returnSentToInbox");
            if (returnSentStr != null) {
                config.setReturnSentToInbox(Boolean.parseBoolean(returnSentStr));
            }
            config.setSentFolder(getTextContent(doc, "sentFolder"));

            return config;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(
                "Failed to parse MailKick config XML: " + e.getMessage(),
                e
            );
        }
    }

    /**
     * Serialises a {@link MailKickConfig} to an XML string.
     * Prompt values are wrapped in CDATA sections.
     *
     * @param config the config to serialise
     * @return an XML string representation
     */
    public static String toXml(MailKickConfig config) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<config>\n");
        appendElement(sb, "model", config.getModel());
        appendElement(sb, "timezone", config.getTimezone());
        appendElement(
            sb,
            "maxEmailSizeTokens",
            String.valueOf(config.getMaxEmailSizeTokens())
        );
        appendElement(sb, "defaultPromptName", config.getDefaultPromptName());
        if (config.getDigestTime() != null) {
            appendElement(sb, "digestTime", config.getDigestTime());
        }
        if (config.getDigestPromptName() != null) {
            appendElement(sb, "digestPromptName", config.getDigestPromptName());
        }
        if (config.getDigestSenderAddress() != null) {
            appendElement(
                sb,
                "digestSenderAddress",
                config.getDigestSenderAddress()
            );
        }
        if (config.getPrompts() != null && !config.getPrompts().isEmpty()) {
            sb.append("    <prompts>\n");
            for (Map.Entry<String, String> entry : config
                .getPrompts()
                .entrySet()) {
                sb.append("        <prompt name=\"")
                    .append(xmlEscape(entry.getKey()))
                    .append("\"><![CDATA[\n")
                    .append(entry.getValue())
                    .append("\n        ]]></prompt>\n");
            }
            sb.append("    </prompts>\n");
        }
        sb.append("</config>");
        return sb.toString();
    }

    private static java.util.Set<String> parseToolAttribute(String value) {
        java.util.Set<String> tools = new java.util.LinkedHashSet<>();
        if (value == null || value.isBlank()) {
            return tools;
        }
        for (String tool : value.split(",")) {
            String trimmed = tool.trim();
            if (!trimmed.isEmpty()) {
                tools.add(trimmed);
            }
        }
        return tools;
    }

    private static String getChildText(
        org.w3c.dom.Element parent,
        String tagName
    ) {
        org.w3c.dom.NodeList nodes = parent.getElementsByTagName(tagName);
        if (nodes.getLength() == 0) {
            return null;
        }
        String text = nodes.item(0).getTextContent();
        return (text == null || text.isBlank()) ? null : text.strip();
    }

    private static String getTextContent(Document doc, String tagName) {
        NodeList nodes = doc.getElementsByTagName(tagName);
        if (nodes.getLength() == 0) {
            return null;
        }
        String text = nodes.item(0).getTextContent();
        return (text == null || text.isBlank()) ? null : text.strip();
    }

    private static void appendElement(
        StringBuilder sb,
        String tag,
        String value
    ) {
        if (value != null) {
            sb.append("    <")
                .append(tag)
                .append('>')
                .append(xmlEscape(value))
                .append("</")
                .append(tag)
                .append(">\n");
        }
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
