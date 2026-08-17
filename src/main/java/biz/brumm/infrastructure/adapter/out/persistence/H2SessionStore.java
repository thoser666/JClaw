package biz.brumm.infrastructure.adapter.out.persistence;

import biz.brumm.domain.model.Session;
import biz.brumm.domain.port.out.SessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class H2SessionStore implements SessionStore {

    private static final Logger log = LoggerFactory.getLogger(H2SessionStore.class);

    private final JdbcTemplate jdbc;

    public H2SessionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Session> findById(String sessionId) {
        List<Session> results = jdbc.query(
                "SELECT session_id, display_name, session_started_at, last_interaction_at, updated_at " +
                        "FROM session WHERE session_id = ?",
                sessionRowMapper(), sessionId);
        return results.stream().findFirst();
    }

    @Override
    public List<Session> findAll() {
        return jdbc.query(
                "SELECT session_id, display_name, session_started_at, last_interaction_at, updated_at " +
                        "FROM session ORDER BY last_interaction_at DESC",
                sessionRowMapper());
    }

    @Override
    public Session save(Session session) {
        int updated = jdbc.update(
                "UPDATE session SET display_name = ?, session_started_at = ?, last_interaction_at = ?, updated_at = ? " +
                        "WHERE session_id = ?",
                session.displayName(), toTimestamp(session.sessionStartedAt()),
                toTimestamp(session.lastInteractionAt()), toTimestamp(session.updatedAt()),
                session.sessionId());
        if (updated == 0) {
            jdbc.update(
                    "INSERT INTO session (session_id, display_name, session_started_at, last_interaction_at, updated_at) " +
                            "VALUES (?, ?, ?, ?, ?)",
                    session.sessionId(), session.displayName(),
                    toTimestamp(session.sessionStartedAt()), toTimestamp(session.lastInteractionAt()),
                    toTimestamp(session.updatedAt()));
            log.info("Neue Session erstellt: '{}'.", session.sessionId());
        }
        return session;
    }

    @Override
    public void deleteById(String sessionId) {
        int deleted = jdbc.update("DELETE FROM session WHERE session_id = ?", sessionId);
        if (deleted > 0) {
            log.info("Session '{}' gelöscht.", sessionId);
        }
    }

    private RowMapper<Session> sessionRowMapper() {
        return (rs, rowNum) -> new Session(
                rs.getString("session_id"),
                rs.getString("display_name"),
                rs.getTimestamp("session_started_at").toInstant(),
                rs.getTimestamp("last_interaction_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static java.sql.Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : java.sql.Timestamp.from(instant);
    }
}
