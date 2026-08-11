package biz.brumm.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShellToolPropertiesTest {

    @Test
    void acceptsValidValues() {
        assertThatCode(() -> new ShellToolProperties(true, "./workspace", 10, 5000)).doesNotThrowAnyException();
    }

    @Test
    void acceptsDefaultsWithoutValidationError() {
        assertThatCode(() -> new ShellToolProperties(false, null, null, null)).doesNotThrowAnyException();
    }

    @Test
    void rejectsNonPositiveTimeout() {
        assertThatThrownBy(() -> new ShellToolProperties(true, "./workspace", 0, 5000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeout-seconds");
        assertThatThrownBy(() -> new ShellToolProperties(true, "./workspace", -1, 5000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonPositiveMaxOutputChars() {
        assertThatThrownBy(() -> new ShellToolProperties(true, "./workspace", 10, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-output-chars");
    }

    @Test
    void effectiveValuesFallBackToDefaults() {
        ShellToolProperties defaults = new ShellToolProperties(false, null, null, null);

        assertThat(defaults.effectiveWorkdir()).isEqualTo(".");
        assertThat(defaults.effectiveTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(defaults.effectiveMaxOutputChars()).isEqualTo(10_000);
    }

    @Test
    void effectiveValuesUseConfiguredValues() {
        ShellToolProperties configured = new ShellToolProperties(true, "./ws", 5, 100);

        assertThat(configured.effectiveWorkdir()).isEqualTo("./ws");
        assertThat(configured.effectiveTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(configured.effectiveMaxOutputChars()).isEqualTo(100);
    }
}
