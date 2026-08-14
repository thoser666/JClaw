package biz.brumm.config;

import biz.brumm.infrastructure.mcp.McpToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class McpConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(EnableProperties.class, McpConfiguration.class);

    @Test
    void registryAbsentWithoutEnabledProperty() {
        contextRunner.run(context -> assertThat(context).doesNotHaveBean(McpToolRegistry.class));
    }

    @Test
    void registryAbsentWhenDisabled() {
        contextRunner
                .withPropertyValues("jclaw.mcp.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(McpToolRegistry.class));
    }

    @Test
    void registryPresentWhenEnabledWithoutServers() {
        contextRunner
                .withPropertyValues("jclaw.mcp.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(McpToolRegistry.class);
                    assertThat(context.getBean(McpToolRegistry.class).toolCallbacks()).isEmpty();
                });
    }

    @Test
    void bindsServerEntriesFromConfiguration() {
        contextRunner
                .withPropertyValues("jclaw.mcp.servers.filesystem.url=http://localhost:8080")
                .run(context -> {
                    assertThat(context).hasSingleBean(McpToolProperties.class);
                    McpToolProperties properties = context.getBean(McpToolProperties.class);
                    assertThat(properties.servers()).containsOnlyKeys("filesystem");
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(McpToolProperties.class)
    static class EnableProperties {
    }
}
