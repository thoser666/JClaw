package biz.brumm.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Konfiguration für MCP-Server (Model Context Protocol). Erst wenn {@code enabled=true}
 * gesetzt ist, werden die Tools externer MCP-Server registriert (Deny-by-Default).
 *
 * @param enabled        Schaltet die MCP-Integration frei.
 * @param requestTimeout Timeout für Anfragen an die MCP-Server (Standard: 60 Sekunden).
 * @param servers        Benannte MCP-Server ({@code jclaw.mcp.servers.<name>.*}).
 */
@ConfigurationProperties(prefix = "jclaw.mcp")
public record McpToolProperties(boolean enabled, Duration requestTimeout, Map<String, Server> servers) {

    public McpToolProperties {
        requestTimeout = requestTimeout == null ? Duration.ofSeconds(60) : requestTimeout;
        servers = servers == null ? Map.of() : Map.copyOf(servers);
    }

    public Duration effectiveRequestTimeout() {
        return requestTimeout;
    }

    /**
     * Definition eines einzelnen MCP-Servers. Es muss entweder {@code url} (HTTP-Transport,
     * Streamable HTTP) oder {@code command} (STDIO-Transport) gesetzt sein.
     *
     * @param url      Basis-URL des HTTP-Servers (ohne Pfad; der Endpunkt wird angehängt).
     * @param command  Befehl für den STDIO-Server (z. B. ein ausführbares Skript).
     * @param args     Argumente für den STDIO-Befehl.
     * @param env      Zusätzliche Umgebungsvariablen für den STDIO-Befehl.
     * @param endpoint MCP-Endpunkt-Pfad für HTTP-Server (Standard: {@code /mcp}).
     */
    public record Server(String url, String command, List<String> args, Map<String, String> env, String endpoint) {

        public Server {
            args = args == null ? List.of() : List.copyOf(args);
            env = env == null ? Map.of() : Map.copyOf(env);
            boolean hasUrl = url != null && !url.isBlank();
            boolean hasCommand = command != null && !command.isBlank();
            if (hasUrl == hasCommand) {
                throw new IllegalArgumentException(
                        "jclaw.mcp.servers.*: genau eines von 'url' oder 'command' muss gesetzt sein.");
            }
        }
    }
}
