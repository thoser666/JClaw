package biz.brumm.config;

import biz.brumm.domain.service.SecretEgressGuard;
import biz.brumm.infrastructure.adapter.out.ai.tool.WebFetchTool;
import biz.brumm.infrastructure.adapter.out.ai.tool.WebSearchTool;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WebToolConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "jclaw.agent.webtool", name = "enabled", havingValue = "true")
    public WebFetchTool webFetchTool(WebToolProperties properties, ObjectProvider<SecretEgressGuard> egressGuard) {
        return new WebFetchTool(properties.allowedDomains(), properties.effectiveFetchTimeout(),
                properties.effectiveMaxFetchBytes(), egressGuard.getIfAvailable());
    }

    @Bean
    @ConditionalOnProperty(prefix = "jclaw.agent.webtool", name = "enabled", havingValue = "true")
    public WebSearchTool webSearchTool(WebToolProperties properties, ObjectProvider<SecretEgressGuard> egressGuard) {
        return new WebSearchTool(properties.effectiveSearchEndpoint(), properties.effectiveMaxSearchResults(),
                properties.effectiveFetchTimeout(), egressGuard.getIfAvailable());
    }
}
