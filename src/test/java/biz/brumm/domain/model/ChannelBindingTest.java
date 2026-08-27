package biz.brumm.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChannelBindingTest {

    @Test
    void validBindingCreation() {
        ChannelBinding b = ChannelBinding.of("1", "telegram", "ext-123", "sess-1", BindingType.DM);
        assertThat(b.id()).isEqualTo("1");
        assertThat(b.channelId()).isEqualTo("telegram");
        assertThat(b.externalId()).isEqualTo("ext-123");
        assertThat(b.sessionId()).isEqualTo("sess-1");
        assertThat(b.bindingType()).isEqualTo(BindingType.DM);
        assertThat(b.createdAt()).isNotNull();
    }

    @Test
    void invalidBindingWithBlankIdThrows() {
        assertThatThrownBy(() -> new ChannelBinding("", "ch", "ext", "sess", BindingType.DM, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ID");
    }

    @Test
    void invalidBindingWithBlankExternalIdThrows() {
        assertThatThrownBy(() -> new ChannelBinding("1", "ch", "", "sess", BindingType.THREAD, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Externe ID");
    }

    @Test
    void threadBindingCreation() {
        ChannelBinding b = ChannelBinding.of("2", "slack", "ext-456", "sess-2", BindingType.THREAD);
        assertThat(b.bindingType()).isEqualTo(BindingType.THREAD);
    }
}
