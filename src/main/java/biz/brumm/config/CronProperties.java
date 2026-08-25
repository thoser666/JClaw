package biz.brumm.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Konfiguration für Cron-Jobs (P1-12).
 *
 * @param enabled     Cron-System aktivieren (Deny-by-Default)
 * @param interval    Intervall in Sekunden, in dem auf fällige Jobs geprüft wird
 * @param maxRetries  Maximale Wiederholungen bei Fehler
 */
@ConfigurationProperties(prefix = "jclaw.cron")
public record CronProperties(boolean enabled, int interval, int maxRetries) {

    public static CronProperties of(boolean enabled, int interval, int maxRetries) {
        return new CronProperties(enabled, interval <= 0 ? 60 : interval, Math.max(maxRetries, 0));
    }
}
