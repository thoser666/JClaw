package biz.brumm.domain.service;

import biz.brumm.domain.model.AgentCommand;
import biz.brumm.domain.model.BackgroundTask;
import biz.brumm.domain.model.BackgroundTaskStatus;
import biz.brumm.domain.port.in.ExecuteTaskUseCase;
import biz.brumm.domain.port.out.BackgroundTaskStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * Startet Agent-Tasks einer Session asynchron im Hintergrund (OpenClaw-Kompatibilität, Punkt P4-08).
 *
 * <p>Der Start persistiert den Task als {@code PENDING} und gibt sofort zurück; die Ausführung
 * läuft über den injizierbaren {@link Executor}. Terminal-Stati sind {@code COMPLETED}/{\code FAILED}
 * und werden persistiert — was die Completion-Benachrichtigung (REST/SSE) über Gateway-Neustarts
 * hinweg möglich macht.</p>
 */
@Service
public class BackgroundTaskService {

    private static final Logger log = LoggerFactory.getLogger(BackgroundTaskService.class);

    private final BackgroundTaskStore store;
    private final ExecuteTaskUseCase executeTaskUseCase;
    private final Executor executor;

    public BackgroundTaskService(BackgroundTaskStore store, ExecuteTaskUseCase executeTaskUseCase, Executor executor) {
        this.store = store;
        this.executeTaskUseCase = executeTaskUseCase;
        this.executor = executor;
    }

    public BackgroundTask start(String sessionId, String prompt) {
        requireNonBlank(sessionId, "Session-ID darf nicht leer sein.");
        requireNonBlank(prompt, "Prompt darf nicht leer sein.");
        BackgroundTask task = new BackgroundTask(UUID.randomUUID().toString(), sessionId, prompt,
                BackgroundTaskStatus.PENDING, null, null, Instant.now(), null, null);
        store.save(task);
        executor.execute(() -> run(task.id()));
        return task;
    }

    /**
     * Führt einen PENDING-Task aus (idempotent: nur PENDING wird übernommen).
     */
    public BackgroundTask run(String id) {
        Optional<BackgroundTask> existing = store.findById(id);
        if (existing.isEmpty()) {
            log.warn("Background-Task '{}' nicht gefunden, Ausführung übersprungen.", id);
            return null;
        }
        BackgroundTask task = existing.get();
        if (task.status() != BackgroundTaskStatus.PENDING) {
            return task;
        }
        Instant startedAt = Instant.now();
        store.save(task.withStarted(startedAt));
        try {
            String result = executeTaskUseCase.handle(new AgentCommand(task.prompt(), task.sessionId())).content();
            return store.save(task.withCompleted(result, Instant.now(), startedAt));
        } catch (RuntimeException ex) {
            log.warn("Background-Task '{}' fehlgeschlagen.", id, ex);
            return store.save(task.withFailed(ex.getMessage(), Instant.now(), startedAt));
        }
    }

    public Optional<BackgroundTask> findById(String id) {
        return store.findById(id);
    }

    public List<BackgroundTask> findBySession(String sessionId) {
        return store.findBySessionId(sessionId);
    }

    private static void requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }
}