package biz.brumm.config;

import biz.brumm.infrastructure.adapter.out.ai.tool.WebFetchTool;
import biz.brumm.infrastructure.adapter.out.ai.tool.WebSearchTool;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class WebToolConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(EnableProperties.class, WebToolConfiguration.class);

    @Test
    void bothToolsRegisteredWhenEnabled() {
        contextRunner
                .withPropertyValues("jclaw.agent.webtool.enabled=true")
                .run(context -> assertThat(context)
                        .hasSingleBean(WebFetchTool.class)
                        .hasSingleBean(WebSearchTool.class));
    }

    @Test
    void toolsAbsentWithoutEnabledProperty() {
        contextRunner.run(context -> assertThat(context)
                .doesNotHaveBean(WebFetchTool.class)
                .doesNotHaveBean(WebSearchTool.class));
    }

    @Test
    void toolsAbsentWhenDisabled() {
        contextRunner
                .withPropertyValues("jclaw.agent.webtool.enabled=false")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(WebFetchTool.class)
                        .doesNotHaveBean(WebSearchTool.class));
    }

    @Test
    void webFetchToolCarriesAllowedDomains() {
        contextRunner
                .withPropertyValues(
                        "jclaw.agent.webtool.enabled=true",
                        "jclaw.agent.webtool.allowed-domains=example.com, docs.spring.io")
                .run(context -> {
                    WebFetchTool tool = context.getBean(WebFetchTool.class);
                    assertThat(tool).isNotNull();
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(WebToolProperties.class)
    static class EnableProperties {
    }
}
