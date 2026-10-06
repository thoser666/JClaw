package biz.brumm.domain.port.out;

import biz.brumm.domain.model.SkillProposal;

import java.util.List;
import java.util.Optional;

/**
 * Persistenz für Skill-Workshop-Proposals (Tabelle {@code skill_proposal}).
 * UPDATE-then-INSERT: {@code proposeCreate} kommt mit id {@code 0} (INSERT), Lifecycle-
 * Übergänge mit gesetzter id (UPDATE). Persistenz überlebt Gateway-Neustarts.
 */
public interface SkillProposalStore {

    SkillProposal save(SkillProposal proposal);

    Optional<SkillProposal> findByProposalId(String proposalId);

    /** Alle Proposals, älteste zuerst. */
    List<SkillProposal> findAll();

    /** Anzahl aller Proposals für einen Skill-Namen (für die {@code <slug>-<seq>}-ID-Vergabe). */
    long countByName(String name);

    /** Anzahl PENDING + QUARANTINED (gegen {@code maxPending}). */
    long countActive();
}