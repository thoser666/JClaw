package biz.brumm.domain.model;

import java.time.Instant;

/**
 * Ein im Hintergrund ausgeführter Agent-Task einer Session (OpenClaw-kompatibles Background-Session-Konzept, Punkt P4-08).
 *
 * <p>Der Start gibt sofort zurück (Status {@code PENDING}); die Ausführung läuft asynchron.
 * Terminal-Stati sind {@code COMPLETED} und {@code FAILED} — über den REST/SSE-Endpoint
 * wird der Abschluss (Completion-Benachrichtigung) gemeldet.</p>
 *
 * @param id          Eindeutige Task-ID
 * @param sessionId   Ziel-Session
 * @param prompt      Der Prompt, der an den Agenten gesendet wird
 * @param status      Aktueller Status
 * @param result      Ergebnis-Text (COMPLETED)
 * @param error       Fehlermeldung (FAILED)
 * @param createdAt   Erstellungszeitpunkt
 * @param startedAt   Zeitpunkt der Ausführungs-Übernahme (RUNNING)
 * @param completedAt Zeitpunkt des Abschlusses (terminal)
 */
public record BackgroundTask(String id, String sessionId, String prompt, BackgroundTaskStatus status,
                             String result, String error, Instant createdAt, Instant startedAt, Instant completedAt) {

    public BackgroundTask {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Task-ID darf nicht leer sein.");
        }
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("Session-ID darf nicht leer sein.");
        }
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("Prompt darf nicht leer sein.");
        }
        if (status == null) {
            throw new IllegalArgumentException("Status darf nicht null sein.");
        }
    }

    public BackgroundTask withStarted(Instant started) {
        return new BackgroundTask(id, sessionId, prompt, BackgroundTaskStatus.RUNNING, null, null, createdAt, started, null);
    }

    public BackgroundTask withCompleted(String completionResult, Instant completed, Instant started) {
        return new BackgroundTask(id, sessionId, prompt, BackgroundTaskStatus.COMPLETED,
                completionResult, null, createdAt, started, completed);
    }

    public BackgroundTask withFailed(String errorMessage, Instant completed, Instant started) {
        return new BackgroundTask(id, sessionId, prompt, BackgroundTaskStatus.FAILED,
                null, errorMessage, createdAt, started, completed);
    }
}