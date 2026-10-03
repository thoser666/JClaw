package biz.brumm.domain.model;

import java.time.Instant;

/**
 * Ein dauerhaftes Ziel einer Session (OpenClaw-kompatibles Goal-Konzept).
 *
 * <p>Pro Session existiert maximal ein Ziel. Das Ziel überlebt Gateway-Neustarts
 * und wird über Status-Übergänge gesteuert (start/edit/pause/resume/block/complete/clear).</p>
 *
 * @param sessionId   ID der Session
 * @param objective   Das Ziel in Worten
 * @param status      Aktueller Status
 * @param statusNote  Optionale Notiz zum Statuswechsel
 * @param tokenBudget Optionales Token-Budget
 * @param createdAt   Erstellungszeitpunkt
 * @param completedAt Zeitpunkt der Fertigstellung (terminal, null solange offen)
 */
public record SessionGoal(String sessionId, String objective, GoalStatus status,
                          String statusNote, Long tokenBudget, Instant createdAt, Instant completedAt) {

    public SessionGoal {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("Session-ID darf nicht leer sein.");
        }
        if (objective == null || objective.isBlank()) {
            throw new IllegalArgumentException("Objective darf nicht leer sein.");
        }
        if (status == null) {
            throw new IllegalArgumentException("Status darf nicht null sein.");
        }
    }

    public SessionGoal withObjective(String newObjective) {
        return new SessionGoal(sessionId, newObjective, status, statusNote, tokenBudget, createdAt, completedAt);
    }

    public SessionGoal withNote(String note) {
        return new SessionGoal(sessionId, objective, status, note, tokenBudget, createdAt, completedAt);
    }

    public SessionGoal withStatus(GoalStatus newStatus, String note, Instant now) {
        Instant completed = newStatus == GoalStatus.COMPLETE && completedAt == null ? now : completedAt;
        return new SessionGoal(sessionId, objective, newStatus, note, tokenBudget, createdAt, completed);
    }
}