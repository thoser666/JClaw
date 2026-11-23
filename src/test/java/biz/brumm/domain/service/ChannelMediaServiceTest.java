package biz.brumm.domain.service;

import biz.brumm.config.GuardrailProperties;
import biz.brumm.domain.model.Channel;
import biz.brumm.domain.model.ChannelMessage;
import biz.brumm.domain.model.ChannelType;
import biz.brumm.domain.model.MediaAttachment;
import biz.brumm.domain.port.out.ChannelAdapter;
import biz.brumm.domain.port.out.ChannelMediaAdapter;
import biz.brumm.domain.port.out.ChannelStore;
import biz.brumm.domain.port.out.PluginHookDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChannelMediaServiceTest {

    @Mock
    private ChannelStore channelStore;

    @Mock
    private ChannelMediaAdapter telegramMediaAdapter;

    private ChannelService service;

    private Channel telegram;

    @BeforeEach
    void setUp() {
        when(telegramMediaAdapter.channelType()).thenReturn(ChannelType.TELEGRAM);
        service = new ChannelService(channelStore, List.of(telegramMediaAdapter), guardDisabled(),
                PluginHookDispatcher.noop());
        telegram = new Channel("ch-1", "Telegram", ChannelType.TELEGRAM, true,
                Map.of("token", "abc"), Instant.now(), Instant.now());
    }

    private static CredentialLeakGuard guardDisabled() {
        return new CredentialLeakGuard(new GuardrailProperties(false, List.of(), List.of()), null);
    }

    @Test
    void sendMediaDelegatesToAdapterAndStoresMessage() throws Exception {
        ChannelMessage sent = ChannelMessage.outbound("ch-1", "cap", "42", "s1")
                .withExternalId("e-1");
        when(telegramMediaAdapter.supportsMedia()).thenReturn(true);
        when(telegramMediaAdapter.isAvailable(any())).thenReturn(true);
        when(telegramMediaAdapter.sendMedia(any(), any(), any())).thenReturn(sent);

        MediaAttachment attachment = new MediaAttachment(MediaAttachment.MediaKind.AUDIO,
                "audio/ogg", "n.ogg", new byte[] {1});

        ChannelMessage result = service.sendMedia(telegram, "cap", "42", "s1", attachment);

        assertThat(result.externalId()).isEqualTo("e-1");
        verify(telegramMediaAdapter).sendMedia(any(), any(), any());
        verify(channelStore).saveMessage(result);
    }

    @Test
    void rejectsAdapterWithoutMediaSupport() throws Exception {
        when(telegramMediaAdapter.supportsMedia()).thenReturn(false);

        MediaAttachment attachment = new MediaAttachment(MediaAttachment.MediaKind.IMAGE,
                "image/png", "p.png", new byte[] {1});

        assertThatThrownBy(() -> service.sendMedia(telegram, "cap", "42", "s1", attachment))
                .isInstanceOf(ChannelAdapter.ChannelException.class)
                .hasMessageContaining("Medien-Nachrichten");

        verify(telegramMediaAdapter, never()).sendMedia(any(), any(), any());
    }

    @Test
    void rejectsAdapterThatIsNotMediaCapable() throws Exception {
        ChannelAdapter plainOnly = mock(ChannelAdapter.class);
        when(plainOnly.channelType()).thenReturn(ChannelType.DISCORD);
        ChannelService plainService = new ChannelService(channelStore, List.of(plainOnly),
                guardDisabled(), PluginHookDispatcher.noop());
        Channel discord = new Channel("ch-2", "Discord", ChannelType.DISCORD, true,
                Map.of("token", "abc"), Instant.now(), Instant.now());

        assertThatThrownBy(() -> plainService.sendMedia(discord, "cap", "1", "s1",
                new MediaAttachment(MediaAttachment.MediaKind.DOCUMENT, "text/plain", "a.txt", new byte[] {1})))
                .isInstanceOf(ChannelAdapter.ChannelException.class)
                .hasMessageContaining("Medien-Nachrichten");
    }

    @Test
    void rejectsEmptyAttachment() {
        assertThatThrownBy(() -> service.sendMedia(telegram, "cap", "42", "s1",
                new MediaAttachment(MediaAttachment.MediaKind.AUDIO, "audio/ogg", "n.ogg", new byte[0])))
                .isInstanceOf(ChannelAdapter.ChannelException.class)
                .hasMessageContaining("leer");

        assertThatThrownBy(() -> service.sendMedia(telegram, "cap", "42", "s1", null))
                .isInstanceOf(ChannelAdapter.ChannelException.class)
                .hasMessageContaining("leer");
    }

    @Test
    void rejectsUnavailableChannel() throws Exception {
        when(telegramMediaAdapter.supportsMedia()).thenReturn(true);
        when(telegramMediaAdapter.isAvailable(any())).thenReturn(false);

        assertThatThrownBy(() -> service.sendMedia(telegram, "cap", "42", "s1",
                new MediaAttachment(MediaAttachment.MediaKind.IMAGE, "image/png", "p.png", new byte[] {1})))
                .isInstanceOf(ChannelAdapter.ChannelException.class)
                .hasMessageContaining("nicht verfuegbar");
    }
}