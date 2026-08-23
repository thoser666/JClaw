package biz.brumm.infrastructure.adapter.out.hook;

import biz.brumm.config.HookProperties;
import biz.brumm.domain.model.Hook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FileHookProviderTest {

    @TempDir
    Path tempDir;

    private HookProperties properties;

    @BeforeEach
    void setUp() {
        properties = new HookProperties(tempDir.toString(), true, 30, List.of());
    }

    @Test
    void returnsEmptyWhenDirectoryDoesNotExist() {
        HookProperties props = new HookProperties("/nonexistent", true, 30, List.of());
        FileHookProvider provider = new FileHookProvider(props);
        assertThat(provider.findAll()).isEmpty();
    }

    @Test
    void returnsEmptyWhenDisabled() {
        HookProperties props = new HookProperties(tempDir.toString(), false, 30, List.of());
        FileHookProvider provider = new FileHookProvider(props);
        assertThat(provider.findAll()).isEmpty();
    }

    @Test
    void parsesHookFromHookMd() throws IOException {
        Path hookDir = tempDir.resolve("my-hook");
        Files.createDirectories(hookDir);

        String hookMd = """
                ---
                name: before-agent-run
                stage: before_agent_run
                priority: 10
                script: ./run.sh
                description: Test hook
                ---
                """;
        Files.writeString(hookDir.resolve("HOOK.md"), hookMd);
        Files.writeString(hookDir.resolve("run.sh"), "#!/bin/sh\\necho ok");

        FileHookProvider provider = new FileHookProvider(properties);
        List<Hook> hooks = provider.findAll();

        assertThat(hooks).hasSize(1);
        assertThat(hooks.get(0).name()).isEqualTo("before-agent-run");
        assertThat(hooks.get(0).stage()).isEqualTo("before_agent_run");
        assertThat(hooks.get(0).priority()).isEqualTo(10);
        assertThat(hooks.get(0).description()).isEqualTo("Test hook");
    }

    @Test
    void filtersByStage() throws IOException {
        Path hookDir1 = tempDir.resolve("hook-1");
        Files.createDirectories(hookDir1);
        Files.writeString(hookDir1.resolve("HOOK.md"), """
                ---
                name: hook-1
                stage: before_agent_run
                priority: 10
                script: ./run.sh
                ---
                """);
        Files.writeString(hookDir1.resolve("run.sh"), "#!/bin/sh\\necho ok");

        Path hookDir2 = tempDir.resolve("hook-2");
        Files.createDirectories(hookDir2);
        Files.writeString(hookDir2.resolve("HOOK.md"), """
                ---
                name: hook-2
                stage: after_tool_call
                priority: 5
                script: ./run.sh
                ---
                """);
        Files.writeString(hookDir2.resolve("run.sh"), "#!/bin/sh\\necho ok");

        FileHookProvider provider = new FileHookProvider(properties);
        List<Hook> beforeHooks = provider.findByStage("before_agent_run");

        assertThat(beforeHooks).hasSize(1);
        assertThat(beforeHooks.get(0).name()).isEqualTo("hook-1");
    }

    @Test
    void skipsHooksWithoutScript() throws IOException {
        Path hookDir = tempDir.resolve("no-script");
        Files.createDirectories(hookDir);
        Files.writeString(hookDir.resolve("HOOK.md"), """
                ---
                name: no-script-hook
                stage: before_agent_run
                priority: 10
                ---
                """);

        FileHookProvider provider = new FileHookProvider(properties);
        assertThat(provider.findAll()).isEmpty();
    }

    @Test
    void sortsByPriorityDescending() throws IOException {
        Path hookDir1 = tempDir.resolve("low-priority");
        Files.createDirectories(hookDir1);
        Files.writeString(hookDir1.resolve("HOOK.md"), """
                ---
                name: low
                stage: before_agent_run
                priority: 1
                script: ./run.sh
                ---
                """);
        Files.writeString(hookDir1.resolve("run.sh"), "#!/bin/sh\\necho ok");

        Path hookDir2 = tempDir.resolve("high-priority");
        Files.createDirectories(hookDir2);
        Files.writeString(hookDir2.resolve("HOOK.md"), """
                ---
                name: high
                stage: before_agent_run
                priority: 100
                script: ./run.sh
                ---
                """);
        Files.writeString(hookDir2.resolve("run.sh"), "#!/bin/sh\\necho ok");

        FileHookProvider provider = new FileHookProvider(properties);
        List<Hook> hooks = provider.findByStage("before_agent_run");

        assertThat(hooks).hasSize(2);
        assertThat(hooks.get(0).name()).isEqualTo("high");
        assertThat(hooks.get(1).name()).isEqualTo("low");
    }

    @Test
    void respectsAllowedStagesFilter() throws IOException {
        HookProperties props = new HookProperties(tempDir.toString(), true, 30, List.of("before_agent_run"));

        Path hookDir1 = tempDir.resolve("allowed");
        Files.createDirectories(hookDir1);
        Files.writeString(hookDir1.resolve("HOOK.md"), """
                ---
                name: allowed-hook
                stage: before_agent_run
                priority: 10
                script: ./run.sh
                ---
                """);
        Files.writeString(hookDir1.resolve("run.sh"), "#!/bin/sh\\necho ok");

        Path hookDir2 = tempDir.resolve("not-allowed");
        Files.createDirectories(hookDir2);
        Files.writeString(hookDir2.resolve("HOOK.md"), """
                ---
                name: blocked-hook
                stage: after_tool_call
                priority: 10
                script: ./run.sh
                ---
                """);
        Files.writeString(hookDir2.resolve("run.sh"), "#!/bin/sh\\necho ok");

        FileHookProvider provider = new FileHookProvider(props);
        List<Hook> hooks = provider.findAll();

        assertThat(hooks).hasSize(1);
        assertThat(hooks.get(0).name()).isEqualTo("allowed-hook");
    }
}
