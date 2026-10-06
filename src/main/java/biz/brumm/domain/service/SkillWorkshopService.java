package biz.brumm.domain.service;

import biz.brumm.config.SkillWorkshopProperties;
import biz.brumm.domain.model.ApprovalPolicy;
import biz.brumm.domain.model.Skill;
import biz.brumm.domain.model.SkillProposal;
import biz.brumm.domain.model.SkillProposalStatus;
import biz.brumm.domain.model.SkillProposalType;
import biz.brumm.domain.model.SkillWorkshopConfig;
import biz.brumm.domain.port.out.SkillProposalStore;
import biz.brumm.domain.port.out.SkillWorkshopWriter;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

import static biz.brumm.domain.service.SkillWorkshopException.Kind.APPROVAL_REQUIRED;
import static biz.brumm.domain.service.SkillWorkshopException.Kind.CONFLICT;
import static biz.brumm.domain.service.SkillWorkshopException.Kind.NOT_FOUND;

/**
 * Skill-Workshop (OpenClaw {@code skills.workshop.*}: propose-create/update, list, inspect,
 * revise, apply/reject/quarantine, P4-06).
 * <p>
 * Regeln (OpenClaw-Lebenszyklus): nur {@code PENDING}-Proposals dürfen revidiert, angewendet,
 * abgelehnt oder quarantäniert werden. {@code CREATE} ist No-Clobber (Ziel darf nicht existieren);
 * {@code UPDATE} bindet an den aktuellen Ziel-Hash und wird {@code STALE}, wenn sich die Zieldatei
 * vor dem {@code apply} ändert. {@code maxPending} deckelt PENDING + QUARANTINED;
 * {@code approvalPolicy: "pending"} verlangt bei agent-initiierten Lifecycle-Aktionen
 * Operator-Freigabe (REST-Aufruf = die Freigabe).
 */
@Service
public class SkillWorkshopService {

    private static final String SLUG_FALLBACK = "skill";

    private final SkillProposalStore store;
    private final SkillWorkshopWriter writer;
    private final SkillWorkshopProperties properties;

    public SkillWorkshopService(SkillProposalStore store, SkillWorkshopWriter writer,
                                SkillWorkshopProperties properties) {
        this.store = store;
        this.writer = writer;
        this.properties = properties;
    }

    public SkillWorkshopConfig config() {
        return new SkillWorkshopConfig(properties.approvalPolicy(), properties.maxPending(),
                properties.maxSkillBytes(), properties.autonomous().enabled());
    }

    public SkillProposal proposeCreate(String name, String description, String content) {
        validate(name, description, content);
        if (writer.exists(name)) {
            throw new SkillWorkshopException("Skill '" + name + "' existiert bereits (No-Clobber).", CONFLICT);
        }
        requireCapacity();
        SkillProposal proposal = newProposal(name, description, content, SkillProposalType.CREATE, null, null);
        return store.save(proposal);
    }

    public SkillProposal proposeUpdate(String name, String description, String content) {
        validate(name, description, content);
        String targetHash = writer.contentHash(name);
        if (targetHash.isEmpty()) {
            throw new SkillWorkshopException("Skill '" + name + "' existiert nicht im Workspace.", NOT_FOUND);
        }
        requireCapacity();
        return store.save(newProposal(name, description, content, SkillProposalType.UPDATE, targetHash, null));
    }

    public List<SkillProposal> listProposals() {
        return store.findAll();
    }

    public SkillProposal inspect(String proposalId) {
        return requireProposal(proposalId);
    }

    public SkillProposal revise(String proposalId, String description, String content) {
        SkillProposal proposal = requireProposal(proposalId);
        requirePending(proposal);
        validate(proposal.name(), description, content);
        SkillProposal revised = updated(proposal, description, content, SkillProposalStatus.PENDING,
                proposal.targetHash(), null);
        return store.save(revised);
    }

    public SkillProposal apply(String proposalId, boolean agentInitiated) {
        SkillProposal proposal = requireProposal(proposalId);
        requireApproval(agentInitiated);
        requirePending(proposal);
        if (isStale(proposal)) {
            store.save(updated(proposal, proposal.description(), proposal.content(),
                    SkillProposalStatus.STALE, proposal.targetHash(), proposal.reason()));
            throw new SkillWorkshopException("Vorschlag '" + proposalId + "' ist '" + SkillProposalStatus.STALE
                    + "' — das Ziel hat sich seit der Anlage geändert.", CONFLICT);
        }
        writer.write(new Skill(proposal.name(), proposal.description(), proposal.content(), null));
        return store.save(updated(proposal, proposal.description(), proposal.content(),
                SkillProposalStatus.APPLIED, proposal.targetHash(), null));
    }

    public SkillProposal reject(String proposalId, String reason, boolean agentInitiated) {
        SkillProposal proposal = requireProposal(proposalId);
        requireApproval(agentInitiated);
        requirePending(proposal);
        return store.save(updated(proposal, proposal.description(), proposal.content(),
                SkillProposalStatus.REJECTED, proposal.targetHash(), reason));
    }

    public SkillProposal quarantine(String proposalId, String reason, boolean agentInitiated) {
        SkillProposal proposal = requireProposal(proposalId);
        requireApproval(agentInitiated);
        requirePending(proposal);
        return store.save(updated(proposal, proposal.description(), proposal.content(),
                SkillProposalStatus.QUARANTINED, proposal.targetHash(), reason));
    }

    private boolean isStale(SkillProposal proposal) {
        if (proposal.type() == SkillProposalType.CREATE) {
            return writer.exists(proposal.name());
        }
        String liveHash = writer.contentHash(proposal.name());
        return liveHash.isEmpty() || !liveHash.equals(proposal.targetHash());
    }

    private SkillProposal newProposal(String name, String description, String content,
                                      SkillProposalType type, String targetHash, String reason) {
        Instant now = Instant.now();
        return new SkillProposal(0, nextProposalId(name), type, name, description, content,
                SkillProposalStatus.PENDING, targetHash, reason, now, now);
    }

    private SkillProposal updated(SkillProposal p, String description, String content,
                                  SkillProposalStatus status, String targetHash, String reason) {
        return new SkillProposal(p.id(), p.proposalId(), p.type(), p.name(), description, content,
                status, targetHash, reason, p.createdAt(), Instant.now());
    }

    private String nextProposalId(String name) {
        long seq = store.countByName(name) + 1;
        return slug(name) + "-" + String.format("%03d", seq);
    }

    static String slug(String name) {
        String slug = name.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return slug.isEmpty() ? SLUG_FALLBACK : slug;
    }

    private SkillProposal requireProposal(String proposalId) {
        return store.findByProposalId(proposalId)
                .orElseThrow(() -> new SkillWorkshopException("Vorschlag '" + proposalId + "' existiert nicht.", NOT_FOUND));
    }

    private void requirePending(SkillProposal proposal) {
        if (proposal.status() != SkillProposalStatus.PENDING) {
            throw new SkillWorkshopException("Nur " + SkillProposalStatus.PENDING + "-Vorschläge können bearbeitet "
                    + "werden (aktuell: " + proposal.status() + ").", CONFLICT);
        }
    }

    private void requireApproval(boolean agentInitiated) {
        if (agentInitiated && properties.approvalPolicy() == ApprovalPolicy.PENDING) {
            throw new SkillWorkshopException("Agent-initiierte Lifecycle-Aktion erfordert bei "
                    + "approvalPolicy \"pending\" die Operator-Freigabe.", APPROVAL_REQUIRED);
        }
    }

    private void requireCapacity() {
        long active = store.countActive();
        if (active >= properties.maxPending()) {
            throw new SkillWorkshopException("Workshop-Vorschlagslimit maxPending (" + properties.maxPending()
                    + ") erreicht.", CONFLICT);
        }
    }

    private void validate(String name, String description, String content) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name ist erforderlich.");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("description ist erforderlich.");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content ist erforderlich.");
        }
        int bytes = content.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > properties.maxSkillBytes()) {
            throw new IllegalArgumentException("content überschreitet maxSkillBytes ("
                    + properties.maxSkillBytes() + " Bytes).");
        }
    }
}