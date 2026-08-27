package biz.brumm.domain.service;

import biz.brumm.domain.model.*;
import biz.brumm.domain.port.out.ChannelAdapter;
import biz.brumm.domain.port.out.ChannelStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChannelServiceTest {

    @Mock
    private ChannelStore channelStore;

    @Mock
    private ChannelAdapter telegramAdapter;

    private ChannelService channelService;

    private Channel telegramChannel;

    @BeforeEach
    void setUp() {
        when(telegramAdapter.channelType()).thenReturn(ChannelType.TELEGRAM);
        channelService = new ChannelService(channelStore, List.of(telegramAdapter));
        telegramChannel = new Channel("ch-1", "Telegram Bot", ChannelType.TELEGRAM, true,
                Map.of("token", "abc"), Instant.now(), Instant.now());
    }

    @Test
    void findAllReturnsChannels() {
        when(channelStore.findAllChannels()).thenReturn(List.of(telegramChannel));
        assertThat(channelService.findAll()).hasSize(1);
    }

    @Test
    void findByIdReturnsChannel() {
        when(channelStore.findChannelById("ch-1")).thenReturn(Optional.of(telegramChannel));
        assertThat(channelService.findById("ch-1")).isPresent();
        assertThat(channelService.findById("ch-1").get().name()).isEqualTo("Telegram Bot");
    }

    @Test
    void saveDelegatesToStore() {
        when(channelStore.saveChannel(any())).thenReturn(telegramChannel);
        Channel saved = channelService.save(telegramChannel);
        verify(channelStore).saveChannel(telegramChannel);
        assertThat(saved).isEqualTo(telegramChannel);
    }

    @Test
    void deleteDelegatesToStore() {
        channelService.delete("ch-1");
        verify(channelStore).deleteChannelById("ch-1");
    }

    @Test
    void getAdapterReturnsTelegramAdapter() {
        assertThat(channelService.getAdapter(ChannelType.TELEGRAM)).isPresent();
        assertThat(channelService.getAdapter(ChannelType.SLACK)).isEmpty();
    }

    @Test
    void availableAdapterTypesContainsTelegram() {
        assertThat(channelService.availableAdapterTypes()).contains(ChannelType.TELEGRAM);
    }

    @Test
    void sendCallsAdapterAndSavesMessage() throws ChannelAdapter.ChannelException {
        ChannelMessage sent = ChannelMessage.outbound("ch-1", "Hello", null, "sess-1");
        when(telegramAdapter.isAvailable(telegramChannel)).thenReturn(true);
        when(telegramAdapter.send(any(), any())).thenReturn(sent);
        when(channelStore.saveMessage(any())).thenReturn(sent);

        ChannelMessage result = channelService.send(telegramChannel, "Hello", null, "sess-1");

        assertThat(result.content()).isEqualTo("Hello");
        verify(telegramAdapter).send(eq(telegramChannel), any());
        verify(channelStore).saveMessage(sent);
    }

    @Test
    void sendThrowsWhenAdapterUnavailable() {
        when(telegramAdapter.isAvailable(telegramChannel)).thenReturn(false);

        assertThatThrownBy(() -> channelService.send(telegramChannel, "Hello", null, "sess-1"))
                .isInstanceOf(ChannelAdapter.ChannelException.class)
                .hasMessageContaining("nicht verfuegbar");
    }

    @Test
    void sendThrowsWhenNoAdapterRegistered() {
        Channel slackChannel = new Channel("ch-2", "Slack", ChannelType.SLACK, true, Map.of(), Instant.now(), Instant.now());

        assertThatThrownBy(() -> channelService.send(slackChannel, "Hello", null, "sess-1"))
                .isInstanceOf(ChannelAdapter.ChannelException.class)
                .hasMessageContaining("Kein Adapter");
    }

    @Test
    void handleInboundSavesMessage() {
        ChannelMessage msg = ChannelMessage.inbound("ch-1", "ext-1", "Hi", "u1", "Max", null, "sess-1");
        channelService.handleInbound(msg);
        verify(channelStore).saveMessage(msg);
    }

    @Test
    void createBindingSavesBinding() {
        when(channelStore.saveBinding(any())).thenAnswer(inv -> inv.getArgument(0));
        ChannelBinding binding = channelService.createBinding("ch-1", "ext-1", "sess-1", BindingType.DM);
        assertThat(binding.channelId()).isEqualTo("ch-1");
        assertThat(binding.externalId()).isEqualTo("ext-1");
        verify(channelStore).saveBinding(any());
    }
}
