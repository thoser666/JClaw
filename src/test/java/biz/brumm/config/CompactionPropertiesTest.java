package biz.brumm.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CompactionPropertiesTest {

    @Test
    void defaultsAreApplied() {
        CompactionProperties props = new CompactionProperties(false, 0, 0, null);
        assertThat(props.enabled()).isFalse();
        assertThat(props.threshold()).isEqualTo(20);
        assertThat(props.retainCount()).isEqualTo(4);
        assertThat(props.summaryPrompt()).contains("Assistent");
    }

    @Test
    void customValuesArePreserved() {
        CompactionProperties props = new CompactionProperties(true, 30, 6, "Custom prompt");
        assertThat(props.enabled()).isTrue();
        assertThat(props.threshold()).isEqualTo(30);
        assertThat(props.retainCount()).isEqualTo(6);
        assertThat(props.summaryPrompt()).isEqualTo("Custom prompt");
    }
}
