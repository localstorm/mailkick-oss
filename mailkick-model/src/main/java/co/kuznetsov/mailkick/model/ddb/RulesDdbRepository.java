package co.kuznetsov.mailkick.model.ddb;

import co.kuznetsov.mailkick.model.Rule;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;

/**
 * DynamoDB repository for email-routing rules stored in the {@code mailkick.rules} table.
 *
 * <p>The table uses {@code sender} (String) as its partition key. There is no sort key.</p>
 *
 * <p>Instances are thread-safe provided the supplied {@link DynamoDbClient} is thread-safe,
 * which is the default for AWS SDK v2 clients.</p>
 */
public final class RulesDdbRepository {

    private static final Logger LOG = LoggerFactory.getLogger(
        RulesDdbRepository.class
    );

    private static final String TABLE_NAME = "mailkick.rules";

    private final DynamoDbClient client;
    private final Consumer<String> onFailure;
    private final Runnable onSuccess;

    /**
     * Constructs a repository backed by the given DynamoDB client with health callbacks.
     *
     * @param client    the DynamoDB client to use; must not be {@code null}
     * @param onFailure called with the error message on each DynamoDB failure; may be {@code null}
     * @param onSuccess called when a DynamoDB operation succeeds after a prior failure; may be {@code null}
     */
    public RulesDdbRepository(DynamoDbClient client, Consumer<String> onFailure, Runnable onSuccess) {
        this.client = client;
        this.onFailure = onFailure;
        this.onSuccess = onSuccess;
    }

    /**
     * Constructs a repository backed by the given DynamoDB client.
     *
     * @param client the DynamoDB client to use; must not be {@code null}
     */
    public RulesDdbRepository(DynamoDbClient client) {
        this(client, null, null);
    }

    /**
     * Looks up a rule by sender address or domain.
     *
     * @param sender the sender address or domain to look up
     * @return an {@link Optional} containing the rule, or empty if not found
     */
    public Optional<Rule> findBySender(String sender) {
        return DdbRetry.withRetry("rules.findBySender", () -> {
            GetItemRequest request = GetItemRequest.builder()
                .tableName(TABLE_NAME)
                .key(Map.of("sender", AttributeValue.fromS(sender)))
                .build();
            GetItemResponse response = client.getItem(request);
            if (!response.hasItem() || response.item().isEmpty()) {
                return Optional.empty();
            }
            LOG.debug("Looking up rule for sender: {}", sender);
            return Optional.of(DdbMapper.toRule(response.item()));
        }, onFailure, onSuccess);
    }

    /**
     * Saves a rule to the table, creating or replacing any existing item with the same sender.
     *
     * @param rule the rule to save; must not be {@code null}
     */
    public void save(Rule rule) {
        DdbRetry.withRetry("rules.save:" + rule.getSender(), () -> {
            PutItemRequest request = PutItemRequest.builder()
                .tableName(TABLE_NAME)
                .item(DdbMapper.toRuleMap(rule))
                .build();
            client.putItem(request);
            LOG.info(
                "Saved rule for sender: {} type: {}",
                rule.getSender(),
                rule.getRuleType()
            );
        }, onFailure, onSuccess);
    }

    /**
     * Deletes the rule with the given sender key, if it exists.
     *
     * @param sender the sender address or domain whose rule should be deleted
     */
    public void delete(String sender) {
        DdbRetry.withRetry("rules.delete:" + sender, () -> {
            DeleteItemRequest request = DeleteItemRequest.builder()
                .tableName(TABLE_NAME)
                .key(Map.of("sender", AttributeValue.fromS(sender)))
                .build();
            client.deleteItem(request);
            LOG.info("Deleted rule for sender: {}", sender);
        }, onFailure, onSuccess);
    }

    /**
     * Returns all rules in the table by performing a full table scan.
     *
     * <p>This method should only be used when a complete listing is required,
     * as it consumes read capacity proportional to the table size.</p>
     *
     * @return a list of all {@link Rule} objects in the table; never {@code null}
     */
    public List<Rule> findAll() {
        return DdbRetry.withRetry("rules.findAll", () -> {
            ScanRequest request = ScanRequest.builder()
                .tableName(TABLE_NAME)
                .build();
            List<Rule> rules = new ArrayList<>();
            for (ScanResponse page : client.scanPaginator(request)) {
                for (Map<String, AttributeValue> item : page.items()) {
                    rules.add(DdbMapper.toRule(item));
                }
            }
            LOG.debug("Scanned {} rules from table", rules.size());
            return rules;
        }, onFailure, onSuccess);
    }
}
