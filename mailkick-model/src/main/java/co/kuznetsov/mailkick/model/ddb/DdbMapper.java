package co.kuznetsov.mailkick.model.ddb;

import co.kuznetsov.mailkick.model.LogEntry;
import co.kuznetsov.mailkick.model.Rule;
import co.kuznetsov.mailkick.model.RuleType;
import java.util.HashMap;
import java.util.Map;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Utility class for converting between MailKick model objects and DynamoDB
 * {@code Map<String, AttributeValue>} representations.
 *
 * <p>All methods are static; this class cannot be instantiated.</p>
 *
 * <p>Attribute naming notes:</p>
 * <ul>
 *   <li>{@code timestamp} is the sort key name used as-is. DynamoDB reserved words only
 *       require escaping in expressions (filter/key conditions), not in item attribute maps.</li>
 *   <li>{@code from} is used as-is for the same reason.</li>
 * </ul>
 */
public final class DdbMapper {

    private DdbMapper() {}

    /**
     * Converts a {@link Rule} to a DynamoDB attribute map.
     *
     * <p>Always includes {@code sender} and {@code ruleType}. Only includes
     * {@code targetFolder} and {@code promptName} when non-null.</p>
     *
     * @param rule the rule to convert; must not be {@code null}
     * @return a mutable attribute map suitable for use in a DynamoDB put or update request
     */
    public static Map<String, AttributeValue> toRuleMap(Rule rule) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("sender", AttributeValue.fromS(rule.getSender()));
        item.put("ruleType", AttributeValue.fromS(rule.getRuleType().name()));
        if (rule.getTargetFolder() != null) {
            item.put(
                "targetFolder",
                AttributeValue.fromS(rule.getTargetFolder())
            );
        }
        if (rule.getPromptName() != null) {
            item.put("promptName", AttributeValue.fromS(rule.getPromptName()));
        }
        return item;
    }

    /**
     * Converts a DynamoDB attribute map to a {@link Rule}.
     *
     * <p>Expects {@code sender} and {@code ruleType} to be present. The fields
     * {@code targetFolder} and {@code promptName} are optional and will be {@code null}
     * when absent.</p>
     *
     * @param item the DynamoDB item map; must not be {@code null}
     * @return a {@link Rule} populated from the map
     */
    public static Rule toRule(Map<String, AttributeValue> item) {
        String sender = getString(item, "sender");
        String ruleTypeStr = getString(item, "ruleType");
        if (ruleTypeStr == null) {
            throw new IllegalArgumentException(
                "Rule item for sender '" + sender + "' is missing 'ruleType' attribute"
            );
        }
        RuleType ruleType;
        try {
            ruleType = RuleType.valueOf(ruleTypeStr);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                "Rule item for sender '" + sender + "' has unknown ruleType: '" + ruleTypeStr + "'", e
            );
        }
        String targetFolder = getString(item, "targetFolder");
        String promptName = getString(item, "promptName");
        return new Rule(sender, ruleType, targetFolder, promptName);
    }

    /**
     * Converts a {@link LogEntry} to a DynamoDB attribute map.
     *
     * <p>Always includes {@code date}, {@code timestamp}, {@code messageId}, {@code from},
     * {@code subject}, and {@code action}. Only includes {@code to}, {@code cc}, and
     * {@code detail} when non-null.</p>
     *
     * @param entry the log entry to convert; must not be {@code null}
     * @return a mutable attribute map suitable for use in a DynamoDB put request
     */
    public static Map<String, AttributeValue> toLogEntryMap(LogEntry entry) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("date", AttributeValue.fromS(entry.getDate()));
        item.put("timestamp", AttributeValue.fromS(entry.getTimestamp()));
        item.put("messageId", AttributeValue.fromS(entry.getMessageId()));
        item.put("from", AttributeValue.fromS(entry.getFrom()));
        if (entry.getTo() != null) {
            item.put("to", AttributeValue.fromS(entry.getTo()));
        }
        if (entry.getCc() != null) {
            item.put("cc", AttributeValue.fromS(entry.getCc()));
        }
        item.put("subject", AttributeValue.fromS(entry.getSubject()));
        item.put("action", AttributeValue.fromS(entry.getAction()));
        if (entry.getDetail() != null) {
            item.put("detail", AttributeValue.fromS(entry.getDetail()));
        }
        return item;
    }

    /**
     * Converts a DynamoDB attribute map to a {@link LogEntry}.
     *
     * <p>Reads {@code timestamp} and {@code from} matching the attribute names
     * written by {@link #toLogEntryMap(LogEntry)}.
     * The {@code detail} field is optional and will be {@code null} when absent.</p>
     *
     * @param item the DynamoDB item map; must not be {@code null}
     * @return a {@link LogEntry} populated from the map
     */
    public static LogEntry toLogEntry(Map<String, AttributeValue> item) {
        String date = getString(item, "date");
        String timestamp = getString(item, "timestamp");
        String messageId = getString(item, "messageId");
        String from = getString(item, "from");
        String to = getString(item, "to");
        String cc = getString(item, "cc");
        String subject = getString(item, "subject");
        String action = getString(item, "action");
        String detail = getString(item, "detail");
        return LogEntry.forAction(
            date,
            timestamp,
            messageId,
            from,
            to,
            cc,
            subject,
            action,
            detail
        );
    }

    /**
     * Returns the String value for {@code key} in {@code item}, or {@code null} if the
     * key is absent or the attribute value is null.
     */
    private static String getString(
        Map<String, AttributeValue> item,
        String key
    ) {
        AttributeValue value = item.get(key);
        if (value == null) {
            return null;
        }
        return value.s();
    }
}
