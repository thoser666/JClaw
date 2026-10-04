package biz.brumm.infrastructure.adapter.in.web;

import biz.brumm.domain.model.BackgroundTask;
import biz.brumm.domain.service.BackgroundTaskService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;

/**
 * REST-Zugriff auf Background-Sessions (Punkt P4-08): Start ohne Blockieren + Completion-Benachrichtigung.
 * Der Start liefert sofort (202 Accepted); der SSE-Endpoint meldet den Abschluss (event: completion).
 */
@RestController
@RequestMapping("/api/v1")
public class BackgroundTaskRestController {

    private static final long EMITTER_TIMEOUT_MS = 60_000L;
    private static final long POLL_INTERVAL_MS = 500L;

    private final BackgroundTaskService backgroundTaskService;
    private final Executor executor;

    public BackgroundTaskRestController(BackgroundTaskService backgroundTaskService,
                                        Executor executor) {
        this.backgroundTaskService = backgroundTaskService;
        this.executor = executor;
    }

    @PostMapping("/sessions/{sessionId}/background-tasks")
    public ResponseEntity<Map<String, Object>> start(@PathVariable String sessionId, @RequestBody Map<String, Object> body) {
        String prompt = requireString(body, "prompt");
        BackgroundTask task = backgroundTaskService.start(sessionId, prompt);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(toMap(task));
    }

    @GetMapping("/sessions/{sessionId}/background-tasks")
    public List<Map<String, Object>> list(@PathVariable String sessionId) {
        return backgroundTaskService.findBySession(sessionId).stream().map(this::toMap).toList();
    }

    @GetMapping("/background-tasks/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String id) {
        return backgroundTaskService.findById(id)
                .map(task -> ResponseEntity.ok(toMap(task)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping(value = "/background-tasks/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable String id) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        Optional<BackgroundTask> current = backgroundTaskService.findById(id);
        if (current.isEmpty()) {
            emitAndComplete(emitter, taskEvent("error", unknownTaskMap(id)));
            return emitter;
        }
        BackgroundTask task = current.get();
        if (task.status().isTerminal()) {
            emitAndComplete(emitter, taskEvent("completion", task));
            return emitter;
        }
        executor.execute(() -> pollUntilTerminal(emitter, id));
        return emitter;
    }

    private void pollUntilTerminal(SseEmitter emitter, String id) {
        try {
            while (true) {
                Optional<BackgroundTask> task = backgroundTaskService.findById(id);
                if (task.isEmpty()) {
                    emitAndComplete(emitter, taskEvent("error", unknownTaskMap(id)));
                    return;
                }
                BackgroundTask current = task.get();
                if (current.status().isTerminal()) {
                    emitAndComplete(emitter, taskEvent("completion", current));
                    return;
                }
                Thread.sleep(POLL_INTERVAL_MS);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            emitter.completeWithError(ex);
        }
    }

    private static SseEmitter.SseEventBuilder taskEvent(String name, Map<String, Object> data) {
        return SseEmitter.event().name(name).data(data);
    }

    private SseEmitter.SseEventBuilder taskEvent(String name, BackgroundTask task) {
        return SseEmitter.event().name(name).data(Map.of("event", name, "task", toMap(task)));
    }

    private static Map<String, Object> unknownTaskMap(String id) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("status", "UNKNOWN");
        return map;
    }

    private static void emitAndComplete(SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
            emitter.complete();
        } catch (IOException | IllegalStateException ex) {
            emitter.completeWithError(ex);
        }
    }

    private static String requireString(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(key + " ist erforderlich.");
        }
        return text;
    }

    private Map<String, Object> toMap(BackgroundTask task) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", task.id());
        map.put("sessionId", task.sessionId());
        map.put("prompt", task.prompt());
        map.put("status", task.status().name());
        map.put("result", task.result());
        map.put("error", task.error());
        map.put("createdAt", task.createdAt() != null ? task.createdAt().toString() : null);
        map.put("startedAt", task.startedAt() != null ? task.startedAt().toString() : null);
        map.put("completedAt", task.completedAt() != null ? task.completedAt().toString() : null);
        return map;
    }
}