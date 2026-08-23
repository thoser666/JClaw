package biz.brumm.domain.model;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HookTest {

    @Test
    void validHookCreation() {
        Hook hook = new Hook("test-hook", "before_agent_run", 10, Path.of("/hooks/test/run.sh"), "Test");

        assertThat(hook.name()).isEqualTo("test-hook");
        assertThat(hook.stage()).isEqualTo("before_agent_run");
        assertThat(hook.priority()).isEqualTo(10);
        assertThat(hook.scriptPath()).isEqualTo(Path.of("/hooks/test/run.sh"));
        assertThat(hook.description()).isEqualTo("Test");
    }

    @Test
    void rejectsBlankName() {
        assertThatThrownBy(() -> new Hook("", "before_agent_run", 10, Path.of("/test"), ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Name");
    }

    @Test
    void rejectsBlankStage() {
        assertThatThrownBy(() -> new Hook("test", "", 10, Path.of("/test"), ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Stage");
    }

    @Test
    void rejectsNullScriptPath() {
        assertThatThrownBy(() -> new Hook("test", "before_agent_run", 10, null, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ScriptPath");
    }
}
