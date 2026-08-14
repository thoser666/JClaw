package biz.brumm.config;

import biz.brumm.infrastructure.adapter.out.ai.tool.WebFetchTool;
import biz.brumm.infrastructure.adapter.out.ai.tool.WebSearchTool;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WebToolConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "jclaw.agent.webtool", name = "enabled", havingValue = "true")
    public WebFetchTool webFetchTool(WebToolProperties properties) {
        return new WebFetchTool(properties.allowedDomains(), properties.effectiveFetchTimeout(),
                properties.effectiveMaxFetchBytes());
    }

    @Bean
    @ConditionalOnProperty(prefix = "jclaw.agent.webtool", name = "enabled", havingValue = "true")
    public WebSearchTool webSearchTool(WebToolProperties properties) {
        return new WebSearchTool(properties.effectiveSearchEndpoint(), properties.effectiveMaxSearchResults(),
                properties.effectiveFetchTimeout());
    }
}
