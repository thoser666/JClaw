package biz.brumm.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Konfiguration für das Channel-System (P3-01).
 *
 * @param enabled        Channel-System aktivieren (Deny-by-Default)
 * @param default-timeout Standard-Timeout in Sekunden für Channel-Operationen
 */
@ConfigurationProperties(prefix = "jclaw.channels")
public record ChannelProperties(boolean enabled, int defaultTimeout) {

    public static ChannelProperties of(boolean enabled, int defaultTimeout) {
        return new ChannelProperties(enabled, defaultTimeout <= 0 ? 30 : defaultTimeout);
    }
}
