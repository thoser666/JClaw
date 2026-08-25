package biz.brumm.domain.service;

import biz.brumm.config.CronProperties;
import biz.brumm.domain.model.CronJob;
import biz.brumm.domain.port.out.CronJobStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Scheduler für Cron-Jobs. Prüft in konfiguriertem Intervall auf fällige Jobs
 * und führt deren Prompt über den AiProviderPort aus.
 */
public class CronSchedulerService {

    private static final Logger log = LoggerFactory.getLogger(CronSchedulerService.class);

    private final CronJobStore cronJobStore;
    private final CronProperties cronProperties;
    private final List<CronJobListener> listeners = new ArrayList<>();

    /**
     * Listener für Cron-Job-Ausführungen (z.B. für Agent-Integration).
     */
    @FunctionalInterface
    public interface CronJobListener {
        void onExecute(String prompt, String contextId);
    }

    public CronSchedulerService(CronJobStore cronJobStore, CronProperties cronProperties) {
        this.cronJobStore = cronJobStore;
        this.cronProperties = cronProperties;
    }

    public void addListener(CronJobListener listener) {
        listeners.add(listener);
    }

    /**
     * Haupt-Scheduler-Methode. Muss periodisch aufgerufen werden (z.B. via ScheduledExecutorService).
     */
    public void checkAndExecute() {
        if (!cronProperties.enabled()) {
            return;
        }

        Instant now = Instant.now();
        List<CronJob> enabledJobs = cronJobStore.findEnabled();

        for (CronJob job : enabledJobs) {
            if (job.nextRunAt() != null && job.nextRunAt().isBefore(now)) {
                executeJob(job);
            }
        }
    }

    /**
     * Führt einen einzelnen Job aus (manuell oder durch Scheduler).
     */
    public void executeJob(CronJob job) {
        log.info("CronJob '{}' wird ausgeführt (Prompt: {}).", job.name(),
                job.prompt().length() > 50 ? job.prompt().substring(0, 50) + "..." : job.prompt());

        Instant now = Instant.now();
        try {
            // Nächsten Lauf berechnen
            CronExpression expr = CronExpression.parse(job.cronExpression());
            Instant nextRun = expr.nextExecutionAfter(now);

            // Listeners benachrichtigen
            for (CronJobListener listener : listeners) {
                try {
                    listener.onExecute(job.prompt(), job.contextId());
                } catch (Exception e) {
                    log.error("Fehler beim Ausführen von CronJob '{}' (Listener): {}", job.name(), e.getMessage());
                }
            }

            // Job aktualisieren
            CronJob updated = job.withLastRun(now, nextRun);
            cronJobStore.save(updated);
            log.info("CronJob '{}' erfolgreich ausgeführt. Nächster Lauf: {}", job.name(), nextRun);
        } catch (Exception e) {
            log.error("Fehler beim Ausführen von CronJob '{}': {}", job.name(), e.getMessage());
        }
    }

    /**
     * Erstellt einen neuen Cron-Job mit berechneter nextRunAt-Zeit.
     */
    public CronJob createJob(String name, String cronExpression, String prompt, String contextId) {
        CronExpression expr = CronExpression.parse(cronExpression);
        Instant now = Instant.now();
        Instant nextRun = expr.nextExecutionAfter(now);

        CronJob job = new CronJob(
                UUID.randomUUID().toString(),
                name,
                cronExpression,
                prompt,
                contextId,
                true,
                null,
                nextRun,
                now);

        return cronJobStore.save(job);
    }

    /**
     * Aktualisiert die nextRunAt-Zeit eines Jobs nach einem Update.
     */
    public CronJob recalculateNextRun(CronJob job) {
        Instant now = Instant.now();
        Instant nextRun = CronExpression.parse(job.cronExpression()).nextExecutionAfter(now);
        return job.withNextRun(nextRun);
    }
}
