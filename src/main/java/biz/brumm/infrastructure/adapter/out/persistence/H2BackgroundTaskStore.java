package biz.brumm.infrastructure.adapter.out.persistence;

import biz.brumm.domain.model.BackgroundTask;
import biz.brumm.domain.model.BackgroundTaskStatus;
import biz.brumm.domain.port.out.BackgroundTaskStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * H2-Persistenz für Background-Tasks (Tabelle {@code background_task}, Punkt P4-08).
 * Tasks überleben Gateway-Neustarts; verwaiste RUNNING-Tasks werden beim Start FAILED.
 */
@Repository
public class H2BackgroundTaskStore implements BackgroundTaskStore {

    private static final String SELECT_COLUMNS =
            "id, session_id, prompt, status, result, error_message, created_at, started_at, completed_at";

    private final JdbcTemplate jdbc;

    public H2BackgroundTaskStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public BackgroundTask save(BackgroundTask task) {
        int updated = jdbc.update(
                "UPDATE background_task SET status = ?, result = ?, error_message = ?, " +
                        "started_at = ?, completed_at = ? WHERE id = ?",
                task.status().name(), task.result(), task.error(),
                toTimestamp(task.startedAt()), toTimestamp(task.completedAt()), task.id());
        if (updated == 0) {
            jdbc.update(
                    "INSERT INTO background_task (id, session_id, prompt, status, result, error_message, created_at, started_at, completed_at) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    task.id(), task.sessionId(), task.prompt(), task.status().name(), task.result(), task.error(),
                    toTimestamp(task.createdAt()), toTimestamp(task.startedAt()), toTimestamp(task.completedAt()));
        }
        return task;
    }

    @Override
    public Optional<BackgroundTask> findById(String id) {
        List<BackgroundTask> results = jdbc.query(
                "SELECT " + SELECT_COLUMNS + " FROM background_task WHERE id = ?",
                taskRowMapper(), id);
        return results.stream().findFirst();
    }

    @Override
    public List<BackgroundTask> findBySessionId(String sessionId) {
        return jdbc.query(
                "SELECT " + SELECT_COLUMNS + " FROM background_task WHERE session_id = ? ORDER BY created_at DESC",
                taskRowMapper(), sessionId);
    }

    @Override
    public int markOrphanedRunningAsFailed(String message) {
        return jdbc.update(
                "UPDATE background_task SET status = 'FAILED', error_message = ?, completed_at = CURRENT_TIMESTAMP " +
                        "WHERE status = 'RUNNING'",
                message);
    }

    private RowMapper<BackgroundTask> taskRowMapper() {
        return (rs, rowNum) -> new BackgroundTask(
                rs.getString("id"),
                rs.getString("session_id"),
                rs.getString("prompt"),
                BackgroundTaskStatus.valueOf(rs.getString("status")),
                rs.getString("result"),
                rs.getString("error_message"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("started_at")),
                toInstant(rs.getTimestamp("completed_at")));
    }

    private static java.sql.Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : java.sql.Timestamp.from(instant);
    }

    private static Instant toInstant(java.sql.Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}