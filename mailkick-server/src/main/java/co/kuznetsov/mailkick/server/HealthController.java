package co.kuznetsov.mailkick.server;

import co.kuznetsov.mailkick.agent.AgentPromptLoader;
import co.kuznetsov.mailkick.model.HealthComponent;
import co.kuznetsov.mailkick.model.HealthStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoint for application health monitoring. Intended to be polled by FaceKick.
 * Returns {@code 200 OK} when all components are healthy, {@code 500} otherwise.
 */
@RestController
public class HealthController {

    private static final Logger LOG = LoggerFactory.getLogger(
        HealthController.class
    );

    private final HealthTracker healthTracker;
    private final AgentPromptLoader promptLoader;

    /**
     * Constructs a {@code HealthController} with the required dependencies.
     *
     * @param healthTracker the tracker that maintains per-component health state
     * @param promptLoader  the agent prompt loader whose S3 health is surfaced here
     */
    public HealthController(
        HealthTracker healthTracker,
        AgentPromptLoader promptLoader
    ) {
        this.healthTracker = healthTracker;
        this.promptLoader = promptLoader;
    }

    /**
     * Returns the application health status.
     *
     * @return 200 with {@code {"status":"UP"}} or 500 with component failure details
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        // Sync S3 health from AgentPromptLoader
        if (promptLoader.isHealthy()) {
            healthTracker.recordSuccess(HealthComponent.S3);
        } else {
            String err =
                promptLoader.getLastError() != null
                    ? promptLoader.getLastError()
                    : "S3 config load failed";
            healthTracker.recordFailure(HealthComponent.S3, err);
        }

        List<HealthStatus> unhealthy = healthTracker.getUnhealthyComponents();
        if (unhealthy.isEmpty()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "UP");
            return ResponseEntity.ok(body);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "DOWN");
        List<Map<String, String>> components = new ArrayList<>();
        for (HealthStatus s : unhealthy) {
            Map<String, String> comp = new LinkedHashMap<>();
            comp.put("component", s.getComponent().name());
            comp.put(
                "message",
                s.getFailureMessage() != null ? s.getFailureMessage() : ""
            );
            comp.put(
                "since",
                s.getFailureStartTime() != null
                    ? s.getFailureStartTime().toString()
                    : ""
            );
            components.add(comp);
        }
        body.put("components", components);

        LOG.warn(
            "/health returning DOWN: {} unhealthy component(s)",
            unhealthy.size()
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
            body
        );
    }
}
