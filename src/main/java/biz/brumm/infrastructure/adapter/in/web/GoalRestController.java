package biz.brumm.infrastructure.adapter.in.web;

import biz.brumm.domain.model.SessionGoal;
import biz.brumm.domain.service.GoalService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REST-Spiegel der OpenClaw-Slash-Aktionen {@code /goal start|edit|pause|resume|block|complete|clear}.
 */
@RestController
@RequestMapping("/api/v1/sessions/{sessionId}/goal")
public class GoalRestController {

    private final GoalService goalService;

    public GoalRestController(GoalService goalService) {
        this.goalService = goalService;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> get(@PathVariable String sessionId) {
        return goalService.getGoal(sessionId)
                .map(goal -> ResponseEntity.ok(toMap(goal)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/start")
    public ResponseEntity<Map<String, Object>> start(@PathVariable String sessionId, @RequestBody Map<String, Object> body) {
        String objective = requireString(body, "objective");
        Long tokenBudget = longValue(body.get("tokenBudget"));
        SessionGoal goal = goalService.startGoal(sessionId, objective, tokenBudget);
        return ResponseEntity.ok(toMap(goal));
    }

    @PostMapping("/edit")
    public ResponseEntity<Map<String, Object>> edit(@PathVariable String sessionId, @RequestBody Map<String, Object> body) {
        String objective = requireString(body, "objective");
        return ResponseEntity.ok(toMap(goalService.rewordGoal(sessionId, objective)));
    }

    @PostMapping("/pause")
    public ResponseEntity<Map<String, Object>> pause(@PathVariable String sessionId, @RequestBody(required = false) Map<String, Object> body) {
        return ResponseEntity.ok(toMap(goalService.pauseGoal(sessionId, note(body))));
    }

    @PostMapping("/resume")
    public ResponseEntity<Map<String, Object>> resume(@PathVariable String sessionId, @RequestBody(required = false) Map<String, Object> body) {
        return ResponseEntity.ok(toMap(goalService.resumeGoal(sessionId, note(body))));
    }

    @PostMapping("/block")
    public ResponseEntity<Map<String, Object>> block(@PathVariable String sessionId, @RequestBody(required = false) Map<String, Object> body) {
        return ResponseEntity.ok(toMap(goalService.blockGoal(sessionId, note(body))));
    }

    @PostMapping("/complete")
    public ResponseEntity<Map<String, Object>> complete(@PathVariable String sessionId, @RequestBody(required = false) Map<String, Object> body) {
        return ResponseEntity.ok(toMap(goalService.completeGoal(sessionId, note(body))));
    }

    @DeleteMapping
    public ResponseEntity<Map<String, Object>> clear(@PathVariable String sessionId) {
        goalService.clearGoal(sessionId);
        return ResponseEntity.ok(Map.<String, Object>of("cleared", true, "sessionId", sessionId));
    }

    private static String requireString(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(key + " ist erforderlich.");
        }
        return text;
    }

    private static String note(Map<String, Object> body) {
        if (body == null) {
            return null;
        }
        Object value = body.get("note");
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private static Long longValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("tokenBudget muss eine Zahl sein.");
        }
    }

    private Map<String, Object> toMap(SessionGoal goal) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("sessionId", goal.sessionId());
        map.put("objective", goal.objective());
        map.put("status", goal.status().name());
        map.put("statusNote", goal.statusNote());
        map.put("tokenBudget", goal.tokenBudget());
        map.put("createdAt", goal.createdAt() != null ? goal.createdAt().toString() : null);
        map.put("completedAt", goal.completedAt() != null ? goal.completedAt().toString() : null);
        return map;
    }
}