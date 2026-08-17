package biz.brumm.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

class SpawnAgentPropertiesTest {

    @Test
    void acceptsValidValues() {
        SpawnAgentProperties props = new SpawnAgentProperties(true, 3);
        assertThat(props.enabled()).isTrue();
        assertThat(props.effectiveMaxDepth()).isEqualTo(3);
    }

    @Test
    void defaultsMaxDepthWhenZero() {
        SpawnAgentProperties props = new SpawnAgentProperties(false, 0);
        assertThat(props.effectiveMaxDepth()).isEqualTo(SpawnAgentProperties.DEFAULT_MAX_DEPTH);
    }

    @Test
    void rejectsNegativeMaxDepth() {
        assertThatThrownBy(() -> new SpawnAgentProperties(false, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-depth");
    }

    @Test
    void disabledByDefault() {
        SpawnAgentProperties props = new SpawnAgentProperties(false, 3);
        assertThat(props.enabled()).isFalse();
    }
}
