package biz.brumm.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChannelTest {

    @Test
    void validChannelCreation() {
        Instant now = Instant.now();
        Channel ch = new Channel("1", "Telegram", ChannelType.TELEGRAM, true, Map.of("token", "abc"), now, now);

        assertThat(ch.id()).isEqualTo("1");
        assertThat(ch.name()).isEqualTo("Telegram");
        assertThat(ch.type()).isEqualTo(ChannelType.TELEGRAM);
        assertThat(ch.enabled()).isTrue();
        assertThat(ch.config()).containsEntry("token", "abc");
    }

    @Test
    void invalidChannelWithBlankIdThrows() {
        assertThatThrownBy(() -> new Channel("", "name", ChannelType.SLACK, true, null, Instant.now(), Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ID");
    }

    @Test
    void nullConfigDefaultsToEmptyMap() {
        Channel ch = new Channel("1", "Test", ChannelType.DISCORD, false, null, Instant.now(), Instant.now());
        assertThat(ch.config()).isEmpty();
    }

    @Test
    void withEnabledReturnsNewInstance() {
        Channel ch = new Channel("1", "Test", ChannelType.TELEGRAM, true, Map.of(), Instant.now(), Instant.now());
        Channel disabled = ch.withEnabled(false);
        assertThat(disabled.enabled()).isFalse();
        assertThat(ch.enabled()).isTrue();
    }

    @Test
    void withConfigReturnsNewInstance() {
        Channel ch = new Channel("1", "Test", ChannelType.TELEGRAM, true, Map.of(), Instant.now(), Instant.now());
        Channel updated = ch.withConfig(Map.of("key", "value"));
        assertThat(updated.config()).containsEntry("key", "value");
        assertThat(ch.config()).isEmpty();
    }
}
