package biz.brumm.infrastructure.adapter.out.persistence;

import biz.brumm.domain.model.FollowUp;
import biz.brumm.domain.port.out.FollowUpQueueStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * H2-Persistenz für die Follow-up-Queue (Tabelle {@code follow_up}, Punkt P4-07).
 * Queue-Inhalt überlebt Gateway-Neustarts; Ausführung erfolgt nur über explizites Drain.
 */
@Repository
public class H2FollowUpQueueStore implements FollowUpQueueStore {

    private static final String SELECT_COLUMNS = "id, session_id, prompt, created_at, delivered_at";

    private final JdbcTemplate jdbc;

    public H2FollowUpQueueStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public FollowUp enqueue(FollowUp followUp) {
        jdbc.update(
                "INSERT INTO follow_up (id, session_id, prompt, created_at, delivered_at) VALUES (?, ?, ?, ?, ?)",
                followUp.id(), followUp.sessionId(), followUp.prompt(),
                toTimestamp(followUp.createdAt()), toTimestamp(followUp.deliveredAt()));
        return followUp;
    }

    @Override
    public List<FollowUp> findPendingBySessionId(String sessionId) {
        return jdbc.query(
                "SELECT " + SELECT_COLUMNS + " FROM follow_up " +
                        "WHERE session_id = ? AND delivered_at IS NULL ORDER BY created_at",
                followUpRowMapper(), sessionId);
    }

    @Override
    public Optional<FollowUp> findById(String id) {
        List<FollowUp> results = jdbc.query(
                "SELECT " + SELECT_COLUMNS + " FROM follow_up WHERE id = ?",
                followUpRowMapper(), id);
        return results.stream().findFirst();
    }

    @Override
    public FollowUp markDelivered(String id, Instant deliveredAt) {
        jdbc.update("UPDATE follow_up SET delivered_at = ? WHERE id = ?", toTimestamp(deliveredAt), id);
        return findById(id).orElseThrow(() -> new IllegalStateException("Follow-Up '" + id + "' nicht gefunden."));
    }

    @Override
    public void deleteById(String id) {
        jdbc.update("DELETE FROM follow_up WHERE id = ?", id);
    }

    private RowMapper<FollowUp> followUpRowMapper() {
        return (rs, rowNum) -> new FollowUp(
                rs.getString("id"),
                rs.getString("session_id"),
                rs.getString("prompt"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("delivered_at")));
    }

    private static java.sql.Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : java.sql.Timestamp.from(instant);
    }

    private static Instant toInstant(java.sql.Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}