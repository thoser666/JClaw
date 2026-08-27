package biz.brumm.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChannelPropertiesTest {

    @Test
    void defaultValuesAreCorrect() {
        ChannelProperties props = ChannelProperties.of(false, 30);
        assertThat(props.enabled()).isFalse();
        assertThat(props.defaultTimeout()).isEqualTo(30);
    }

    @Test
    void negativeTimeoutDefaultsTo30() {
        ChannelProperties props = ChannelProperties.of(true, -5);
        assertThat(props.defaultTimeout()).isEqualTo(30);
    }

    @Test
    void enabledChannelProperties() {
        ChannelProperties props = ChannelProperties.of(true, 60);
        assertThat(props.enabled()).isTrue();
        assertThat(props.defaultTimeout()).isEqualTo(60);
    }
}
