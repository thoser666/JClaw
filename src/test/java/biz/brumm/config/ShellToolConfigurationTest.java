package biz.brumm.config;

import biz.brumm.infrastructure.adapter.out.ai.tool.ShellTool;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class ShellToolConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(EnableProperties.class, ShellToolConfiguration.class);

    @Test
    void shellToolRegisteredWhenEnabled() {
        contextRunner
                .withPropertyValues("jclaw.agent.shelltool.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(ShellTool.class));
    }

    @Test
    void shellToolAbsentWithoutEnabledProperty() {
        contextRunner.run(context -> assertThat(context).doesNotHaveBean(ShellTool.class));
    }

    @Test
    void shellToolAbsentWhenDisabled() {
        contextRunner
                .withPropertyValues("jclaw.agent.shelltool.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(ShellTool.class));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ShellToolProperties.class)
    static class EnableProperties {
    }
}
