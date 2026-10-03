package biz.brumm.infrastructure.adapter.in.web;

import biz.brumm.domain.model.FollowUp;
import biz.brumm.domain.service.FollowUpQueueService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST-Zugriff auf die persistente Follow-up-Queue einer Session.
 * Enqueue und Drain sind explizit; es gibt keine Auto-Ausführung beim Start.
 */
@RestController
@RequestMapping("/api/v1")
public class FollowUpRestController {

    private final FollowUpQueueService followUpQueueService;

    public FollowUpRestController(FollowUpQueueService followUpQueueService) {
        this.followUpQueueService = followUpQueueService;
    }

    @PostMapping("/sessions/{sessionId}/follow-ups")
    public ResponseEntity<Map<String, Object>> enqueue(@PathVariable String sessionId, @RequestBody Map<String, Object> body) {
        String prompt = requireString(body, "prompt");
        return ResponseEntity.ok(toMap(followUpQueueService.enqueue(sessionId, prompt)));
    }

    @GetMapping("/sessions/{sessionId}/follow-ups")
    public List<Map<String, Object>> list(@PathVariable String sessionId) {
        return followUpQueueService.pending(sessionId).stream().map(this::toMap).toList();
    }

    @PostMapping("/sessions/{sessionId}/follow-ups/drain")
    public List<Map<String, Object>> drain(@PathVariable String sessionId) {
        return followUpQueueService.drain(sessionId).stream().map(this::toMap).toList();
    }

    @DeleteMapping("/follow-ups/{id}")
    public ResponseEntity<Map<String, Object>> cancel(@PathVariable String id) {
        return followUpQueueService.findById(id).map(followUp -> {
            followUpQueueService.cancel(id);
            return ResponseEntity.ok(Map.<String, Object>of("deleted", true, "id", id));
        }).orElse(ResponseEntity.notFound().build());
    }

    private static String requireString(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(key + " ist erforderlich.");
        }
        return text;
    }

    private Map<String, Object> toMap(FollowUp followUp) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", followUp.id());
        map.put("sessionId", followUp.sessionId());
        map.put("prompt", followUp.prompt());
        map.put("createdAt", followUp.createdAt() != null ? followUp.createdAt().toString() : null);
        map.put("deliveredAt", followUp.deliveredAt() != null ? followUp.deliveredAt().toString() : null);
        return map;
    }
}