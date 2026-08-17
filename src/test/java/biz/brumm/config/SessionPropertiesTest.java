package biz.brumm.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionPropertiesTest {

    @Test
    void acceptsValidValues() {
        assertThatCode(() -> new SessionProperties("daily", 4, 60)).doesNotThrowAnyException();
    }

    @Test
    void acceptsIdleMode() {
        assertThatCode(() -> new SessionProperties("idle", 0, 30)).doesNotThrowAnyException();
    }

    @Test
    void defaultsNoneWhenNull() {
        SessionProperties props = new SessionProperties(null, 4, 60);
        assertThat(props.resetMode()).isEqualTo("none");
    }

    @Test
    void rejectsInvalidMode() {
        assertThatThrownBy(() -> new SessionProperties("weekly", 4, 60))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reset-mode");
    }

    @Test
    void rejectsInvalidHour() {
        assertThatThrownBy(() -> new SessionProperties("daily", 25, 60))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reset-at-hour");
    }

    @Test
    void rejectsNegativeIdleMinutes() {
        assertThatThrownBy(() -> new SessionProperties("idle", 4, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reset-idle-minutes");
    }
}
