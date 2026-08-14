package biz.brumm.infrastructure.mcp;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpToolRegistryTest {

    @Test
    void exposesPrefixedToolCallbacksFromConnectedClient() {
        McpSyncClient client = mock(McpSyncClient.class);
        when(client.getCurrentInitializationResult()).thenReturn(initResult("math-server", "1.0.0"));
        when(client.getClientInfo()).thenReturn(new McpSchema.Implementation("jclaw", "0.0.1"));
        when(client.getClientCapabilities()).thenReturn(McpSchema.ClientCapabilities.builder().build());
        when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(
                List.of(tool("add", "Adds two numbers"), tool("multiply", "Multiplies")), null));

        McpToolRegistry registry = new McpToolRegistry(List.of(client));

        List<ToolCallback> callbacks = registry.toolCallbacks();
        assertThat(callbacks).hasSize(2);
        assertThat(callbacks).extracting(cb -> cb.getToolDefinition().name())
                .containsExactly("math-server_add", "math-server_multiply");
    }

    @Test
    void invokesClientToolWithArguments() {
        McpSyncClient client = mock(McpSyncClient.class);
        when(client.getCurrentInitializationResult()).thenReturn(initResult("math-server", "1.0.0"));
        when(client.getClientInfo()).thenReturn(new McpSchema.Implementation("jclaw", "0.0.1"));
        when(client.getClientCapabilities()).thenReturn(McpSchema.ClientCapabilities.builder().build());
        when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(tool("add", "Adds")), null));
        when(client.callTool(any(McpSchema.CallToolRequest.class)))
                .thenReturn(McpSchema.CallToolResult.builder().addTextContent("5").isError(false).build());

        McpToolRegistry registry = new McpToolRegistry(List.of(client));

        String result = registry.toolCallbacks().get(0).call("{\"a\":2,\"b\":3}");

        assertThat(result).contains("5");
        verify(client).callTool(any(McpSchema.CallToolRequest.class));
    }

    @Test
    void sanitizesServerNameForToolPrefix() {
        McpSyncClient client = mock(McpSyncClient.class);
        when(client.getCurrentInitializationResult()).thenReturn(initResult("Math Server", "1.0.0"));
        when(client.getClientInfo()).thenReturn(new McpSchema.Implementation("jclaw", "0.0.1"));
        when(client.getClientCapabilities()).thenReturn(McpSchema.ClientCapabilities.builder().build());
        when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(tool("do-it", "Does it")), null));

        McpToolRegistry registry = new McpToolRegistry(List.of(client));

        assertThat(registry.toolCallbacks()).extracting(cb -> cb.getToolDefinition().name())
                .containsExactly("Math_Server_do-it");
    }

    @Test
    void closeClosesAllClients() {
        McpSyncClient first = mock(McpSyncClient.class);
        McpSyncClient second = mock(McpSyncClient.class);
        when(first.getCurrentInitializationResult()).thenReturn(initResult("server-a", "1.0.0"));
        when(first.getClientInfo()).thenReturn(new McpSchema.Implementation("jclaw", "0.0.1"));
        when(first.getClientCapabilities()).thenReturn(McpSchema.ClientCapabilities.builder().build());
        when(first.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(), null));
        when(second.getCurrentInitializationResult()).thenReturn(initResult("server-b", "1.0.0"));
        when(second.getClientInfo()).thenReturn(new McpSchema.Implementation("jclaw", "0.0.1"));
        when(second.getClientCapabilities()).thenReturn(McpSchema.ClientCapabilities.builder().build());
        when(second.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(), null));

        McpToolRegistry registry = new McpToolRegistry(List.of(first, second));

        registry.close();

        verify(first).close();
        verify(second).close();
    }

    @Test
    void sanitizeReplacesInvalidCharacters() {
        assertThat(McpToolRegistry.sanitize("Math Server")).isEqualTo("Math_Server");
        assertThat(McpToolRegistry.sanitize("a/b.c")).isEqualTo("a_b_c");
        assertThat(McpToolRegistry.sanitize(null)).isEmpty();
    }

    private McpSchema.InitializeResult initResult(String serverName, String version) {
        return new McpSchema.InitializeResult("2025-03-26",
                McpSchema.ServerCapabilities.builder().tools(true).build(),
                new McpSchema.Implementation(serverName, version), null);
    }

    private McpSchema.Tool tool(String name, String description) {
        return McpSchema.Tool.builder()
                .name(name)
                .title(name)
                .description(description)
                .inputSchema(new McpSchema.JsonSchema("object", Map.of(), List.of(), false, null, null))
                .build();
    }
}
