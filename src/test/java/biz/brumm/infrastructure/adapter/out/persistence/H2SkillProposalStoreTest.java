package biz.brumm.infrastructure.adapter.out.persistence;

import biz.brumm.domain.model.SkillProposal;
import biz.brumm.domain.model.SkillProposalStatus;
import biz.brumm.domain.model.SkillProposalType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class H2SkillProposalStoreTest {

    @Autowired
    private H2SkillProposalStore store;

    @Test
    void saveInsertsAndFindsByProposalId() {
        SkillProposal proposal = proposal("morning-catchup-001", "morning-catchup",
                SkillProposalType.CREATE, SkillProposalStatus.PENDING, null);

        SkillProposal saved = store.save(proposal);

        assertThat(saved.id()).isPositive();
        Optional<SkillProposal> found = store.findByProposalId("morning-catchup-001");
        assertThat(found).isPresent();
        assertThat(found.get().id()).isPositive();
        assertThat(found.get().name()).isEqualTo("morning-catchup");
        assertThat(found.get().status()).isEqualTo(SkillProposalStatus.PENDING);
    }

    @Test
    void saveUpdatesExistingProposal() {
        SkillProposal saved = store.save(proposal("qa-check-001", "qa-check",
                SkillProposalType.UPDATE, SkillProposalStatus.PENDING, "hash-1"));

        SkillProposal updated = new SkillProposal(saved.id(), saved.proposalId(), saved.type(), saved.name(),
                saved.description(), saved.content(), SkillProposalStatus.APPLIED, "hash-1", null,
                saved.createdAt(), Instant.now());
        store.save(updated);

        SkillProposal found = store.findByProposalId("qa-check-001").orElseThrow();
        assertThat(found.status()).isEqualTo(SkillProposalStatus.APPLIED);
    }

    @Test
    void findByProposalIdReturnsEmptyForMissing() {
        assertThat(store.findByProposalId("nicht-da")).isEmpty();
    }

    @Test
    void findAllReturnsProposalsOldestFirst() {
        Instant base = Instant.parse("2026-09-01T08:00:00Z");
        store.save(proposalAt("a-001", "a", base.plusSeconds(5), SkillProposalStatus.PENDING));
        store.save(proposalAt("b-001", "b", base.plusSeconds(1), SkillProposalStatus.PENDING));

        List<SkillProposal> all = store.findAll();

        assertThat(all).extracting(SkillProposal::proposalId).containsExactly("b-001", "a-001");
    }

    @Test
    void countByNameCountsAllProposalsForName() {
        store.save(proposal("a-001", "shared-name", SkillProposalType.CREATE, SkillProposalStatus.PENDING, null));
        store.save(proposal("a-002", "shared-name", SkillProposalType.CREATE, SkillProposalStatus.REJECTED, null));
        store.save(proposal("b-001", "other", SkillProposalType.CREATE, SkillProposalStatus.PENDING, null));

        assertThat(store.countByName("shared-name")).isEqualTo(2);
    }

    @Test
    void countActiveCountsOnlyPendingAndQuarantined() {
        store.save(proposal("p1-001", "p1", SkillProposalType.CREATE, SkillProposalStatus.PENDING, null));
        store.save(proposal("p2-001", "p2", SkillProposalType.CREATE, SkillProposalStatus.QUARANTINED, null));
        store.save(proposal("p3-001", "p3", SkillProposalType.CREATE, SkillProposalStatus.APPLIED, null));
        store.save(proposal("p4-001", "p4", SkillProposalType.CREATE, SkillProposalStatus.REJECTED, null));

        assertThat(store.countActive()).isEqualTo(2);
    }

    private static SkillProposal proposal(String proposalId, String name, SkillProposalType type,
                                          SkillProposalStatus status, String targetHash) {
        return proposalAt(proposalId, name, type, Instant.parse("2026-09-01T08:00:00Z"), status, targetHash);
    }

    private static SkillProposal proposalAt(String proposalId, String name, Instant createdAt,
                                            SkillProposalStatus status) {
        return proposalAt(proposalId, name, SkillProposalType.UPDATE, createdAt, status, null);
    }

    private static SkillProposal proposalAt(String proposalId, String name, SkillProposalType type,
                                            Instant createdAt, SkillProposalStatus status, String targetHash) {
        return new SkillProposal(0, proposalId, type, name, "Beschreibung zu " + name,
                "Body zu " + name, status, targetHash, null, createdAt, createdAt);
    }
}