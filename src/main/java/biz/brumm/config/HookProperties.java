package biz.brumm.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Konfiguration für Lifecycle-Hooks (HOOK.md-Scripts).
 *
 * @param dir             Verzeichnis, in dem HOOK.md-Dateien gesucht werden
 * @param enabled         Globale Aktivierung (Deny-by-Default)
 * @param script-timeout  Timeout für Script-Ausführungen in Sekunden
 * @param allowed-stages  Liste der erlaubten Stages ( leer = alle )
 */
@ConfigurationProperties(prefix = "jclaw.hooks")
public record HookProperties(String dir, boolean enabled, int scriptTimeout, List<String> allowedStages) {

    public HookProperties {
        dir = (dir == null || dir.isBlank()) ? "./hooks" : dir;
        if (scriptTimeout <= 0) {
            scriptTimeout = 30;
        }
        allowedStages = (allowedStages == null) ? List.of() : List.copyOf(allowedStages);
    }
}
