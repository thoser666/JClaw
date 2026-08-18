package biz.brumm.config.json5;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validiert eine geladene JSON5-Konfiguration gegen das erwartete JClaw-Schema.
 * Die Validierung arbeitet mit den Kurzschlüsseln der JSON5-Datei (vor dem Mapping auf
 * Spring-Boot-Properties). Für strikte Validierung (P2-02): Bei Fehlern wird eine
 * {@link Json5ConfigValidationException} ausgelöst, die das Starten der Anwendung verhindert.
 */
public class Json5ConfigValidator {

    private static final Set<String> KNOWN_TOP_LEVEL_PREFIXES = Set.of(
            "gateway", "agents", "session", "tools", "skills", "plugins",
            "cron", "hooks", "mcp", "heartbeat", "messages", "models",
            "providers", "env", "$include"
    );

    private static final Set<String> VALID_SESSION_RESET_MODES = Set.of("none", "daily", "idle");

    /**
     * Validiert die flache Properties-Map (Kurzschlüssel) und wirft eine Exception bei Fehlern.
     *
     * @param properties Flache Properties-Map (JSON5-Kurzschlüssel)
     * @throws Json5ConfigValidationException bei Validierungsfehlern
     */
    public static void validate(Map<String, String> properties) {
        List<String> errors = new ArrayList<>();

        validateTopLevelPrefixes(properties, errors);
        validateSessionConfig(properties, errors);
        validateAgentConfig(properties, errors);
        validateMcpConfig(properties, errors);

        if (!errors.isEmpty()) {
            throw new Json5ConfigValidationException(errors);
        }
    }

    private static void validateTopLevelPrefixes(Map<String, String> props, List<String> errors) {
        for (String key : props.keySet()) {
            if (key.startsWith("$")) continue;
            String topLevel = key.contains(".") ? key.substring(0, key.indexOf('.')) : key;
            if (!KNOWN_TOP_LEVEL_PREFIXES.contains(topLevel)) {
                errors.add("Unbekannter Top-Level-Konfigurationsbereich: '" + topLevel + "' "
                        + "(erlaubt: " + KNOWN_TOP_LEVEL_PREFIXES + ")");
            }
        }
    }

    private static void validateSessionConfig(Map<String, String> props, List<String> errors) {
        // JSON5-Kurzschlüssel: session.reset-mode
        String resetMode = props.get("session.reset-mode");
        if (resetMode != null && !resetMode.isEmpty() && !VALID_SESSION_RESET_MODES.contains(resetMode)) {
            errors.add("Ungültiger session.reset-mode: '" + resetMode + "' "
                    + "(erlaubt: " + VALID_SESSION_RESET_MODES + ")");
        }
        validateIntRange(props, "session.reset-at-hour", 0, 23, errors);
        validatePositive(props, "session.reset-idle-minutes", errors);
    }

    private static void validateAgentConfig(Map<String, String> props, List<String> errors) {
        validatePositive(props, "agents.max-iterations", errors);
        validatePositive(props, "agents.max-history-messages", errors);
        validateNonNegative(props, "agents.spawnagent.max-depth", errors);
    }

    private static void validateMcpConfig(Map<String, String> props, List<String> errors) {
        String enabled = props.get("mcp.enabled");
        if (enabled != null && !enabled.isEmpty() && !enabled.equals("true") && !enabled.equals("false")) {
            errors.add("mcp.enabled muss 'true' oder 'false' sein, aber ist: '" + enabled + "'");
        }
        String timeout = props.get("mcp.request-timeout");
        if (timeout != null && !timeout.isEmpty()) {
            try {
                Long.parseLong(timeout.replace("s", "").replace("S", ""));
            } catch (NumberFormatException e) {
                errors.add("mcp.request-timeout muss eine gültige Sekundenzahl sein: '" + timeout + "'");
            }
        }
    }

    private static void validatePositive(Map<String, String> props, String key, List<String> errors) {
        String value = props.get(key);
        if (value == null || value.isEmpty()) return;
        try {
            int intValue = Integer.parseInt(value);
            if (intValue <= 0) {
                errors.add(key + " muss positiv sein, aber ist: " + intValue);
            }
        } catch (NumberFormatException e) {
            errors.add(key + " muss eine gültige Ganzzahl sein: '" + value + "'");
        }
    }

    private static void validateNonNegative(Map<String, String> props, String key, List<String> errors) {
        String value = props.get(key);
        if (value == null || value.isEmpty()) return;
        try {
            int intValue = Integer.parseInt(value);
            if (intValue < 0) {
                errors.add(key + " darf nicht negativ sein, aber ist: " + intValue);
            }
        } catch (NumberFormatException e) {
            errors.add(key + " muss eine gültige Ganzzahl sein: '" + value + "'");
        }
    }

    private static void validateIntRange(Map<String, String> props, String key,
                                         int min, int max, List<String> errors) {
        String value = props.get(key);
        if (value == null || value.isEmpty()) return;
        try {
            int intValue = Integer.parseInt(value);
            if (intValue < min || intValue > max) {
                errors.add(key + " muss zwischen " + min + " und " + max + " sein, aber ist: " + intValue);
            }
        } catch (NumberFormatException e) {
            errors.add(key + " muss eine gültige Ganzzahl sein: '" + value + "'");
        }
    }
}
