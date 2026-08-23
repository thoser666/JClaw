package biz.brumm.config;

import biz.brumm.domain.port.out.AiProviderPort;
import biz.brumm.domain.port.out.HookCallback;
import biz.brumm.domain.service.HookService;
import biz.brumm.infrastructure.adapter.out.ai.HookableAiProviderPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class HookConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "jclaw.hooks", name = "enabled", havingValue = "true")
    public AiProviderPort hookableAiProviderPort(AiProviderPort delegate, HookCallback hookCallback) {
        return new HookableAiProviderPort(delegate, hookCallback);
    }
}
