package biz.brumm.infrastructure.adapter.in.web;

import biz.brumm.domain.model.ConversationMessage;
import biz.brumm.domain.model.Session;
import biz.brumm.domain.port.out.ConversationStore;
import biz.brumm.domain.service.SessionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/sessions")
public class SessionRestController {

    private final SessionService sessionService;
    private final ConversationStore conversationStore;

    public SessionRestController(SessionService sessionService, ConversationStore conversationStore) {
        this.sessionService = sessionService;
        this.conversationStore = conversationStore;
    }

    @GetMapping
    public ResponseEntity<List<Session>> listSessions(
            @RequestParam(required = false) String group) {
        if (group != null && !group.isBlank()) {
            return ResponseEntity.ok(sessionService.listSessionsByGroup(group));
        }
        return ResponseEntity.ok(sessionService.listSessions());
    }

    @GetMapping("/{sessionId}")
    public ResponseEntity<Session> getSession(@PathVariable String sessionId) {
        return sessionService.findSession(sessionId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> deleteSession(@PathVariable String sessionId) {
        sessionService.deleteSession(sessionId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{sessionId}/group")
    public ResponseEntity<Session> updateSessionGroup(
            @PathVariable String sessionId,
            @RequestBody Map<String, String> body) {
        String group = body.get("group");
        try {
            Session updated = sessionService.updateSessionGroup(sessionId, group);
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/{sessionId}/transcript")
    public ResponseEntity<List<Map<String, String>>> getTranscript(@PathVariable String sessionId) {
        if (sessionService.findSession(sessionId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        List<ConversationMessage> messages = conversationStore.findByContextId(sessionId);
        List<Map<String, String>> transcript = messages.stream()
                .map(m -> Map.of("role", m.role(), "text", m.text()))
                .toList();
        return ResponseEntity.ok(transcript);
    }
}
