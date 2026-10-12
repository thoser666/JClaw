package biz.brumm.infrastructure.adapter.out.ai.tool;

import biz.brumm.domain.model.SessionGoal;
import biz.brumm.domain.port.out.AgentTool;
import biz.brumm.domain.service.GoalOperationException;
import biz.brumm.domain.service.GoalService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Spiegel der OpenClaw-Model-Tools {@code get_goal}/{@code create_goal}/{@code update_goal}
 * (P4-07 Goals). Der Agent liest das Session-Ziel, legt es auf ausdrücklichen Wunsch an und
 * markiert es als {@code complete} oder {@code blocked}. Pause/Resume/Clear bleiben
 * Operator-/Session-Controls ({@link GoalService}, REST-Spiegel) — wie in OpenClaw.
 */
@Component
public class GoalModelTool implements AgentTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final GoalService goalService;

    public GoalModelTool(GoalService goalService) {
        this.goalService = goalService;
    }

    @Tool(name = "get_goal",
            description = "Liest das aktuelle Session-Ziel: Status, Objective, Token-Budget und Notizen. "
                    + "Wenn kein Ziel existiert, ist goal: null. Dieses Werkzeug verändert nichts.")
    public String getGoal(
            @ToolParam(description = "Session-ID, deren Ziel gelesen werden soll.") String sessionId) {
        String validated = requireSessionId(sessionId);
        if (validated != null) {
            return validated;
        }
        Optional<SessionGoal> goal = goalService.getGoal(sessionId);
        if (goal.isEmpty()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("sessionId", sessionId);
            empty.put("goal", null);
            return json(empty);
        }
        return json(toSnapshot(goal.get()));
    }

    @Tool(name = "create_goal",
            description = "Erstellt ein Session-Ziel. Nur auf ausdrücklichen Nutzer- oder "
                    + "System-Wunsch aufrufen, niemals stillschweigend. Fehler, wenn bereits ein "
                    + "Ziel für die Session existiert. Optionales positives Token-Budget (token_budget).")
    public String createGoal(
            @ToolParam(description = "Session-ID, für die das Ziel angelegt wird.") String sessionId,
            @ToolParam(description = "Das Ziel in Worten.") String objective,
            @ToolParam(description = "Optional: positives Token-Budget für das Ziel.", required = false) Long token_budget) {
        String validated = requireSessionId(sessionId);
        if (validated != null) {
            return validated;
        }
        try {
            SessionGoal goal = goalService.startGoal(sessionId, objective, token_budget);
            return json(Map.of("status", "created", "goal", toSnapshot(goal)));
        } catch (GoalOperationException | IllegalArgumentException e) {
            return error(e.getMessage());
        }
    }

    @Tool(name = "update_goal",
            description = "Aktualisiert den Status des Session-Ziels: complete | blocked, mit "
                    + "optionaler Notiz. complete nur, wenn das Objective nachweislich erreicht ist; "
                    + "blocked nur, wenn derselbe Blocker über Züge wiederkehrt — nie bei "
                    + "gewöhnlicher Schwierigkeit oder Feinschliff. Pause/Resume/Clear machen nur "
                    + "Operator-/Session-Controls.")
    public String updateGoal(
            @ToolParam(description = "Session-ID, deren Ziel aktualisiert wird.") String sessionId,
            @ToolParam(description = "Neuer Zielstatus: complete | blocked.") String status,
            @ToolParam(description = "Optionale kurze Statusnotiz.", required = false) String note) {
        String validated = requireSessionId(sessionId);
        if (validated != null) {
            return validated;
        }
        try {
            SessionGoal goal = switch (normalizeStatus(status)) {
                case "complete" -> goalService.completeGoal(sessionId, note);
                case "blocked" -> goalService.blockGoal(sessionId, note);
                default -> throw new GoalOperationException(
                        "status muss 'complete' oder 'blocked' sein.", false);
            };
            return json(Map.of("status", "updated", "goal", toSnapshot(goal)));
        } catch (GoalOperationException | IllegalArgumentException e) {
            return error(e.getMessage());
        }
    }

    private static String requireSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return error("Session-ID darf nicht leer sein.");
        }
        return null;
    }

    private static String normalizeStatus(String status) {
        return status == null ? "" : status.trim().toLowerCase(Locale.ROOT);
    }

    private static Map<String, Object> toSnapshot(SessionGoal goal) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("sessionId", goal.sessionId());
        map.put("objective", goal.objective());
        map.put("status", goal.status().name().toLowerCase(Locale.ROOT));
        map.put("statusNote", goal.statusNote());
        map.put("tokenBudget", goal.tokenBudget());
        map.put("tokenUsage", null);
        map.put("createdAt", goal.createdAt() != null ? goal.createdAt().toString() : null);
        map.put("completedAt", goal.completedAt() != null ? goal.completedAt().toString() : null);
        return map;
    }

    private static String json(Map<String, Object> value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return "{\"error\":\"JSON-Fehler\"}";
        }
    }

    private static String error(String message) {
        return json(Map.of("error", message == null ? "Unbekannter Fehler" : message));
    }
}