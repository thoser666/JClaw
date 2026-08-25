package biz.brumm.domain.model;

import java.time.Instant;

/**
 * Ein wiederkehrender Agent-Job (OpenClaw-kompatibles Cron-Konzept).
 *
 * @param id             Eindeutige Job-ID
 * @param name           Anzeigename
 * @param cronExpression Cron-Ausdruck (5 Felder: Minute Stunde Tag Monat Wochentag)
 * @param prompt         Der Prompt, der beim Ausführen an den Agenten gesendet wird
 * @param contextId      Optionale Context-ID für Konversations-Tracking
 * @param enabled        Ob der Job aktiv ist
 * @param lastRunAt      Zeitpunkt der letzten Ausführung
 * @param nextRunAt      Zeitpunkt der nächsten geplanten Ausführung
 * @param createdAt      Erstellungszeitpunkt
 */
public record CronJob(String id, String name, String cronExpression, String prompt,
                       String contextId, boolean enabled,
                       Instant lastRunAt, Instant nextRunAt, Instant createdAt) {

    public CronJob {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("CronJob-ID darf nicht leer sein.");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("CronJob-Name darf nicht leer sein.");
        }
        if (cronExpression == null || cronExpression.isBlank()) {
            throw new IllegalArgumentException("CronExpression darf nicht leer sein.");
        }
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("Prompt darf nicht leer sein.");
        }
    }

    public CronJob withNextRun(Instant nextRun) {
        return new CronJob(id, name, cronExpression, prompt, contextId, enabled,
                lastRunAt, nextRun, createdAt);
    }

    public CronJob withLastRun(Instant lastRun, Instant nextRun) {
        return new CronJob(id, name, cronExpression, prompt, contextId, enabled,
                lastRun, nextRun, createdAt);
    }

    public CronJob withEnabled(boolean enabled) {
        return new CronJob(id, name, cronExpression, prompt, contextId, enabled,
                lastRunAt, nextRunAt, createdAt);
    }
}
