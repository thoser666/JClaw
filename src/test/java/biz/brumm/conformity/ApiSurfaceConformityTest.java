package biz.brumm.conformity;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

import static java.util.stream.Collectors.joining;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * P4-05: API-Surface-Konformitätstest (Paritäts-Testsuite).
 * <p>
 * Introspiziert {@link RequestMappingHandlerMapping} und erzwingt einen 1:1-Abgleich mit der
 * deklarierten API-Schnittstelle (Methode + Pfad). Jede Änderung an den Endpoints — neue,
 * entfernte oder umbenannte Routen — bricht den Test und zwingt die Doku (bridge-protocol,
 * README) aktuell zu halten. Details ohne Feature-Gate: alle Controller sind im Standard-
 * Kontext verdrahtet (deny-by-default steuert nur die Funktionalität, nicht die Oberfläche).
 */
@SpringBootTest
class ApiSurfaceConformityTest {

    @Autowired
    private RequestMappingHandlerMapping handlerMapping;

    /** Deklarierte API-Oberfläche: "METHODE /pfad". Muss die aktuelle Implementierung exakt spiegeln. */
    private static final Set<String> DECLARED_API = Set.of(
            // TaskRestController
            "POST /api/v1/tasks",
            // SessionRestController
            "GET /api/v1/sessions",
            "GET /api/v1/sessions/{sessionId}",
            "DELETE /api/v1/sessions/{sessionId}",
            "PUT /api/v1/sessions/{sessionId}/group",
            "GET /api/v1/sessions/{sessionId}/transcript",
            // ConversationRestController
            "GET /api/v1/conversations/{contextId}",
            "DELETE /api/v1/conversations/{contextId}",
            // GoalRestController
            "GET /api/v1/sessions/{sessionId}/goal",
            "POST /api/v1/sessions/{sessionId}/goal/start",
            "POST /api/v1/sessions/{sessionId}/goal/edit",
            "POST /api/v1/sessions/{sessionId}/goal/pause",
            "POST /api/v1/sessions/{sessionId}/goal/resume",
            "POST /api/v1/sessions/{sessionId}/goal/block",
            "POST /api/v1/sessions/{sessionId}/goal/complete",
            "DELETE /api/v1/sessions/{sessionId}/goal",
            // FollowUpRestController
            "POST /api/v1/sessions/{sessionId}/follow-ups",
            "GET /api/v1/sessions/{sessionId}/follow-ups",
            "POST /api/v1/sessions/{sessionId}/follow-ups/drain",
            "DELETE /api/v1/follow-ups/{id}",
            // BackgroundTaskRestController
            "POST /api/v1/sessions/{sessionId}/background-tasks",
            "GET /api/v1/sessions/{sessionId}/background-tasks",
            "GET /api/v1/background-tasks/{id}",
            "GET /api/v1/background-tasks/{id}/events",
            // CronRestController
            "GET /api/v1/cron-jobs",
            "GET /api/v1/cron-jobs/{id}",
            "POST /api/v1/cron-jobs",
            "PUT /api/v1/cron-jobs/{id}",
            "DELETE /api/v1/cron-jobs/{id}",
            "POST /api/v1/cron-jobs/{id}/execute",
            // ChannelRestController
            "GET /api/v1/channels",
            "GET /api/v1/channels/{id}",
            "POST /api/v1/channels",
            "PUT /api/v1/channels/{id}",
            "DELETE /api/v1/channels/{id}",
            "POST /api/v1/channels/{id}/send",
            "POST /api/v1/channels/inbound",
            "GET /api/v1/channels/{id}/bindings",
            "POST /api/v1/channels/{id}/bindings",
            "DELETE /api/v1/channels/bindings/{bindingId}",
            "GET /api/v1/channels/adapters",
            // MemoryVaultController
            "POST /api/v1/memory/{contextId}/sync",
            "GET /api/v1/memory",
            // AuthRestController
            "GET /api/v1/auth/tokens",
            "POST /api/v1/auth/tokens",
            "DELETE /api/v1/auth/tokens/{id}",
            // ConfigRestController
            "POST /api/v1/config.apply",
            // GatewayRestController
            "GET /api/v1/gateway/status",
            "GET /api/v1/gateway/info",
            // PluginRestController
            "GET /api/v1/plugins",
            // SkillRestController
            "GET /api/v1/skills"
    );

    /** Nur Framework-Mappings (Error-Handler), kein Teil der deklarierten API. */
    private static final Set<String> IGNORED_PATTERNS = Set.of("/error");

    @Test
    void everyExposedEndpointIsDeclared() {
        Set<String> actual = collectMappingKeys();
        assertThat(actual)
                .describedAs("Undokumentierte Endpoints gefunden (Referenz-Drift): API-Contract und "
                        + "Bridge-Protocol-Doku müssen aktualisiert werden oder der Endpoint gekappt werden.")
                .allSatisfy(key -> assertThat(DECLARED_API).describedAs("undokumentiert: %s", key).contains(key));
    }

    @Test
    void everyDeclaredEndpointIsExposed() {
        Set<String> actual = collectMappingKeys();
        assertThat(DECLARED_API)
                .describedAs("Deklarierte Endpoints fehlen in der Implementierung (dangling Doku).")
                .allSatisfy(key -> assertThat(actual).describedAs("fehlend: %s", key).contains(key));
    }

    @Test
    void exposedSurfaceMatchesDeclaredSurfaceExactly() {
        assertThat(collectMappingKeys())
                .describedAs("API-Oberfläche ≠ deklarierter API-Contract. Expected = Contract, Actual = Runtime.")
                .isEqualTo(new TreeSet<>(DECLARED_API));
    }

    private Set<String> collectMappingKeys() {
        Set<String> keys = new TreeSet<>();
        handlerMapping.getHandlerMethods().forEach((info, handlerMethod) -> {
            Set<String> patterns = new HashSet<>();
            if (info.getPathPatternsCondition() != null) {
                info.getPathPatternsCondition().getPatterns()
                        .forEach(p -> patterns.add(p.getPatternString()));
            } else if (info.getPatternsCondition() != null) {
                patterns.addAll(info.getPatternsCondition().getPatterns());
            }
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            String method = methods.isEmpty() ? "ANY" : methods.stream()
                    .map(Enum::name).sorted().collect(joining(","));
            for (String pattern : patterns) {
                if (IGNORED_PATTERNS.contains(pattern)) continue;
                keys.add(method + " " + pattern);
            }
        });
        return keys;
    }
}