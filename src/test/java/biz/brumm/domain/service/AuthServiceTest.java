package biz.brumm.domain.service;

import biz.brumm.domain.model.ApiKey;
import biz.brumm.domain.port.out.ApiKeyStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AuthServiceTest {

    private ApiKeyStore apiKeyStore;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        apiKeyStore = mock(ApiKeyStore.class);
        authService = new AuthService(apiKeyStore);
    }

    @Test
    void isValidTokenReturnsTrueForValidToken() {
        String rawToken = "test-token-123";
        String hash = authService.hashToken(rawToken);
        when(apiKeyStore.findByTokenHash(hash)).thenReturn(
                Optional.of(new ApiKey("id-1", "test", hash, java.time.Instant.now())));

        assertThat(authService.isValidToken(rawToken)).isTrue();
    }

    @Test
    void isValidTokenReturnsFalseForInvalidToken() {
        when(apiKeyStore.findByTokenHash(any())).thenReturn(Optional.empty());

        assertThat(authService.isValidToken("nonexistent-token")).isFalse();
    }

    @Test
    void isValidTokenReturnsFalseForNullToken() {
        assertThat(authService.isValidToken(null)).isFalse();
    }

    @Test
    void isValidTokenReturnsFalseForBlankToken() {
        assertThat(authService.isValidToken("   ")).isFalse();
    }

    @Test
    void createApiKeyReturnsRawToken() {
        when(apiKeyStore.save(any())).thenReturn(null);

        String rawToken = authService.createApiKey("my-key");

        assertThat(rawToken).isNotEmpty();
        verify(apiKeyStore).save(argThat(key ->
                key.name().equals("my-key") && key.tokenHash() != null));
    }

    @Test
    void hashTokenProducesConsistentResults() {
        String token = "consistent-token";
        String hash1 = authService.hashToken(token);
        String hash2 = authService.hashToken(token);

        assertThat(hash1).isEqualTo(hash2);
    }

    @Test
    void hashTokenProducesDifferentResultsForDifferentTokens() {
        String hash1 = authService.hashToken("token-a");
        String hash2 = authService.hashToken("token-b");

        assertThat(hash1).isNotEqualTo(hash2);
    }
}
