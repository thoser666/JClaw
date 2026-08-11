package biz.brumm.config;

import biz.brumm.infrastructure.adapter.out.ai.tool.ShellTool;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

@Configuration
public class ShellToolConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "jclaw.agent.shelltool", name = "enabled", havingValue = "true")
    public ShellTool shellTool(ShellToolProperties properties) {
        return new ShellTool(Path.of(properties.effectiveWorkdir()), properties.effectiveTimeout(),
                properties.effectiveMaxOutputChars());
    }
}
