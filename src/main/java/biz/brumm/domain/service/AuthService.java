package biz.brumm.domain.service;

import biz.brumm.domain.model.ApiKey;
import biz.brumm.domain.port.out.ApiKeyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final ApiKeyStore apiKeyStore;

    public AuthService(ApiKeyStore apiKeyStore) {
        this.apiKeyStore = apiKeyStore;
    }

    public boolean isValidToken(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        String hash = hashToken(token);
        return apiKeyStore.findByTokenHash(hash).isPresent();
    }

    public Optional<ApiKey> findApiKeyByToken(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String hash = hashToken(token);
        return apiKeyStore.findByTokenHash(hash);
    }

    public List<ApiKey> listApiKeys() {
        return apiKeyStore.findAll();
    }

    /**
     * Erstellt einen neuen API-Key und gibt das rohe Token-Format zurück.
     * Das Token wird nur einmalig angezeigt und kann danach nicht wieder abgerufen werden.
     *
     * @param name Bezeichnung für den API-Key
     * @return Das rohe Token (Base64URL)
     */
    public String createApiKey(String name) {
        String rawToken = generateToken();
        String hash = hashToken(rawToken);
        ApiKey apiKey = new ApiKey(UUID.randomUUID().toString(), name, hash, Instant.now());
        apiKeyStore.save(apiKey);
        log.info("API-Key '{}' erstellt.", name);
        return rawToken;
    }

    public boolean deleteApiKey(String id) {
        apiKeyStore.deleteById(id);
        return true;
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 nicht verfügbar", e);
        }
    }
}
