package co.kuznetsov.mailkick.server;

import co.kuznetsov.mailkick.model.HealthComponent;
import co.kuznetsov.mailkick.model.HealthStatus;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Thread-safe per-component health tracker.
 *
 * <p>Any failing component immediately makes the overall health bad.
 * Grace periods, if needed, should be applied externally (e.g. at the monitoring layer).
 */
@Component
public class HealthTracker {

    private static final Logger LOG = LoggerFactory.getLogger(HealthTracker.class);

    private final ConcurrentHashMap<HealthComponent, HealthStatus> statuses =
        new ConcurrentHashMap<>();

    /**
     * Records a successful health check for the given component.
     * Clears any previously recorded failure, resetting the component to healthy.
     *
     * @param component the component that is now healthy
     */
    public void recordSuccess(HealthComponent component) {
        statuses.put(component, HealthStatus.healthy(component));
        LOG.debug("Health OK: {}", component);
    }

    /**
     * Records a failure for the given component.
     *
     * <p>If the component is already in a failing state, the original failure start time
     * is preserved (useful for diagnostics).
     *
     * @param component the component that has failed
     * @param message   a human-readable description of the failure
     */
    public void recordFailure(HealthComponent component, String message) {
        statuses.compute(component, (k, existing) -> {
            if (existing == null || existing.isHealthy()) {
                LOG.warn("Health FAIL: {} — {}", component, message);
                return HealthStatus.failing(component, message, Instant.now());
            }
            return existing; // keep original start time
        });
    }

    /**
     * Returns the list of components that are currently failing.
     *
     * @return a list of {@link HealthStatus} entries for failing components; empty if all healthy
     */
    public List<HealthStatus> getUnhealthyComponents() {
        return statuses.values().stream()
            .filter(s -> !s.isHealthy())
            .collect(Collectors.toList());
    }

    /**
     * Returns {@code true} if no component is currently failing.
     *
     * @return {@code true} when overall health is good, {@code false} otherwise
     */
    public boolean isOverallHealthy() {
        return getUnhealthyComponents().isEmpty();
    }
}
