package biz.brumm.domain.model;

import java.time.Instant;

/**
 * Skill-Workshop-Vorschlag (OPENCLAW: Proposal, gespeichert als {@code PROPOSAL.md}).
 * <p>
 * Ein Proposal ist ein ausstehender Entwurf (Name, Beschreibung, Body), der erst durch
 * {@code apply} zu einem live Skill wird. {@code targetHash} bindet {@code UPDATE}-Proposals
 * an den aktuellen Hash der Zieldatei — ändert sich die Datei vor dem {@code apply}, wird
 * der Proposal {@code STALE}. {@code reason} trägt den Ablehnungs-/Quarantäne-Grund.
 *
 * @param id          Interne Persistenz-ID (0 solange nicht gespeichert).
 * @param proposalId  Öffentliche ID ({@code <slug>-<seq>}), z. B. {@code morning-catchup-001}.
 * @param type        {@code CREATE} oder {@code UPDATE}.
 * @param name        Ziel-Skill-Name (verzeichnis-fähig).
 * @param description Kurzbeschreibung für das Frontmatter.
 * @param content     Markdown-Body des Skills.
 * @param status      Lebenszyklus-Status.
 * @param targetHash  SHA-256 (hex) der Zieldatei bei {@code UPDATE}-Anlage; {@code null} bei {@code CREATE}.
 * @param reason      Grund für reject/quarantine.
 * @param createdAt   Zeitpunkt der Anlage.
 * @param updatedAt   Zeitpunkt der letzten Änderung.
 */
public record SkillProposal(
        long id,
        String proposalId,
        SkillProposalType type,
        String name,
        String description,
        String content,
        SkillProposalStatus status,
        String targetHash,
        String reason,
        Instant createdAt,
        Instant updatedAt) {
}