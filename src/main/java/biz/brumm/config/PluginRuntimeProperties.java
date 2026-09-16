package biz.brumm.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Konfiguration der Plugin-Laufzeit (P4-01, Node-Sidecar).
 *
 * @param enabled           Schaltet die Laufzeit frei (Deny-by-Default; nur {@code true} startet den Sidecar).
 * @param callTimeoutMillis Call-Timeout für Sidecar-Aufrufe (Standard: 15 000 ms).
 */
@ConfigurationProperties(prefix = "jclaw.agent.plugins.runtime")
public record PluginRuntimeProperties(boolean enabled, long callTimeoutMillis) {

    public static final long DEFAULT_CALL_TIMEOUT_MILLIS = 15_000;

    public PluginRuntimeProperties {
        if (callTimeoutMillis <= 0) {
            callTimeoutMillis = DEFAULT_CALL_TIMEOUT_MILLIS;
        }
    }
}