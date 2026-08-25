package biz.brumm.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CronPropertiesTest {

    @Test
    void defaultValuesAreCorrect() {
        CronProperties props = CronProperties.of(false, 60, 3);

        assertThat(props.enabled()).isFalse();
        assertThat(props.interval()).isEqualTo(60);
        assertThat(props.maxRetries()).isEqualTo(3);
    }

    @Test
    void negativeIntervalDefaultsTo60() {
        CronProperties props = CronProperties.of(true, -1, 3);

        assertThat(props.interval()).isEqualTo(60);
    }

    @Test
    void negativeMaxRetriesDefaultsTo0() {
        CronProperties props = CronProperties.of(true, 60, -5);

        assertThat(props.maxRetries()).isEqualTo(0);
    }

    @Test
    void enabledCronProperties() {
        CronProperties props = CronProperties.of(true, 30, 5);

        assertThat(props.enabled()).isTrue();
        assertThat(props.interval()).isEqualTo(30);
        assertThat(props.maxRetries()).isEqualTo(5);
    }
}
