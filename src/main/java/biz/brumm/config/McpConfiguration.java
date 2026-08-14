package biz.brumm.config;

import biz.brumm.infrastructure.mcp.McpToolRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registriert die MCP-Integration. Erst wenn {@code jclaw.mcp.enabled=true} gesetzt ist,
 * werden Verbindungen zu den konfigurierten MCP-Servern aufgebaut (Deny-by-Default).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "jclaw.mcp", name = "enabled", havingValue = "true")
public class McpConfiguration {

    @Bean(destroyMethod = "close")
    public McpToolRegistry mcpToolRegistry(McpToolProperties properties) {
        return McpToolRegistry.connect(properties);
    }
}
