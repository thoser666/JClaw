package biz.brumm.infrastructure.adapter.out.persistence;

import biz.brumm.domain.model.GoalStatus;
import biz.brumm.domain.model.SessionGoal;
import biz.brumm.domain.port.out.GoalStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * H2-Persistenz für das Session-Ziel (Tabelle {@code session_goal}, Punkt P4-07).
 * Ein Ziel pro Session, überlebt Gateway-Neustarts.
 */
@Repository
public class H2GoalStore implements GoalStore {

    private static final String SELECT_COLUMNS =
            "session_id, objective, status, status_note, token_budget, created_at, completed_at";

    private final JdbcTemplate jdbc;

    public H2GoalStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<SessionGoal> findBySessionId(String sessionId) {
        List<SessionGoal> results = jdbc.query(
                "SELECT " + SELECT_COLUMNS + " FROM session_goal WHERE session_id = ?",
                goalRowMapper(), sessionId);
        return results.stream().findFirst();
    }

    @Override
    public SessionGoal save(SessionGoal goal) {
        int updated = jdbc.update(
                "UPDATE session_goal SET objective = ?, status = ?, status_note = ?, token_budget = ?, completed_at = ? " +
                        "WHERE session_id = ?",
                goal.objective(), goal.status().name(), goal.statusNote(), goal.tokenBudget(),
                toTimestamp(goal.completedAt()), goal.sessionId());
        if (updated == 0) {
            jdbc.update(
                    "INSERT INTO session_goal (session_id, objective, status, status_note, token_budget, created_at, completed_at) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    goal.sessionId(), goal.objective(), goal.status().name(), goal.statusNote(), goal.tokenBudget(),
                    toTimestamp(goal.createdAt()), toTimestamp(goal.completedAt()));
        }
        return goal;
    }

    @Override
    public void deleteBySessionId(String sessionId) {
        jdbc.update("DELETE FROM session_goal WHERE session_id = ?", sessionId);
    }

    private RowMapper<SessionGoal> goalRowMapper() {
        return (rs, rowNum) -> new SessionGoal(
                rs.getString("session_id"),
                rs.getString("objective"),
                GoalStatus.valueOf(rs.getString("status")),
                rs.getString("status_note"),
                (Long) rs.getObject("token_budget"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("completed_at")));
    }

    private static java.sql.Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : java.sql.Timestamp.from(instant);
    }

    private static Instant toInstant(java.sql.Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}