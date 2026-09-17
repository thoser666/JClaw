package biz.brumm.infrastructure.adapter.out.plugin;

import biz.brumm.infrastructure.sidecar.SidecarToolDescriptor;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.util.StringUtils;

import java.util.function.BiFunction;

/**
 * Bindet ein im Node-Sidecar registriertes Plugin-Tool (Bridge-Protokoll, Abschnitt 9:
 * "Tool-Schema &rarr; Spring-AI", P4-01: {@code PluginToolCallback}) als Spring-AI
 * {@link ToolCallback} an.
 * <p>
 * Das JSON-Schema aus {@link SidecarToolDescriptor#parameters()} wird zur
 * {@link ToolDefinition#inputSchema()} des Tools; die eigentliche Ausf&uuml;hrung delegiert an
 * den injizierten Dispatcher {@code (toolName, argsJson) &rarr; resultJson} &ndash; hermetic ohne
 * laufenden Node-Sidecar, der Dispatcher ist im Unit-Test ein reiner Stub.
 *
 * @param descriptor Der Sidecar-Tool-Descriptor (Name, Beschreibung, Parameter-JSON-Schema).
 * @param dispatcher Dispatcher {@code (toolName, argsJson) &rarr; resultJson}.
 */
public record PluginToolCallback(SidecarToolDescriptor descriptor,
                                 BiFunction<String, String, String> dispatcher) implements ToolCallback {

    @Override
    public ToolDefinition getToolDefinition() {
        String schema = descriptor.parameters() == null
                ? "{\"type\":\"object\",\"properties\":{}}"
                : descriptor.parameters().toString();
        return DefaultToolDefinition.builder()
                .name(descriptor.name())
                .description(descriptor.description())
                .inputSchema(schema)
                .build();
    }

    @Override
    public String call(String toolInput) {
        String input = StringUtils.hasText(toolInput) ? toolInput : "{}";
        return dispatcher.apply(descriptor.name(), input);
    }
}
