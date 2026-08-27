package biz.brumm.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChannelMessageTest {

    @Test
    void inboundFactoryCreatesCorrectMessage() {
        ChannelMessage msg = ChannelMessage.inbound("telegram", "ext-123", "Hello", "user1", "Max", "thread1", "sess1");

        assertThat(msg.id()).isNotBlank();
        assertThat(msg.channelId()).isEqualTo("telegram");
        assertThat(msg.externalId()).isEqualTo("ext-123");
        assertThat(msg.direction()).isEqualTo(MessageDirection.INBOUND);
        assertThat(msg.content()).isEqualTo("Hello");
        assertThat(msg.senderId()).isEqualTo("user1");
        assertThat(msg.senderName()).isEqualTo("Max");
        assertThat(msg.threadId()).isEqualTo("thread1");
        assertThat(msg.sessionId()).isEqualTo("sess1");
        assertThat(msg.timestamp()).isNotNull();
    }

    @Test
    void outboundFactoryCreatesCorrectMessage() {
        ChannelMessage msg = ChannelMessage.outbound("slack", "Response", "t-456", "sess2");

        assertThat(msg.id()).isNotBlank();
        assertThat(msg.channelId()).isEqualTo("slack");
        assertThat(msg.direction()).isEqualTo(MessageDirection.OUTBOUND);
        assertThat(msg.content()).isEqualTo("Response");
        assertThat(msg.threadId()).isEqualTo("t-456");
        assertThat(msg.sessionId()).isEqualTo("sess2");
        assertThat(msg.externalId()).isNull();
    }

    @Test
    void invalidMessageWithBlankIdThrows() {
        assertThatThrownBy(() -> new ChannelMessage("", "ch", null, MessageDirection.INBOUND, "c", null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ID");
    }

    @Test
    void nullContentDefaultsToEmpty() {
        ChannelMessage msg = new ChannelMessage("1", "ch", null, MessageDirection.INBOUND, null, null, null, null, null, null);
        assertThat(msg.content()).isEmpty();
    }

    @Test
    void nullTimestampDefaultsToNow() {
        ChannelMessage msg = new ChannelMessage("1", "ch", null, MessageDirection.INBOUND, "c", null, null, null, null, null);
        assertThat(msg.timestamp()).isNotNull();
        assertThat(msg.timestamp()).isBeforeOrEqualTo(java.time.Instant.now());
    }
}
