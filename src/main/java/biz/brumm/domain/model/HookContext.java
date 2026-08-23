package biz.brumm.domain.model;

import java.util.Map;

/**
 * Kontext, der einem Hook bei der Ausführung übergeben wird.
 * Das Script erhält die Werte als Umgebungsvariablen (JCLAW_HOOK_*).
 *
 * @param stage    Aktueller Lifecycle-Stage
 * @param prompt   Benutzer-Prompt (bei Agent-Turns)
 * @param toolName Tool-Name (bei Tool-Hooks)
 * @param toolArgs Tool-Argumente (bei Tool-Hooks, JSON)
 * @param sessionId Session-ID (falls vorhanden)
 * @param metadata Zusätzliche Key-Value-Paare
 */
public record HookContext(String stage, String prompt, String toolName, String toolArgs,
                           String sessionId, Map<String, String> metadata) {

    public static HookContext forStage(String stage) {
        return new HookContext(stage, null, null, null, null, Map.of());
    }

    public static HookContext forAgentRun(String stage, String prompt, String sessionId) {
        return new HookContext(stage, prompt, null, null, sessionId, Map.of());
    }

    public static HookContext forToolCall(String stage, String toolName, String toolArgs, String sessionId) {
        return new HookContext(stage, null, toolName, toolArgs, sessionId, Map.of());
    }
}
