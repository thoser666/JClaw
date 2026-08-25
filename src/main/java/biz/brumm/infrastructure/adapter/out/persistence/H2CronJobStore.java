package biz.brumm.infrastructure.adapter.out.persistence;

import biz.brumm.domain.model.CronJob;
import biz.brumm.domain.port.out.CronJobStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class H2CronJobStore implements CronJobStore {

    private static final Logger log = LoggerFactory.getLogger(H2CronJobStore.class);

    private final JdbcTemplate jdbc;

    public H2CronJobStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<CronJob> findById(String id) {
        List<CronJob> results = jdbc.query(
                "SELECT id, name, cron_expression, prompt, context_id, enabled, last_run_at, next_run_at, created_at " +
                        "FROM cron_job WHERE id = ?",
                cronJobRowMapper(), id);
        return results.stream().findFirst();
    }

    @Override
    public List<CronJob> findAll() {
        return jdbc.query(
                "SELECT id, name, cron_expression, prompt, context_id, enabled, last_run_at, next_run_at, created_at " +
                        "FROM cron_job ORDER BY created_at",
                cronJobRowMapper());
    }

    @Override
    public List<CronJob> findEnabled() {
        return jdbc.query(
                "SELECT id, name, cron_expression, prompt, context_id, enabled, last_run_at, next_run_at, created_at " +
                        "FROM cron_job WHERE enabled = TRUE ORDER BY next_run_at",
                cronJobRowMapper());
    }

    @Override
    public CronJob save(CronJob cronJob) {
        int updated = jdbc.update(
                "UPDATE cron_job SET name = ?, cron_expression = ?, prompt = ?, context_id = ?, enabled = ?, " +
                        "last_run_at = ?, next_run_at = ? WHERE id = ?",
                cronJob.name(), cronJob.cronExpression(), cronJob.prompt(), cronJob.contextId(),
                cronJob.enabled(),
                toTimestamp(cronJob.lastRunAt()), toTimestamp(cronJob.nextRunAt()),
                cronJob.id());
        if (updated == 0) {
            jdbc.update(
                    "INSERT INTO cron_job (id, name, cron_expression, prompt, context_id, enabled, last_run_at, next_run_at, created_at) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    cronJob.id(), cronJob.name(), cronJob.cronExpression(), cronJob.prompt(),
                    cronJob.contextId(), cronJob.enabled(),
                    toTimestamp(cronJob.lastRunAt()), toTimestamp(cronJob.nextRunAt()),
                    toTimestamp(cronJob.createdAt()));
            log.info("Neuen CronJob erstellt: '{}' (id={}).", cronJob.name(), cronJob.id());
        } else {
            log.info("CronJob '{}' aktualisiert.", cronJob.name());
        }
        return cronJob;
    }

    @Override
    public void deleteById(String id) {
        int deleted = jdbc.update("DELETE FROM cron_job WHERE id = ?", id);
        if (deleted > 0) {
            log.info("CronJob '{}' gelöscht.", id);
        }
    }

    private RowMapper<CronJob> cronJobRowMapper() {
        return (rs, rowNum) -> new CronJob(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("cron_expression"),
                rs.getString("prompt"),
                rs.getString("context_id"),
                rs.getBoolean("enabled"),
                toInstant(rs.getTimestamp("last_run_at")),
                toInstant(rs.getTimestamp("next_run_at")),
                toInstant(rs.getTimestamp("created_at")));
    }

    private static java.sql.Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : java.sql.Timestamp.from(instant);
    }

    private static Instant toInstant(java.sql.Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
