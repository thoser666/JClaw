package biz.brumm.infrastructure.mcp;

import biz.brumm.config.McpToolProperties;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.McpConnectionInfo;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.util.JacksonUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Hält die Verbindungen zu den konfigurierten MCP-Servern und stellt deren Tools als
 * Spring-AI-{@link ToolCallback}s bereit. Die Modell-seitigen Tool-Namen werden mit dem
 * bereinigten Server-Namen präfigiert ({@code <server>_<tool>}).
 */
public final class McpToolRegistry implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(McpToolRegistry.class);

    private static final String CLIENT_NAME = "jclaw";
    private static final String CLIENT_VERSION = "0.0.1";
    private static final String FALLBACK_SERVER_NAME = "mcp";

    private final List<McpSyncClient> clients;
    private final List<ToolCallback> toolCallbacks;

    public McpToolRegistry(List<McpSyncClient> clients) {
        this.clients = List.copyOf(clients);
        SyncMcpToolCallbackProvider provider = SyncMcpToolCallbackProvider.builder()
                .mcpClients(clients)
                .toolNamePrefixGenerator(this::prefixedToolName)
                .build();
        this.toolCallbacks = List.of(provider.getToolCallbacks());
    }

    /**
     * Verbindet sich mit allen konfigurierten Servern und liefert die fertige Registry.
     * Scheitert eine Verbindung, wird sofort eine Ausnahme geworfen (Fail-Fast).
     */
    public static McpToolRegistry connect(McpToolProperties properties) {
        List<McpSyncClient> clients = new ArrayList<>();
        Duration timeout = properties.effectiveRequestTimeout();
        for (Map.Entry<String, McpToolProperties.Server> entry : properties.servers().entrySet()) {
            log.info("Verbinde mit MCP-Server '{}' ...", entry.getKey());
            try {
                clients.add(connect(entry.getValue(), timeout));
            } catch (RuntimeException ex) {
                throw new IllegalStateException(
                        "Verbindung zu MCP-Server '%s' fehlgeschlagen".formatted(entry.getKey()), ex);
            }
        }
        return new McpToolRegistry(clients);
    }

    private static McpSyncClient connect(McpToolProperties.Server server, Duration requestTimeout) {
        McpClientTransport transport;
        if (server.url() != null && !server.url().isBlank()) {
            HttpClientStreamableHttpTransport.Builder builder = HttpClientStreamableHttpTransport.builder(server.url());
            if (server.endpoint() != null && !server.endpoint().isBlank()) {
                builder.endpoint(server.endpoint());
            }
            transport = builder.build();
        } else {
            ServerParameters parameters = ServerParameters.builder(server.command())
                    .args(server.args())
                    .env(server.env())
                    .build();
            transport = new StdioClientTransport(parameters, new JacksonMcpJsonMapper(JacksonUtils.getDefaultJsonMapper()));
        }

        McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(requestTimeout)
                .clientInfo(new McpSchema.Implementation(CLIENT_NAME, CLIENT_VERSION))
                .build();
        client.initialize();
        return client;
    }

    public List<ToolCallback> toolCallbacks() {
        return toolCallbacks;
    }

    @Override
    public void close() {
        for (McpSyncClient client : clients) {
            try {
                client.close();
            } catch (RuntimeException ex) {
                log.warn("MCP-Client konnte nicht geschlossen werden.", ex);
            }
        }
    }

    private String prefixedToolName(McpConnectionInfo connection, McpSchema.Tool tool) {
        String serverName = FALLBACK_SERVER_NAME;
        if (connection.initializeResult() != null && connection.initializeResult().serverInfo() != null) {
            String infoName = connection.initializeResult().serverInfo().name();
            if (infoName != null && !infoName.isBlank()) {
                serverName = infoName;
            }
        }
        return sanitize(serverName) + "_" + sanitize(tool.name());
    }

    static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("[^a-zA-Z0-9_-]", "_");
    }
}
