package biz.brumm.config;

import biz.brumm.infrastructure.adapter.out.ai.tool.FileTool;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

@Configuration
public class FileToolConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "jclaw.agent.filetool", name = "workdir")
    public FileTool fileTool(FileToolProperties properties) {
        return new FileTool(Path.of(properties.workdir()), properties.effectiveMaxReadBytes());
    }
}
