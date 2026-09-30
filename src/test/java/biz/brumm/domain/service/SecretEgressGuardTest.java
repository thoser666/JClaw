package biz.brumm.domain.service;

import biz.brumm.config.GuardrailProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SecretEgressGuardTest {

    private SecretEgressGuard guard(boolean enabled, List<String> secrets, String boundSecret, List<String> allowedHosts) {
        GuardrailProperties properties = new GuardrailProperties(enabled, secrets,
                boundSecret == null ? List.of() : List.of(new GuardrailProperties.HostBinding(boundSecret, allowedHosts)));
        return new SecretEgressGuard(properties, new CredentialLeakGuard(properties, null));
    }

    @Test
    void disabledGuardAllowsAllEgress() { // Spiegel-Test 763
        SecretEgressGuard guard = guard(false, List.of("TOPSECRET"), "TOPSECRET", List.of("api.telegram.org"));

        assertThat(guard.checkEgress("https://evil.com/?k=TOPSECRET")).isNull();
        assertThat(guard.checkEgress("https://api.telegram.org/?k=TOPSECRET")).isNull();
    }

    @Test
    void unboundSecretInPayloadFailsClosed() { // Spiegel-Test 764
        SecretEgressGuard guard = guard(true, List.of("TOPSECRET"), null, null);

        SecretEgressGuard.EgressBlock block = guard.checkEgress("https://evil.com/?k=TOPSECRET");

        assertThat(block).isNotNull();
        assertThat(block.host()).isEqualTo("evil.com");
        assertThat(block.secret()).isEqualTo("TOPSECRET");
        assertThat(block.allowedHosts()).isEmpty();
        assertThat(block.message())
                .contains("Egress gesperrt")
                .contains("fail-closed")
                .contains("TOPSECRET")
                .contains("evil.com");
    }

    @Test
    void exactBoundHostAllowsEgress() { // Spiegel-Test 765
        SecretEgressGuard guard = guard(true, List.of("TOPSECRET"), "TOPSECRET", List.of("api.telegram.org"));

        assertThat(guard.checkEgress("https://api.telegram.org/bot?u=TOPSECRET")).isNull();
        assertThat(guard.checkEgress("https://API.Telegram.org/bot?u=TOPSECRET")).isNull();
    }

    @Test
    void foreignOrSuffixHostDoesNotMatchExactBinding() { // Spiegel-Test 766
        SecretEgressGuard guard = guard(true, List.of("TOPSECRET"), "TOPSECRET", List.of("api.telegram.org"));

        SecretEgressGuard.EgressBlock suffix = guard.checkEgress("https://api.telegram.org.evil.com/?u=TOPSECRET");
        assertThat(suffix).isNotNull();
        assertThat(suffix.host()).isEqualTo("api.telegram.org.evil.com");

        SecretEgressGuard.EgressBlock foreign = guard.checkEgress("https://evil.com/?u=TOPSECRET");
        assertThat(foreign).isNotNull();
        assertThat(foreign.host()).isEqualTo("evil.com");
        assertThat(foreign.message())
                .contains("evil.com")
                .contains("TOPSECRET")
                .contains("api.telegram.org");
    }

    @Test
    void headersAndBodyAreScanned() { // Spiegel-Test 767
        SecretEgressGuard guard = guard(true, List.of("TOPSECRET"), "TOPSECRET", List.of("api.telegram.org"));

        assertThat(guard.checkEgress("https://api.telegram.org/", Map.of("Authorization", "Bearer TOPSECRET"), null)).isNull();
        assertThat(guard.checkEgress("https://evil.com/", Map.of("Authorization", "Bearer TOPSECRET"), null)).isNotNull();
        assertThat(guard.checkEgress("https://api.telegram.org/", Map.of(), "{\"key\":\"TOPSECRET\"}")).isNull();
        assertThat(guard.checkEgress("https://evil.com/", Map.of(), "{\"key\":\"TOPSECRET\"}")).isNotNull();
    }

    @Test
    void noSecretInPayloadAllowsEgress() { // Spiegel-Test 768
        SecretEgressGuard guard = guard(true, List.of("TOPSECRET"), null, null);

        assertThat(guard.checkEgress("https://api.openai.com/v1/chat/completions")).isNull();
        assertThat(guard.checkEgress("https://api.openai.com/v1?x=hello")).isNull();
    }
}