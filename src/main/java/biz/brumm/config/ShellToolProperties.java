package biz.brumm.config;

import biz.brumm.infrastructure.adapter.out.ai.tool.ShellTool;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Konfiguration für das Shell-Werkzeug des Agenten. Erst wenn {@code enabled=true} gesetzt ist,
 * wird das {@code runCommand}-Werkzeug registriert (Deny-by-Default).
 *
 * @param enabled          Schaltet das Shell-Werkzeug frei.
 * @param workdir          Arbeitsverzeichnis, in dem Befehle ausgeführt werden (Standard: aktuelles Verzeichnis).
 * @param timeoutSeconds   Maximale Laufzeit eines Befehls in Sekunden (Standard: 30).
 * @param maxOutputChars   Maximale Zeichenanzahl der zurückgegebenen Ausgabe (Standard: 10.000).
 */
@ConfigurationProperties(prefix = "jclaw.agent.shelltool")
public record ShellToolProperties(boolean enabled, String workdir, Integer timeoutSeconds, Integer maxOutputChars) {

    public ShellToolProperties {
        if (timeoutSeconds != null && timeoutSeconds <= 0) {
            throw new IllegalArgumentException("jclaw.agent.shelltool.timeout-seconds muss positiv sein.");
        }
        if (maxOutputChars != null && maxOutputChars <= 0) {
            throw new IllegalArgumentException("jclaw.agent.shelltool.max-output-chars muss positiv sein.");
        }
    }

    public String effectiveWorkdir() {
        return (workdir == null || workdir.isBlank()) ? "." : workdir;
    }

    public Duration effectiveTimeout() {
        return Duration.ofSeconds(timeoutSeconds == null ? ShellTool.DEFAULT_TIMEOUT_SECONDS : timeoutSeconds);
    }

    public int effectiveMaxOutputChars() {
        return maxOutputChars == null ? ShellTool.DEFAULT_MAX_OUTPUT_CHARS : maxOutputChars;
    }
}
