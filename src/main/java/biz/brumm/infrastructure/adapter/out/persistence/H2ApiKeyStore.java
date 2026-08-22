package biz.brumm.infrastructure.adapter.out.persistence;

import biz.brumm.domain.model.ApiKey;
import biz.brumm.domain.port.out.ApiKeyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class H2ApiKeyStore implements ApiKeyStore {

    private static final Logger log = LoggerFactory.getLogger(H2ApiKeyStore.class);

    private final JdbcTemplate jdbc;

    public H2ApiKeyStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ApiKey> findByTokenHash(String tokenHash) {
        List<ApiKey> results = jdbc.query(
                "SELECT id, name, token_hash, created_at FROM api_key WHERE token_hash = ?",
                apiKeyRowMapper(), tokenHash);
        return results.stream().findFirst();
    }

    @Override
    public List<ApiKey> findAll() {
        return jdbc.query(
                "SELECT id, name, token_hash, created_at FROM api_key ORDER BY created_at DESC",
                apiKeyRowMapper());
    }

    @Override
    public ApiKey save(ApiKey apiKey) {
        int updated = jdbc.update(
                "UPDATE api_key SET name = ? WHERE id = ?",
                apiKey.name(), apiKey.id());
        if (updated == 0) {
            jdbc.update(
                    "INSERT INTO api_key (id, name, token_hash, created_at) VALUES (?, ?, ?, ?)",
                    apiKey.id(), apiKey.name(), apiKey.tokenHash(),
                    toTimestamp(apiKey.createdAt()));
            log.info("Neuer API-Key erstellt: '{}' (ID: {}).", apiKey.name(), apiKey.id());
        }
        return apiKey;
    }

    @Override
    public void deleteById(String id) {
        int deleted = jdbc.update("DELETE FROM api_key WHERE id = ?", id);
        if (deleted > 0) {
            log.info("API-Key '{}' gelöscht.", id);
        }
    }

    private RowMapper<ApiKey> apiKeyRowMapper() {
        return (rs, rowNum) -> new ApiKey(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("token_hash"),
                rs.getTimestamp("created_at").toInstant());
    }

    private static java.sql.Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : java.sql.Timestamp.from(instant);
    }
}
