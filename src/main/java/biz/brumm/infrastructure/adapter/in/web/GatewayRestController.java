package biz.brumm.infrastructure.adapter.in.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * REST-API für Gateway-Status und System-Informationen.
 * Bietet {@code GET /api/v1/gateway/status} und {@code GET /api/v1/gateway/info}.
 */
@RestController
@RequestMapping("/api/v1/gateway")
public class GatewayRestController {

    private final Instant startedAt = Instant.now();

    @Value("${spring.application.name:jclaw}")
    private String applicationName;

    @Value("${server.port:8080}")
    private int serverPort;

    @Value("${jclaw.agent.max-iterations:8}")
    private int maxIterations;

    @Value("${jclaw.session.reset-mode:none}")
    private String sessionResetMode;

    /**
     * Gibt den aktuellen Gateway-Status zurück.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        return ResponseEntity.ok(Map.of(
                "status", "running",
                "startedAt", startedAt.toString(),
                "uptime", Instant.now().getEpochSecond() - startedAt.getEpochSecond()
        ));
    }

    /**
     * Gibt System-Informationen über den Gateway zurück.
     */
    @GetMapping("/info")
    public ResponseEntity<Map<String, Object>> getInfo() {
        return ResponseEntity.ok(Map.of(
                "name", applicationName,
                "version", getVersion(),
                "serverPort", serverPort,
                "config", Map.of(
                        "maxIterations", maxIterations,
                        "sessionResetMode", sessionResetMode
                )
        ));
    }

    private String getVersion() {
        return getClass().getPackage().getImplementationVersion() != null
                ? getClass().getPackage().getImplementationVersion()
                : "dev";
    }
}
