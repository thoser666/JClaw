package biz.brumm.infrastructure.adapter.out.hook;

import biz.brumm.config.HookProperties;
import biz.brumm.domain.model.Hook;
import biz.brumm.domain.model.HookContext;
import biz.brumm.domain.model.HookResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HookScriptExecutorTest {

    @TempDir
    Path tempDir;

    private HookProperties properties;
    private HookScriptExecutor executor;

    @BeforeEach
    void setUp() {
        properties = new HookProperties(tempDir.toString(), true, 5, List.of());
        executor = new HookScriptExecutor(properties);
    }

    private Path createBatScript(String filename, String command) throws IOException {
        Path script = tempDir.resolve(filename);
        Files.writeString(script, command);
        return script;
    }

    private String batCommand(String command) {
        return "cmd.exe /c " + command;
    }

    @Test
    void executeReturnsProceedOnExitZero() throws IOException {
        Path script = createBatScript("run.bat", batCommand("exit /b 0"));
        Hook hook = new Hook("test", "before_agent_run", 10, script, "");
        HookContext context = HookContext.forStage("before_agent_run");

        HookResult result = executor.execute(hook, context);

        assertThat(result.allowed()).isTrue();
        assertThat(result.hookName()).isEqualTo("test");
    }

    @Test
    void executeReturnsBlockOnNonZeroExit() throws IOException {
        Path script = createBatScript("run.bat", batCommand("echo blocked & exit /b 1"));
        Hook hook = new Hook("test", "before_agent_run", 10, script, "");
        HookContext context = HookContext.forStage("before_agent_run");

        HookResult result = executor.execute(hook, context);

        assertThat(result.allowed()).isFalse();
        assertThat(result.hookName()).isEqualTo("test");
    }

    @Test
    void executePassesEnvironmentVariables() throws IOException {
        Path script = createBatScript("run.bat", batCommand("echo %JCLAW_HOOK_STAGE%"));
        Hook hook = new Hook("test", "before_agent_run", 10, script, "");
        HookContext context = HookContext.forAgentRun("before_agent_run", "test prompt", "s1");

        HookResult result = executor.execute(hook, context);

        assertThat(result.allowed()).isTrue();
        assertThat(result.output()).contains("before_agent_run");
    }

    @Test
    void executeHandlesTimeout() throws IOException {
        Path script = createBatScript("run.bat", batCommand("ping -n 11 127.0.0.1 >nul & echo ok"));
        HookProperties shortTimeout = new HookProperties(tempDir.toString(), true, 1, List.of());
        HookScriptExecutor shortExecutor = new HookScriptExecutor(shortTimeout);
        Hook hook = new Hook("test", "before_agent_run", 10, script, "");
        HookContext context = HookContext.forStage("before_agent_run");

        HookResult result = shortExecutor.execute(hook, context);

        assertThat(result.allowed()).isFalse();
        assertThat(result.output()).contains("Timeout");
    }
}
