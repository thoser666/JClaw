package biz.brumm.domain.service;

import biz.brumm.config.GuardrailProperties;
import biz.brumm.domain.model.Channel;
import biz.brumm.domain.model.ChannelType;
import biz.brumm.domain.port.out.ChannelStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CredentialLeakGuardTest {

    @Mock
    private ChannelStore channelStore;

    private CredentialLeakGuard guard(boolean enabled, List<String> secrets) {
        return new CredentialLeakGuard(new GuardrailProperties(enabled, secrets), channelStore);
    }

    @Test
    void redactsConfiguredSecrets() {
        CredentialLeakGuard guard = guard(true, List.of("TOPSECRET"));

        assertThat(guard.redact("Token ist TOPSECRET, nicht weitergeben"))
                .isEqualTo("Token ist [REDACTED], nicht weitergeben");
        assertThat(guard.redact(null)).isNull();
    }

    @Test
    void redactsSecretsFromChannelConfigSecretKeys() {
        when(channelStore.findAllChannels()).thenReturn(List.of(channelWithConfig("token", "CHANNELTOKEN")));
        CredentialLeakGuard guard = guard(true, List.of());

        assertThat(guard.redact("CHANNELTOKEN taucht hier auf"))
                .isEqualTo("[REDACTED] taucht hier auf");
    }

    @Test
    void keepsNonSensitiveConfigInSanitizedCopy() {
        when(channelStore.findAllChannels()).thenReturn(List.of(channelWithConfig("token", "CHANNELTOKEN")));
        CredentialLeakGuard guard = guard(true, List.of("TOPSECRET"));

        Map<String, Object> cleaned = guard.sanitizeConfig(Map.of(
                "token", "CHANNELTOKEN",
                "channel", "#general",
                "note", "mein TOPSECRET hier"));

        assertThat(cleaned).containsEntry("token", "[REDACTED]");
        assertThat(cleaned).containsEntry("channel", "#general");
        assertThat(cleaned).containsEntry("note", "mein [REDACTED] hier");
    }

    @Test
    void disabledGuardReturnsTextUnchanged() {
        CredentialLeakGuard guard = guard(false, List.of("TOPSECRET"));

        assertThat(guard.redact("TOPSECRET bleibt sichtbar")).isEqualTo("TOPSECRET bleibt sichtbar");
        assertThat(guard.sanitizeConfig(Map.of("token", "abc"))).containsEntry("token", "abc");
    }

    private static Channel channelWithConfig(String key, String value) {
        return new Channel("ch-1", "Telegram Bot", ChannelType.TELEGRAM, true,
                Map.of(key, value), Instant.now(), Instant.now());
    }
}