package biz.brumm.domain.model;

import java.nio.file.Path;

/**
 * Ein Lifecycle-Hook im OpenClaw-HOOK.md-Format.
 * Hooks werden sequenziell in absteigender Priorität ausgeführt.
 *
 * @param name        Eindeutiger Hook-Name (z. B. "before-agent-run")
 * @param stage       Lifecycle-Stage (z. B. "before_agent_run")
 * @param priority    Ausführungspriorität (höher = früher)
 * @param scriptPath  Pfad zum ausführbaren Script
 * @param description Optionale Beschreibung
 */
public record Hook(String name, String stage, int priority, Path scriptPath, String description) {

    public Hook {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Hook-Name darf nicht leer sein.");
        }
        if (stage == null || stage.isBlank()) {
            throw new IllegalArgumentException("Hook-Stage darf nicht leer sein.");
        }
        if (scriptPath == null) {
            throw new IllegalArgumentException("Hook-ScriptPath darf nicht null sein.");
        }
    }
}
