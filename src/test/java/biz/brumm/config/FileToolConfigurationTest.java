package biz.brumm.config;

import biz.brumm.infrastructure.adapter.out.ai.tool.ApplyPatchTool;
import biz.brumm.infrastructure.adapter.out.ai.tool.FileTool;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class FileToolConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(EnableProperties.class, FileToolConfiguration.class);

    @Test
    void fileToolRegisteredWhenWorkdirConfigured() {
        contextRunner
                .withPropertyValues("jclaw.agent.filetool.workdir=./workspace")
                .run(context -> assertThat(context)
                        .hasSingleBean(FileTool.class)
                        .hasSingleBean(ApplyPatchTool.class));
    }

    @Test
    void fileToolAbsentWithoutWorkdir() {
        contextRunner.run(context -> assertThat(context)
                .doesNotHaveBean(FileTool.class)
                .doesNotHaveBean(ApplyPatchTool.class));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(FileToolProperties.class)
    static class EnableProperties {
    }
}
