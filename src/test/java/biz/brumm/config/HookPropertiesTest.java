package biz.brumm.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HookPropertiesTest {

    @Test
    void defaultsAreApplied() {
        HookProperties props = new HookProperties(null, false, 0, null);
        assertThat(props.dir()).isEqualTo("./hooks");
        assertThat(props.enabled()).isFalse();
        assertThat(props.scriptTimeout()).isEqualTo(30);
        assertThat(props.allowedStages()).isEmpty();
    }

    @Test
    void customValuesArePreserved() {
        HookProperties props = new HookProperties("/custom/hooks", true, 60, List.of("before_agent_run"));
        assertThat(props.dir()).isEqualTo("/custom/hooks");
        assertThat(props.enabled()).isTrue();
        assertThat(props.scriptTimeout()).isEqualTo(60);
        assertThat(props.allowedStages()).containsExactly("before_agent_run");
    }

    @Test
    void blankDirDefaultsToHooks() {
        HookProperties props = new HookProperties("  ", false, 10, List.of());
        assertThat(props.dir()).isEqualTo("./hooks");
    }
}
