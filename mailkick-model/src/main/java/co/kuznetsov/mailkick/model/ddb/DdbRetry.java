package co.kuznetsov.mailkick.model.ddb;

import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Retry utility for DynamoDB operations.
 *
 * <p>All DynamoDB calls are wrapped with {@link #withRetry} or {@link #withRetry(String, Runnable)},
 * which retry indefinitely with a fixed {@value #RETRY_DELAY_MS}ms backoff until the operation
 * succeeds. This handles transient infrastructure failures (network blips, throttling, etc.)
 * without dropping data.
 *
 * <p>Thread interruption is respected: if the sleeping thread is interrupted, the interrupted
 * flag is restored and a {@link RuntimeException} is thrown.
 */
public final class DdbRetry {

    private static final Logger LOG = LoggerFactory.getLogger(DdbRetry.class);

    /** Delay in milliseconds between retry attempts. */
    private static final int RETRY_DELAY_MS = 1000;

    private DdbRetry() {
    }

    /**
     * A DynamoDB operation that may return a value and throw any exception.
     *
     * @param <T> the return type
     */
    @FunctionalInterface
    public interface DdbOperation<T> {
        /**
         * Executes the operation.
         *
         * @return the result
         * @throws Exception if the operation fails
         */
        T execute() throws Exception;
    }

    /**
     * Executes the given operation, retrying indefinitely with {@value #RETRY_DELAY_MS}ms
     * backoff on any failure. Calls {@code onFailure} with the error message on each failed
     * attempt, and {@code onSuccess} (no-arg) when the operation eventually succeeds after
     * at least one failure.
     *
     * @param <T>           the return type
     * @param operationName a short label used in log messages to identify the operation
     * @param operation     the DynamoDB operation to execute
     * @param onFailure     called with the error message on each failure; may be {@code null}
     * @param onSuccess     called once the operation succeeds after a prior failure; may be {@code null}
     * @return the operation result
     * @throws RuntimeException if the thread is interrupted while waiting to retry
     */
    public static <T> T withRetry(String operationName, DdbOperation<T> operation,
            Consumer<String> onFailure, Runnable onSuccess) {
        boolean everFailed = false;
        while (true) {
            try {
                T result = operation.execute();
                if (everFailed && onSuccess != null) {
                    onSuccess.run();
                }
                return result;
            } catch (Exception e) {
                everFailed = true;
                LOG.warn(
                    "DDB '{}' failed, retrying in {}ms: {}",
                    operationName,
                    RETRY_DELAY_MS,
                    e.getMessage(),
                    e
                );
                if (onFailure != null) {
                    onFailure.accept(e.getMessage());
                }
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(
                        "DDB retry interrupted for: " + operationName, ie
                    );
                }
            }
        }
    }

    /**
     * Executes the given operation, retrying indefinitely with {@value #RETRY_DELAY_MS}ms
     * backoff on any failure.
     *
     * @param <T>           the return type
     * @param operationName a short label used in log messages to identify the operation
     * @param operation     the DynamoDB operation to execute
     * @return the operation result
     * @throws RuntimeException if the thread is interrupted while waiting to retry
     */
    public static <T> T withRetry(String operationName, DdbOperation<T> operation) {
        return withRetry(operationName, operation, null, null);
    }

    /**
     * Executes the given void operation, retrying indefinitely with {@value #RETRY_DELAY_MS}ms
     * backoff on any failure.
     *
     * <p>This is a convenience overload for operations that return no value.
     * AWS SDK v2 DynamoDB exceptions are all unchecked, so a plain {@link Runnable} suffices.
     *
     * @param operationName a short label used in log messages
     * @param operation     the void DynamoDB operation to execute
     * @param onFailure     called with the error message on each failure; may be {@code null}
     * @param onSuccess     called once the operation succeeds after a prior failure; may be {@code null}
     * @throws RuntimeException if the thread is interrupted while waiting to retry
     */
    public static void withRetry(String operationName, Runnable operation,
            Consumer<String> onFailure, Runnable onSuccess) {
        withRetry(operationName, () -> {
            operation.run();
            return null;
        }, onFailure, onSuccess);
    }

    /**
     * Executes the given void operation, retrying indefinitely with {@value #RETRY_DELAY_MS}ms
     * backoff on any failure.
     *
     * <p>This is a convenience overload for operations that return no value.
     * AWS SDK v2 DynamoDB exceptions are all unchecked, so a plain {@link Runnable} suffices.
     *
     * @param operationName a short label used in log messages
     * @param operation     the void DynamoDB operation to execute
     * @throws RuntimeException if the thread is interrupted while waiting to retry
     */
    public static void withRetry(String operationName, Runnable operation) {
        withRetry(operationName, operation, null, null);
    }
}
