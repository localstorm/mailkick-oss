package co.kuznetsov.mailkick.model.ddb;

import co.kuznetsov.mailkick.model.LogEntry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

/**
 * DynamoDB repository for mail-processing log entries stored in the {@code mailkick.log} table.
 *
 * <p>The table uses {@code date} (String, e.g. {@code 2025-01-15}) as its partition key
 * and {@code ts} (String ISO-8601 timestamp) as its sort key.</p>
 *
 * <p>Instances are thread-safe provided the supplied {@link DynamoDbClient} is thread-safe,
 * which is the default for AWS SDK v2 clients.</p>
 */
public final class LogDdbRepository {

    private static final Logger LOG = LoggerFactory.getLogger(
        LogDdbRepository.class
    );

    private static final String TABLE_NAME = "mailkick.log";

    private static final int BATCH_SIZE = 25;

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
    public LogDdbRepository(DynamoDbClient client, Consumer<String> onFailure, Runnable onSuccess) {
        this.client = client;
        this.onFailure = onFailure;
        this.onSuccess = onSuccess;
    }

    /**
     * Constructs a repository backed by the given DynamoDB client.
     *
     * @param client the DynamoDB client to use; must not be {@code null}
     */
    public LogDdbRepository(DynamoDbClient client) {
        this(client, null, null);
    }

    /**
     * Saves a single log entry to the table.
     *
     * @param entry the log entry to save; must not be {@code null}
     */
    public void save(LogEntry entry) {
        DdbRetry.withRetry("log.save", () -> {
            PutItemRequest request = PutItemRequest.builder()
                .tableName(TABLE_NAME)
                .item(DdbMapper.toLogEntryMap(entry))
                .build();
            client.putItem(request);
            LOG.debug(
                "Saved log entry: action={} from={}",
                entry.getAction(),
                entry.getFrom()
            );
        }, onFailure, onSuccess);
    }

    /**
     * Returns all log entries from the table by performing a full table scan.
     *
     * <p>Results are sorted by {@code date} then {@code ts} using natural String ordering,
     * which is correct for ISO-8601 date and timestamp values.</p>
     *
     * <p>This method is intended for digest generation where all entries across all
     * partition keys must be aggregated.</p>
     *
     * @return a sorted list of all {@link LogEntry} objects in the table; never {@code null}
     */
    public List<LogEntry> findAll() {
        return DdbRetry.withRetry("log.findAll", () -> {
            ScanRequest request = ScanRequest.builder()
                .tableName(TABLE_NAME)
                .build();
            List<LogEntry> entries = new ArrayList<>();
            for (ScanResponse page : client.scanPaginator(request)) {
                for (Map<String, AttributeValue> item : page.items()) {
                    entries.add(DdbMapper.toLogEntry(item));
                }
            }
            entries.sort(
                Comparator.comparing(LogEntry::getDate).thenComparing(
                    LogEntry::getTimestamp
                )
            );
            LOG.debug("Found {} log entries", entries.size());
            return entries;
        }, onFailure, onSuccess);
    }

    /**
     * Deletes the given log entries from the table using DynamoDB batch-write operations.
     *
     * <p>Entries are deleted in batches of up to {@value #BATCH_SIZE} items, which is the
     * maximum allowed by the DynamoDB {@code BatchWriteItem} API.</p>
     *
     * @param entries the log entries to delete; must not be {@code null}
     */
    public void deleteAll(List<LogEntry> entries) {
        DdbRetry.withRetry("log.deleteAll", () -> {
            int totalBatches = 0;
            for (int start = 0; start < entries.size(); start += BATCH_SIZE) {
                int end = Math.min(start + BATCH_SIZE, entries.size());
                List<LogEntry> batch = entries.subList(start, end);
                List<WriteRequest> writeRequests = new ArrayList<>(
                    batch.size()
                );
                for (LogEntry entry : batch) {
                    DeleteRequest deleteRequest = DeleteRequest.builder()
                        .key(
                            Map.of(
                                "date",
                                AttributeValue.fromS(entry.getDate()),
                                "timestamp",
                                AttributeValue.fromS(entry.getTimestamp())
                            )
                        )
                        .build();
                    writeRequests.add(
                        WriteRequest.builder()
                            .deleteRequest(deleteRequest)
                            .build()
                    );
                }
                BatchWriteItemRequest batchRequest =
                    BatchWriteItemRequest.builder()
                        .requestItems(Map.of(TABLE_NAME, writeRequests))
                        .build();
                client.batchWriteItem(batchRequest);
                totalBatches++;
            }
            LOG.info(
                "Deleted {} log entries in {} batches",
                entries.size(),
                totalBatches
            );
        }, onFailure, onSuccess);
    }
}
