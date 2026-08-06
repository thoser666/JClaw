package biz.brumm.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClawAgentPropertiesTest {

    @Test
    void acceptsValidValues() {
        assertThatCode(() -> new ClawAgentProperties(8, 10)).doesNotThrowAnyException();
    }

    @Test
    void rejectsNonPositiveMaxIterations() {
        assertThatThrownBy(() -> new ClawAgentProperties(0, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-iterations");
        assertThatThrownBy(() -> new ClawAgentProperties(-1, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonPositiveMaxHistoryMessages() {
        assertThatThrownBy(() -> new ClawAgentProperties(8, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-history-messages");
        assertThatThrownBy(() -> new ClawAgentProperties(8, -5))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
