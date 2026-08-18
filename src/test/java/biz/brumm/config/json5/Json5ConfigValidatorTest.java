package biz.brumm.config.json5;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Json5ConfigValidatorTest {

    @Test
    void validConfigPassesValidation() {
        Map<String, String> props = Map.of(
                "agents.max-iterations", "8",
                "session.reset-mode", "daily"
        );

        assertThatCode(() -> Json5ConfigValidator.validate(props))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsUnknownTopLevelPrefix() {
        Map<String, String> props = Map.of(
                "unknown-section.key", "value"
        );

        assertThatThrownBy(() -> Json5ConfigValidator.validate(props))
                .isInstanceOf(Json5ConfigValidationException.class)
                .hasMessageContaining("unknown-section");
    }

    @Test
    void allowsKnownTopLevelPrefixes() {
        Map<String, String> props = Map.of(
                "gateway.host", "localhost",
                "agents.max-iterations", "8",
                "session.reset-mode", "idle",
                "mcp.enabled", "false"
        );

        assertThatCode(() -> Json5ConfigValidator.validate(props))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsInvalidSessionResetMode() {
        Map<String, String> props = Map.of(
                "session.reset-mode", "weekly"
        );

        assertThatThrownBy(() -> Json5ConfigValidator.validate(props))
                .isInstanceOf(Json5ConfigValidationException.class)
                .hasMessageContaining("reset-mode")
                .hasMessageContaining("weekly");
    }

    @Test
    void acceptsValidSessionResetModes() {
        for (String mode : new String[]{"none", "daily", "idle"}) {
            Map<String, String> props = Map.of("session.reset-mode", mode);
            assertThatCode(() -> Json5ConfigValidator.validate(props))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void rejectsResetAtHourOutOfRange() {
        Map<String, String> props = Map.of(
                "session.reset-at-hour", "25"
        );

        assertThatThrownBy(() -> Json5ConfigValidator.validate(props))
                .isInstanceOf(Json5ConfigValidationException.class)
                .hasMessageContaining("reset-at-hour");
    }

    @Test
    void rejectsNegativeMaxIterations() {
        Map<String, String> props = Map.of(
                "agents.max-iterations", "-1"
        );

        assertThatThrownBy(() -> Json5ConfigValidator.validate(props))
                .isInstanceOf(Json5ConfigValidationException.class)
                .hasMessageContaining("max-iterations");
    }

    @Test
    void rejectsNonBooleanMcpEnabled() {
        Map<String, String> props = Map.of(
                "mcp.enabled", "yes"
        );

        assertThatThrownBy(() -> Json5ConfigValidator.validate(props))
                .isInstanceOf(Json5ConfigValidationException.class)
                .hasMessageContaining("mcp.enabled");
    }

    @Test
    void acceptsBooleanMcpEnabled() {
        for (String val : new String[]{"true", "false"}) {
            Map<String, String> props = Map.of("mcp.enabled", val);
            assertThatCode(() -> Json5ConfigValidator.validate(props))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void collectsMultipleErrors() {
        Map<String, String> props = new HashMap<>();
        props.put("session.reset-mode", "weekly");
        props.put("agents.max-iterations", "-1");

        assertThatThrownBy(() -> Json5ConfigValidator.validate(props))
                .isInstanceOf(Json5ConfigValidationException.class)
                .satisfies(e -> {
                    Json5ConfigValidationException ex = (Json5ConfigValidationException) e;
                    assertThat(ex.errors()).hasSizeGreaterThanOrEqualTo(2);
                });
    }

    @Test
    void includeMetaFieldIsIgnored() {
        Map<String, String> props = Map.of(
                "$include", "base.json5"
        );

        assertThatCode(() -> Json5ConfigValidator.validate(props))
                .doesNotThrowAnyException();
    }

    @Test
    void emptyPropertiesAreValid() {
        assertThatCode(() -> Json5ConfigValidator.validate(Map.of()))
                .doesNotThrowAnyException();
    }

    @Test
    void nonNumericMaxIterationsRejected() {
        Map<String, String> props = Map.of(
                "agents.max-iterations", "abc"
        );

        assertThatThrownBy(() -> Json5ConfigValidator.validate(props))
                .isInstanceOf(Json5ConfigValidationException.class)
                .hasMessageContaining("max-iterations");
    }
}
