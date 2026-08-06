package biz.brumm.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentCommandTest {

    @Test
    void rejectsBlankPrompt() {
        assertThatThrownBy(() -> new AgentCommand("  ", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("leer");
    }

    @Test
    void rejectsNullPrompt() {
        assertThatThrownBy(() -> new AgentCommand(null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
