package biz.brumm.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Konfiguration der Plugin-Laufzeit (P4-01, Node-Sidecar).
 *
 * @param enabled           Schaltet die Laufzeit frei (Deny-by-Default; nur {@code true} startet den Sidecar).
 * @param callTimeoutMillis Call-Timeout für Sidecar-Aufrufe (Standard: 15 000 ms).
 * @param trustedSources    Install-Quellen, die ohne weitere Freigabe vertraut werden
 *                          (Plugin-Install-Provenance, P4-09; Standard: {@code bundled}, {@code catalog}).
 * @param allow             Pinned Plugin-Ids (Spiegel zu OpenClaw {@code plugins.allow}/{@code --force}):
 *                          explizite Operator-Freigabe, die das Laden auch aus beliebigen Quellen erlaubt.
 */
@ConfigurationProperties(prefix = "jclaw.agent.plugins.runtime")
public record PluginRuntimeProperties(boolean enabled, long callTimeoutMillis,
                                      List<String> trustedSources, List<String> allow) {

    public static final long DEFAULT_CALL_TIMEOUT_MILLIS = 15_000;

    /** Vertrauenswürdige Install-Quellen per Default (OpenClaw: bundled + offizieller Katalog). */
    public static final List<String> DEFAULT_TRUSTED_SOURCES = List.of("bundled", "catalog");

    public PluginRuntimeProperties {
        if (callTimeoutMillis <= 0) {
            callTimeoutMillis = DEFAULT_CALL_TIMEOUT_MILLIS;
        }
        if (trustedSources == null) {
            trustedSources = DEFAULT_TRUSTED_SOURCES;
        }
        if (allow == null) {
            allow = List.of();
        }
    }
}