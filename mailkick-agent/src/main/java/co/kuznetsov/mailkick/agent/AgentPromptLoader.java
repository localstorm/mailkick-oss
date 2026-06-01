package co.kuznetsov.mailkick.agent;

import co.kuznetsov.mailkick.model.MailKickConfig;
import co.kuznetsov.mailkick.model.config.MailKickConfigLoader;
import co.kuznetsov.mailkick.model.config.MailKickConfigValidator;
import java.io.IOException;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads and periodically reloads {@link MailKickConfig} from S3.
 *
 * <p>On startup, call {@link #loadNow()} to perform an immediate synchronous load, then
 * call {@link #start()} to begin the background reload loop. The background thread
 * reloads the config every {@value #RELOAD_INTERVAL_MS} ms.
 *
 * <p>Health status is reflected by {@link #isHealthy()}: healthy when the last load
 * succeeded and no error is recorded.
 */
public final class AgentPromptLoader {

    /** Reload interval: 5 minutes in milliseconds. */
    private static final int RELOAD_INTERVAL_MS = 300000;

    private static final Logger LOG = LoggerFactory.getLogger(AgentPromptLoader.class);

    private final MailKickConfigLoader loader;
    private final Set<String> knownToolNames;
    private final AtomicReference<MailKickConfig> config = new AtomicReference<>();
    private final AtomicReference<Instant> lastSuccessfulLoad = new AtomicReference<>();
    private final AtomicReference<String> lastError = new AtomicReference<>();
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * Constructs an {@code AgentPromptLoader} with the given config loader and known tool names.
     *
     * @param loader         the config loader to use for S3 reads
     * @param knownToolNames all tool names registered in the tool registry; used to validate
     *                       extraTools and disallowTools in each prompt
     */
    public AgentPromptLoader(MailKickConfigLoader loader, Set<String> knownToolNames) {
        this.loader = loader;
        this.knownToolNames = knownToolNames;
    }

    /**
     * Loads the config immediately (synchronous). Should be called once before {@link #start()}.
     *
     * @throws IOException if the load or validation fails
     */
    public void loadNow() throws IOException {
        MailKickConfig loaded = loader.load();
        MailKickConfigValidator.validate(loaded, knownToolNames);
        config.set(loaded);
        lastSuccessfulLoad.set(Instant.now());
        lastError.set(null);
        LOG.info("MailKickConfig loaded: model={}, prompts={}",
            loaded.getModel(), loaded.getPrompts().keySet());
    }

    /**
     * Starts the background config-reload daemon thread.
     * Has no effect if already running.
     */
    public void start() {
        running.set(true);
        Thread thread = new Thread(this::run, "mailkick-config-reloader");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Signals the background reload loop to stop.
     * The loop will exit after its current sleep or reload attempt completes.
     */
    public void stop() {
        running.set(false);
    }

    /**
     * Background reload loop. Sleeps for {@value #RELOAD_INTERVAL_MS} ms between reloads.
     * Intended to be run on the background daemon thread started by {@link #start()}.
     */
    public void run() {
        while (running.get()) {
            try {
                Thread.sleep(RELOAD_INTERVAL_MS);
                if (!running.get()) {
                    break;
                }
                MailKickConfig loaded = loader.load();
                MailKickConfigValidator.validate(loaded, knownToolNames);
                config.set(loaded);
                lastSuccessfulLoad.set(Instant.now());
                lastError.set(null);
                LOG.info("MailKickConfig reloaded: model={}", loaded.getModel());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                lastError.set(e.getMessage());
                LOG.warn("Failed to reload MailKickConfig: {}", e.getMessage());
            }
        }
    }

    /**
     * Returns the currently loaded config.
     *
     * @return the current {@link MailKickConfig}
     * @throws IllegalStateException if {@link #loadNow()} has not been called yet
     */
    public MailKickConfig getConfig() {
        MailKickConfig current = config.get();
        if (current == null) {
            throw new IllegalStateException("MailKickConfig not loaded yet");
        }
        return current;
    }

    /**
     * Returns the instant of the last successful config load, or {@code null} if never loaded.
     *
     * @return last successful load time, or {@code null}
     */
    public Instant getLastSuccessfulLoad() {
        return lastSuccessfulLoad.get();
    }

    /**
     * Returns the error message from the last failed reload attempt, or {@code null} if healthy.
     *
     * @return last error message, or {@code null}
     */
    public String getLastError() {
        return lastError.get();
    }

    /**
     * Returns {@code true} if the last load succeeded and no error is recorded.
     *
     * @return {@code true} when healthy
     */
    public boolean isHealthy() {
        return lastError.get() == null && lastSuccessfulLoad.get() != null;
    }
}
