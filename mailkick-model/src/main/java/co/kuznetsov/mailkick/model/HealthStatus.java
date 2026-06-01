package co.kuznetsov.mailkick.model;

import java.time.Instant;

/**
 * Immutable value class representing the health state of a single system component.
 *
 * <p>Use the static factory methods {@link #healthy(HealthComponent)} and
 * {@link #failing(HealthComponent, String, Instant)} to construct instances.</p>
 */
public final class HealthStatus {

    private final HealthComponent component;
    private final boolean healthy;
    private final String failureMessage;
    private final Instant failureStartTime;

    private HealthStatus(HealthComponent component, boolean healthy,
            String failureMessage, Instant failureStartTime) {
        this.component = component;
        this.healthy = healthy;
        this.failureMessage = failureMessage;
        this.failureStartTime = failureStartTime;
    }

    /**
     * Creates a healthy status for the given component.
     *
     * @param component the component that is healthy
     * @return a new {@link HealthStatus} with {@code healthy=true} and {@code null} failure fields
     */
    public static HealthStatus healthy(HealthComponent component) {
        return new HealthStatus(component, true, null, null);
    }

    /**
     * Creates a failing status for the given component.
     *
     * @param component        the component that is failing
     * @param failureMessage   human-readable description of the failure
     * @param failureStartTime the instant at which the failure was first detected
     * @return a new {@link HealthStatus} with {@code healthy=false}
     */
    public static HealthStatus failing(HealthComponent component,
            String failureMessage, Instant failureStartTime) {
        return new HealthStatus(component, false, failureMessage, failureStartTime);
    }

    /**
     * Returns the system component this status describes.
     *
     * @return component
     */
    public HealthComponent getComponent() {
        return component;
    }

    /**
     * Returns {@code true} if the component is currently healthy.
     *
     * @return {@code true} when healthy
     */
    public boolean isHealthy() {
        return healthy;
    }

    /**
     * Returns the failure message, or {@code null} when the component is healthy.
     *
     * @return failure message, or {@code null}
     */
    public String getFailureMessage() {
        return failureMessage;
    }

    /**
     * Returns the instant at which the failure was first detected,
     * or {@code null} when the component is healthy.
     *
     * @return failure start time, or {@code null}
     */
    public Instant getFailureStartTime() {
        return failureStartTime;
    }
}
