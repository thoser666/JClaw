package biz.brumm.infrastructure.adapter.out.persistence;

import biz.brumm.domain.model.SkillProposal;
import biz.brumm.domain.model.SkillProposalStatus;
import biz.brumm.domain.model.SkillProposalType;
import biz.brumm.domain.port.out.SkillProposalStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * H2-Persistenz für Skill-Workshop-Proposals (Tabelle {@code skill_proposal}, Punkt P4-06).
 * Proposals überleben Gateway-Neustarts; der Status wird per UPDATE-then-INSERT geschrieben.
 */
@Repository
public class H2SkillProposalStore implements SkillProposalStore {

    private static final Set<SkillProposalStatus> ACTIVE_STATUSES = Set.of(
            SkillProposalStatus.PENDING, SkillProposalStatus.QUARANTINED);

    private static final String COLUMNS =
            "id, proposal_id, type, name, description, content, status, target_hash, reason, created_at, updated_at";

    private final JdbcTemplate jdbc;

    public H2SkillProposalStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public SkillProposal save(SkillProposal proposal) {
        if (proposal.id() > 0) {
            jdbc.update(
                    "UPDATE skill_proposal SET description = ?, content = ?, status = ?, target_hash = ?, "
                            + "reason = ?, updated_at = ? WHERE id = ?",
                    proposal.description(), proposal.content(), proposal.status().name(), proposal.targetHash(),
                    proposal.reason(), toTimestamp(proposal.updatedAt()), proposal.id());
        } else {
            jdbc.update(
                    "INSERT INTO skill_proposal (proposal_id, type, name, description, content, status, "
                            + "target_hash, reason, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    proposal.proposalId(), proposal.type().name(), proposal.name(), proposal.description(),
                    proposal.content(), proposal.status().name(), proposal.targetHash(), proposal.reason(),
                    toTimestamp(proposal.createdAt()), toTimestamp(proposal.updatedAt()));
        }
        return findByProposalId(proposal.proposalId()).orElseThrow(
                () -> new IllegalStateException("Skill-Proposal nach Speichern nicht lesbar: " + proposal.proposalId()));
    }

    @Override
    public Optional<SkillProposal> findByProposalId(String proposalId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM skill_proposal WHERE proposal_id = ?",
                        proposalRowMapper(), proposalId)
                .stream().findFirst();
    }

    @Override
    public List<SkillProposal> findAll() {
        return jdbc.query("SELECT " + COLUMNS + " FROM skill_proposal ORDER BY created_at, id",
                proposalRowMapper());
    }

    @Override
    public long countByName(String name) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM skill_proposal WHERE name = ?", Long.class, name);
        return count == null ? 0 : count;
    }

    @Override
    public long countActive() {
        String placeholders = String.join(",", ACTIVE_STATUSES.stream().map(s -> "?").toList());
        String sql = "SELECT COUNT(*) FROM skill_proposal WHERE status IN (" + placeholders + ")" ;
        Long count = jdbc.queryForObject(sql, Long.class, ACTIVE_STATUSES.stream().map(Enum::name).toArray());
        return count == null ? 0 : count;
    }

    private RowMapper<SkillProposal> proposalRowMapper() {
        return (rs, rowNum) -> new SkillProposal(
                rs.getLong("id"),
                rs.getString("proposal_id"),
                SkillProposalType.valueOf(rs.getString("type")),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("content"),
                SkillProposalStatus.valueOf(rs.getString("status")),
                rs.getString("target_hash"),
                rs.getString("reason"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")));
    }

    private static java.sql.Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : java.sql.Timestamp.from(instant);
    }

    private static Instant toInstant(java.sql.Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}