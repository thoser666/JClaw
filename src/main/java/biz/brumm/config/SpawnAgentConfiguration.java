package biz.brumm.config;

import biz.brumm.domain.port.out.AiProviderPort;
import biz.brumm.infrastructure.adapter.out.ai.tool.SpawnAgentTool;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SpawnAgentConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "jclaw.agent.spawnagent", name = "enabled", havingValue = "true")
    public SpawnAgentTool spawnAgentTool(AiProviderPort aiProviderPort, SpawnAgentProperties properties) {
        return new SpawnAgentTool(aiProviderPort, properties.effectiveMaxDepth());
    }
}
