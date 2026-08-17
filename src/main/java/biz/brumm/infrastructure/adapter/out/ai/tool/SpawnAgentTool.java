package biz.brumm.infrastructure.adapter.out.ai.tool;

import biz.brumm.domain.model.AgentCommand;
import biz.brumm.domain.model.AgentResponse;
import biz.brumm.domain.port.out.AgentTool;
import biz.brumm.domain.port.out.AiProviderPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * Werkzeug zum Starten eines Sub-Agenten mit eigenem Prompt.
 * Der Sub-Agent erhält dieselben Werkzeuge und läuft eigenständig
 * mit einer frischen Konversation (oder einem gemeinsamen Kontext).
 * Rekursionstiefe ist durch {@code maxDepth} begrenzt.
 */
public class SpawnAgentTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(SpawnAgentTool.class);

    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    private final AiProviderPort aiProviderPort;
    private final int maxDepth;

    public SpawnAgentTool(AiProviderPort aiProviderPort, int maxDepth) {
        this.aiProviderPort = aiProviderPort;
        this.maxDepth = maxDepth;
    }

    @Tool(name = "spawn_agent",
            description = "Startet einen Sub-Agenten mit einem eigenen Prompt. Der Sub-Agent hat "
                    + "Zugriff auf dieselben Werkzeuge und führt die Aufgabe eigenständig aus. "
                    + "Gibt die finale Antwort des Sub-Agenten zurück.")
    public String spawnAgent(
            @ToolParam(description = "Die Aufgabe oder Frage, die der Sub-Agent bearbeiten soll.") String prompt,
            @ToolParam(description = "Optionale contextId. Wird nicht angegeben, verwendet der Sub-Agent "
                    + "einen frischen Konversationskontext.", required = false) String contextId) {

        int currentDepth = DEPTH.get();
        if (currentDepth >= maxDepth) {
            String msg = "Maximale Rekursionstiefe (" + maxDepth + ") erreicht. "
                    + "Ein Sub-Agent kann hier nicht gestartet werden.";
            log.warn(msg);
            return msg;
        }

        try {
            DEPTH.set(currentDepth + 1);
            log.info("Sub-Agent gestartet (Tiefe {}/{}): prompt='{}', contextId='{}'",
                    currentDepth + 1, maxDepth, truncate(prompt, 80), contextId);

            AgentCommand command = new AgentCommand(prompt,
                    (contextId != null && !contextId.isBlank()) ? contextId : null);
            AgentResponse response = aiProviderPort.execute(command, buildSubAgentPrompt(), maxDepth);

            log.info("Sub-Agent beendet (Tiefe {}): {} Iteration(en), {} Tool-Aufruf(e).",
                    currentDepth + 1, response.iterations(), response.toolInvocations().size());
            return response.content();
        } catch (Exception e) {
            log.error("Sub-Agent fehlgeschlagen (Tiefe {}): {}", currentDepth + 1, e.getMessage());
            return "Fehler beim Sub-Agenten: " + e.getMessage();
        } finally {
            DEPTH.set(currentDepth);
        }
    }

    private String buildSubAgentPrompt() {
        return """
                Du bist ein Sub-Agent. Du hast Zugriff auf dieselben Werkzeuge wie der Haupt-Agent.
                Löse die dir übergebene Aufgabe eigenständig und präzise.
                Gib eine direkte, knappe Antwort.
                """;
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "…";
    }
}
