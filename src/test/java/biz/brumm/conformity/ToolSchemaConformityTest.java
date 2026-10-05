package biz.brumm.conformity;

import biz.brumm.domain.port.out.AgentTool;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Set;

import static java.util.stream.Collectors.toSet;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * P4-05: Tool-Schema-Konformitätstest (Paritäts-Testsuite).
 * <p>
 * Baut exakt die Tool-Liste auf, die der Agent sieht ({@code ToolCallbacks.from(...)}, gleiches
 * Muster wie {@code OllamaAiAdapter}), und prüft, dass jedes aktivierte Tool ein wohlgeformtes
 * LLM-Schema liefert: Name und Beschreibung vorhanden, Input-Schema vom Typ {@code object}.
 * Zusätzlich muss der Kern (Rechnen, Zeit) auch im Default-Kontext exponiert sein.
 */
@SpringBootTest
class ToolSchemaConformityTest {

    @Autowired
    private List<AgentTool> agentTools;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void defaultToolSchemasAreWellFormed() throws Exception {
        ToolCallback[] callbacks = ToolCallbacks.from(agentTools.toArray());

        assertThat(callbacks)
                .describedAs("Im Default-Kontext müssen Agent-Tools als Tool-Callbacks exponiert sein.")
                .isNotEmpty();

        for (ToolCallback callback : callbacks) {
            ToolDefinition definition = callback.getToolDefinition();
            assertThat(definition.name())
                    .describedAs("Tool-Schema muss einen gültigen Namen tragen.")
                    .isNotBlank();
            assertThat(definition.description())
                    .describedAs("Tool-Schema für '%s' muss eine Beschreibung tragen.", definition.name())
                    .isNotBlank();
            JsonNode inputSchema = MAPPER.readTree(definition.inputSchema());
            assertThat(inputSchema.path("type").asText())
                    .describedAs("Tool-Schema für '%s' muss ein JSON-Objekt-Schema sein.", definition.name())
                    .isEqualTo("object");
        }
    }

    @Test
    void coreToolsAreAlwaysExposed() {
        Set<String> names = java.util.Arrays.stream(ToolCallbacks.from(agentTools.toArray()))
                .map(callback -> callback.getToolDefinition().name())
                .collect(toSet());

        assertThat(names)
                .describedAs("Kern-Tools (Rechnen, Zeit) müssen im Default-Kontext exponiert sein.")
                .contains("calculate", "getCurrentDateTime");
    }
}