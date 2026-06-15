package co.kuznetsov.mailkick.jmap;

import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Retry utility for JMAP operations.
 *
 * <p>All JMAP calls that must not be dropped should be wrapped with {@link #withRetry},
 * which retries indefinitely with a fixed {@value #RETRY_DELAY_MS}ms backoff until the
 * operation succeeds. Health callbacks allow callers to surface failures to the health tracker
 * immediately — no grace period.
 *
 * <p>An optional {@code onRepeatedFailure} callback is invoked after
 * {@value #FLUSH_AFTER_FAILURES} consecutive failures. The triage processor uses this to
 * flush the {@link MailboxResolver} cache so that a newly-created folder becomes visible
 * without a restart.
 *
 * <p>Thread interruption is respected: if the sleeping thread is interrupted, the interrupted
 * flag is restored and a {@link RuntimeException} is thrown.
 */
public final class JmapRetry {

    private static final Logger LOG = LoggerFactory.getLogger(JmapRetry.class);

    /** Delay in milliseconds between retry attempts. */
    private static final int RETRY_DELAY_MS = 5000;

    /**
     * Number of consecutive failures after which {@code onRepeatedFailure} is triggered.
     * Chosen to be large enough to survive transient JMAP blips but small enough to
     * unblock a stale-cache loop quickly.
     */
    static final int FLUSH_AFTER_FAILURES = 3;

    private JmapRetry() {
    }

    /**
     * A JMAP operation that may return a value and throw any exception.
     *
     * @param <T> the return type
     */
    @FunctionalInterface
    public interface JmapOperation<T> {
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
     * <p>After {@value #FLUSH_AFTER_FAILURES} consecutive failures the
     * {@code onRepeatedFailure} callback is invoked (if non-null) and the consecutive
     * failure counter is reset, allowing the callback to fire again if failures persist.
     *
     * @param <T>               the return type
     * @param operationName     a short label used in log messages to identify the operation
     * @param operation         the JMAP operation to execute
     * @param onFailure         called with the error message on each failure; may be {@code null}
     * @param onSuccess         called once the operation succeeds after a prior failure; may be {@code null}
     * @param onRepeatedFailure called after {@value #FLUSH_AFTER_FAILURES} consecutive failures; may be {@code null}
     * @return the operation result
     * @throws RuntimeException if the thread is interrupted while waiting to retry
     */
    public static <T> T withRetry(String operationName, JmapOperation<T> operation,
            Consumer<String> onFailure, Runnable onSuccess, Runnable onRepeatedFailure) {
        boolean everFailed = false;
        int consecutiveFailures = 0;
        while (true) {
            try {
                T result = operation.execute();
                if (everFailed && onSuccess != null) {
                    onSuccess.run();
                }
                return result;
            } catch (Exception e) {
                everFailed = true;
                consecutiveFailures++;
                LOG.warn(
                    "JMAP '{}' failed (attempt {}), retrying in {}ms: {}",
                    operationName,
                    consecutiveFailures,
                    RETRY_DELAY_MS,
                    e.getMessage()
                );
                if (onFailure != null) {
                    onFailure.accept(operationName + " failed: " + e.getMessage());
                }
                if (onRepeatedFailure != null && consecutiveFailures % FLUSH_AFTER_FAILURES == 0) {
                    LOG.info(
                        "JMAP '{}' failed {} times in a row — flushing mailbox cache",
                        operationName,
                        consecutiveFailures
                    );
                    onRepeatedFailure.run();
                }
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(
                        "JMAP retry interrupted for: " + operationName, ie
                    );
                }
            }
        }
    }

    /**
     * Executes the given operation, retrying indefinitely with {@value #RETRY_DELAY_MS}ms
     * backoff on any failure.  Equivalent to calling the five-argument overload with a
     * {@code null} {@code onRepeatedFailure}.
     *
     * @param <T>           the return type
     * @param operationName a short label used in log messages to identify the operation
     * @param operation     the JMAP operation to execute
     * @param onFailure     called with the error message on each failure; may be {@code null}
     * @param onSuccess     called once the operation succeeds after a prior failure; may be {@code null}
     * @return the operation result
     * @throws RuntimeException if the thread is interrupted while waiting to retry
     */
    public static <T> T withRetry(String operationName, JmapOperation<T> operation,
            Consumer<String> onFailure, Runnable onSuccess) {
        return withRetry(operationName, operation, onFailure, onSuccess, null);
    }

    /**
     * Executes the given void operation, retrying indefinitely with {@value #RETRY_DELAY_MS}ms
     * backoff on any failure.
     *
     * @param operationName a short label used in log messages
     * @param operation     the void JMAP operation to execute
     * @param onFailure     called with the error message on each failure; may be {@code null}
     * @param onSuccess     called once the operation succeeds after a prior failure; may be {@code null}
     * @throws RuntimeException if the thread is interrupted while waiting to retry
     */
    public static void withRetry(String operationName, Runnable operation,
            Consumer<String> onFailure, Runnable onSuccess) {
        withRetry(operationName, () -> {
            operation.run();
            return null;
        }, onFailure, onSuccess, null);
    }
}
