package biz.brumm.infrastructure.adapter.out.plugin;

import biz.brumm.infrastructure.sidecar.SidecarToolDescriptor;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;

/** Hermetischer Unit-Test für {@link PluginToolCallback} — Bridge-Protokoll, Abschnitt 9
 * (P4-01 "Tool-Schema → Spring-AI"): ohne laufenden Node-Sidecar, Dispatcher als reiner Stub. */
class PluginToolCallbackTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode parametersSchema() {
        ObjectNode properties = mapper.createObjectNode();
        properties.put("city", "The city to query");
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        return schema;
    }

    @Test
    void exposesToolSchemaAsSpringAiToolDefinition() {
        SidecarToolDescriptor descriptor = new SidecarToolDescriptor(
                "weather.get", "Queries the weather", parametersSchema());
        PluginToolCallback callback = new PluginToolCallback(
                descriptor, (name, args) -> {"{}".isEmpty(); return "{}"; });

        ToolDefinition definition = callback.getToolDefinition();

        assertThat(definition.name()).isEqualTo("weather.get");
        assertThat(definition.description()).isEqualTo("Queries the weather");
        assertThat(definition.inputSchema()).contains("\"city\"");
    }

    @Test
    void missingParametersYieldEmptyObjectSchema() {
        SidecarToolDescriptor descriptor = new SidecarToolDescriptor(
                "time.now", "Returns the current time", null);
        PluginToolCallback callback = new PluginToolCallback(descriptor, (n, a) -> "{}");

        ToolDefinition definition = callback.getToolDefinition();

        assertThat(definition.inputSchema()).contains("\"object\"");
        assertThat(definition.inputSchema()).contains("\"properties\"");
    }

    @Test
    void dispatchesToolNameAndArgumentsToDispatcher() {
        SidecarToolDescriptor descriptor = new SidecarToolDescriptor(
                "weather.get", "Queries the weather", parametersSchema());
        AtomicReference<String> seenName = new AtomicReference<>();
        AtomicReference<String> seenArgs = new AtomicReference<>();
        PluginToolCallback callback = new PluginToolCallback(
                descriptor,
                (name, args) -> {
                    seenName.set(name);
                    seenArgs.set(args);
                    return "{\"temp\":22}";
                });

        String result = callback.call("{\"city\":\"Berlin\"}");

        assertThat(seenName.get()).isEqualTo("weather.get");
        assertThat(seenArgs.get()).isEqualTo("{\"city\":\"Berlin\"}");
        assertThat(result).isEqualTo("{\"temp\":22}");
    }

    @Test
    void blankToolInputFallsBackToEmptyObject() {
        SidecarToolDescriptor descriptor = new SidecarToolDescriptor(
                "ping", "Pings the sidecar", parametersSchema());
        AtomicReference<String> seenArgs = new AtomicReference<>();
        PluginToolCallback callback = new PluginToolCallback(
                descriptor,
                (name, args) -> {
                    seenArgs.set(args);
                    return "{}";
                });

        callback.call("");

        assertThat(seenArgs.get()).isEqualTo("{}");
    }
}
