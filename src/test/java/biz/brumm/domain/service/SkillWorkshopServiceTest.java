package biz.brumm.domain.service;

import biz.brumm.config.SkillWorkshopProperties;
import biz.brumm.domain.model.ApprovalPolicy;
import biz.brumm.domain.model.Skill;
import biz.brumm.domain.model.SkillProposal;
import biz.brumm.domain.model.SkillProposalStatus;
import biz.brumm.domain.model.SkillProposalType;
import biz.brumm.domain.port.out.SkillProposalStore;
import biz.brumm.domain.port.out.SkillWorkshopWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkillWorkshopServiceTest {

    private FakeStore store;
    private FakeWriter writer;
    private SkillWorkshopService service;

    @BeforeEach
    void setUp() {
        store = new FakeStore();
        writer = new FakeWriter();
        service = new SkillWorkshopService(store, writer, new SkillWorkshopProperties(null, 0, 0, null));
    }

    @Test
    void proposeCreateReturnsPendingProposal() {
        SkillProposal proposal = service.proposeCreate("morning-catchup", "Morgen-Routine", "Tue montags die Inbox.");

        assertThat(proposal.proposalId()).isEqualTo("morning-catchup-001");
        assertThat(proposal.type()).isEqualTo(SkillProposalType.CREATE);
        assertThat(proposal.status()).isEqualTo(SkillProposalStatus.PENDING);
        assertThat(proposal.targetHash()).isNull();
        assertThat(store.rows).hasSize(1);
    }

    @Test
    void proposeCreateIncrementsProposalIdSeqPerName() {
        service.proposeCreate("morning-catchup", "A", "a");
        SkillProposal second = service.proposeCreate("morning-catchup", "B", "b");

        assertThat(second.proposalId()).isEqualTo("morning-catchup-002");
    }

    @Test
    void proposeCreateRejectsBlankFields() {
        assertThatThrownBy(() -> service.proposeCreate("  ", "A", "a")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.proposeCreate("name", "  ", "a")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.proposeCreate("name", "A", "  ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void proposeCreateRejectsContentOverMaxSkillBytes() {
        SkillWorkshopService small = new SkillWorkshopService(store, writer,
                new SkillWorkshopProperties(null, 50, 5, null));

        assertThatThrownBy(() -> small.proposeCreate("name", "A", "123456"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxSkillBytes");
    }

    @Test
    void proposeCreateIsNoClobberWhenSkillExists() {
        writer.createLive("existing", "alt");

        assertThatThrownBy(() -> service.proposeCreate("existing", "A", "neu"))
                .isInstanceOf(SkillWorkshopException.class)
                .extracting(thrown -> ((SkillWorkshopException) thrown).kind())
                .isEqualTo(SkillWorkshopException.Kind.CONFLICT);
    }

    @Test
    void proposeCreateEnforcesMaxPending() {
        SkillWorkshopService capped = new SkillWorkshopService(store, writer,
                new SkillWorkshopProperties(null, 1, 0, null));
        capped.proposeCreate("one", "A", "a");

        assertThatThrownBy(() -> capped.proposeCreate("two", "B", "b"))
                .isInstanceOf(SkillWorkshopException.class)
                .extracting(thrown -> ((SkillWorkshopException) thrown).kind())
                .isEqualTo(SkillWorkshopException.Kind.CONFLICT);
        assertThat(store.rows).hasSize(1);
    }

    @Test
    void proposeUpdateRequiresLiveSkill() {
        assertThatThrownBy(() -> service.proposeUpdate("missing", "A", "a"))
                .isInstanceOf(SkillWorkshopException.class)
                .extracting(thrown -> ((SkillWorkshopException) thrown).kind())
                .isEqualTo(SkillWorkshopException.Kind.NOT_FOUND);
    }

    @Test
    void proposeUpdateBindsTargetHash() {
        writer.createLive("qa-check", "altes Frontmatter");

        SkillProposal proposal = service.proposeUpdate("qa-check", "QA-Liste", "Neue Checks");

        assertThat(proposal.type()).isEqualTo(SkillProposalType.UPDATE);
        assertThat(proposal.status()).isEqualTo(SkillProposalStatus.PENDING);
        assertThat(proposal.targetHash()).isEqualTo(writer.contentHash("qa-check"));
        assertThat(proposal.proposalId()).isEqualTo("qa-check-001");
    }

    @Test
    void listReturnsAllProposalsOldestFirst() {
        service.proposeCreate("single", "A", "a");

        assertThat(service.listProposals()).extracting(SkillProposal::proposalId)
                .containsExactly("single-001");
    }

    @Test
    void inspectThrowsNotFoundForMissingProposal() {
        assertThatThrownBy(() -> service.inspect("nicht-da"))
                .isInstanceOf(SkillWorkshopException.class)
                .extracting(thrown -> ((SkillWorkshopException) thrown).kind())
                .isEqualTo(SkillWorkshopException.Kind.NOT_FOUND);
    }

    @Test
    void reviseUpdatesContentWhileStayingPending() {
        SkillProposal proposal = service.proposeCreate("morning-catchup", "A", "a");

        SkillProposal revised = service.revise(proposal.proposalId(), "B", "b");

        assertThat(revised.description()).isEqualTo("B");
        assertThat(revised.content()).isEqualTo("b");
        assertThat(revised.status()).isEqualTo(SkillProposalStatus.PENDING);
        assertThat(store.findByProposalId(proposal.proposalId()).orElseThrow().description()).isEqualTo("B");
    }

    @Test
    void reviseRejectsNonPendingProposal() {
        SkillProposal proposal = service.proposeCreate("morning-catchup", "A", "a");
        service.reject(proposal.proposalId(), "dup", false);

        assertThatThrownBy(() -> service.revise(proposal.proposalId(), "B", "b"))
                .isInstanceOf(SkillWorkshopException.class)
                .extracting(thrown -> ((SkillWorkshopException) thrown).kind())
                .isEqualTo(SkillWorkshopException.Kind.CONFLICT);
    }

    @Test
    void applyCreatesWritesSkillAndMarksApplied() {
        SkillProposal proposal = service.proposeCreate("morning-catchup", "Morgen", "Schreibe die Inbox.");

        SkillProposal applied = service.apply(proposal.proposalId(), false);

        assertThat(applied.status()).isEqualTo(SkillProposalStatus.APPLIED);
        assertThat(writer.exists("morning-catchup")).isTrue();
        assertThat(writer.content("morning-catchup")).isEqualTo("Schreibe die Inbox.");
    }

    @Test
    void applyCreateGoesStaleWhenTargetAppearsBeforeApply() {
        SkillProposal proposal = service.proposeCreate("morning-catchup", "Morgen", "Inhalt");
        writer.createLive("morning-catchup", "extern angelegt");

        assertThatThrownBy(() -> service.apply(proposal.proposalId(), false))
                .isInstanceOf(SkillWorkshopException.class)
                .extracting(thrown -> ((SkillWorkshopException) thrown).kind())
                .isEqualTo(SkillWorkshopException.Kind.CONFLICT);
        assertThat(store.findByProposalId(proposal.proposalId()).orElseThrow().status())
                .isEqualTo(SkillProposalStatus.STALE);
    }

    @Test
    void applyUpdateGoesStaleWhenLiveSkillChanges() {
        writer.createLive("qa-check", "alt");
        SkillProposal proposal = service.proposeUpdate("qa-check", "QA", "neu1");
        writer.createLive("qa-check", "extern geandert");

        assertThatThrownBy(() -> service.apply(proposal.proposalId(), false))
                .isInstanceOf(SkillWorkshopException.class)
                .extracting(thrown -> ((SkillWorkshopException) thrown).kind())
                .isEqualTo(SkillWorkshopException.Kind.CONFLICT);
        assertThat(store.findByProposalId(proposal.proposalId()).orElseThrow().status())
                .isEqualTo(SkillProposalStatus.STALE);
    }

    @Test
    void applyUpdateSucceedsWhenLiveSkillUnchanged() {
        writer.createLive("qa-check", "alt");
        SkillProposal proposal = service.proposeUpdate("qa-check", "QA", "neu");

        SkillProposal applied = service.apply(proposal.proposalId(), false);

        assertThat(applied.status()).isEqualTo(SkillProposalStatus.APPLIED);
        assertThat(writer.content("qa-check")).isEqualTo("neu");
    }

    @Test
    void applyRejectsNonPendingProposal() {
        SkillProposal proposal = service.proposeCreate("morning-catchup", "A", "a");
        service.reject(proposal.proposalId(), "dup", false);

        assertThatThrownBy(() -> service.apply(proposal.proposalId(), false))
                .isInstanceOf(SkillWorkshopException.class)
                .extracting(thrown -> ((SkillWorkshopException) thrown).kind())
                .isEqualTo(SkillWorkshopException.Kind.CONFLICT);
        assertThat(writer.exists("morning-catchup")).isFalse();
    }

    @Test
    void agentInitiatedApplyRequiresOperatorApprovalUnderPendingPolicy() {
        SkillProposal proposal = service.proposeCreate("morning-catchup", "A", "a");

        assertThatThrownBy(() -> service.apply(proposal.proposalId(), true))
                .isInstanceOf(SkillWorkshopException.class)
                .extracting(thrown -> ((SkillWorkshopException) thrown).kind())
                .isEqualTo(SkillWorkshopException.Kind.APPROVAL_REQUIRED);
        assertThat(store.findByProposalId(proposal.proposalId()).orElseThrow().status())
                .isEqualTo(SkillProposalStatus.PENDING);
    }

    @Test
    void agentInitiatedApplyRunsUnderAutoPolicy() {
        SkillWorkshopService auto = new SkillWorkshopService(store, writer,
                new SkillWorkshopProperties(ApprovalPolicy.AUTO, 0, 0, null));
        SkillProposal proposal = auto.proposeCreate("morning-catchup", "A", "a");

        SkillProposal applied = auto.apply(proposal.proposalId(), true);

        assertThat(applied.status()).isEqualTo(SkillProposalStatus.APPLIED);
    }

    @Test
    void agentInitiatedRejectBlockedUnderPendingPolicy() {
        SkillProposal proposal = service.proposeCreate("morning-catchup", "A", "a");

        assertThatThrownBy(() -> service.reject(proposal.proposalId(), "dup", true))
                .isInstanceOf(SkillWorkshopException.class)
                .extracting(thrown -> ((SkillWorkshopException) thrown).kind())
                .isEqualTo(SkillWorkshopException.Kind.APPROVAL_REQUIRED);
    }

    @Test
    void rejectMarksRejectedWithReason() {
        SkillProposal proposal = service.proposeCreate("morning-catchup", "A", "a");

        SkillProposal rejected = service.reject(proposal.proposalId(), "Duplikat", false);

        assertThat(rejected.status()).isEqualTo(SkillProposalStatus.REJECTED);
        assertThat(rejected.reason()).isEqualTo("Duplikat");
        assertThat(writer.exists("morning-catchup")).isFalse();
    }

    @Test
    void quarantineMarksQuarantinedWithReason() {
        SkillProposal proposal = service.proposeCreate("morning-catchup", "A", "a");

        SkillProposal quarantined = service.quarantine(proposal.proposalId(), "Sicherheitspruefung", false);

        assertThat(quarantined.status()).isEqualTo(SkillProposalStatus.QUARANTINED);
        assertThat(quarantined.reason()).isEqualTo("Sicherheitspruefung");
    }

    @Test
    void quarantineCountsAgainstMaxPending() {
        SkillWorkshopService capped = new SkillWorkshopService(store, writer,
                new SkillWorkshopProperties(null, 1, 0, null));
        SkillProposal one = capped.proposeCreate("one", "A", "a");
        capped.quarantine(one.proposalId(), "review", false);

        assertThatThrownBy(() -> capped.proposeCreate("two", "B", "b"))
                .isInstanceOf(SkillWorkshopException.class)
                .extracting(thrown -> ((SkillWorkshopException) thrown).kind())
                .isEqualTo(SkillWorkshopException.Kind.CONFLICT);
    }

    @Test
    void configExposesDefaults() {
        assertThat(service.config().approvalPolicy()).isEqualTo(ApprovalPolicy.PENDING);
        assertThat(service.config().maxPending()).isEqualTo(50);
        assertThat(service.config().maxSkillBytes()).isEqualTo(40_000);
        assertThat(service.config().autonomousEnabled()).isFalse();
    }

    @Test
    void configExposesConfiguredValues() {
        SkillWorkshopService auto = new SkillWorkshopService(store, writer,
                new SkillWorkshopProperties(ApprovalPolicy.AUTO, 7, 1234, new SkillWorkshopProperties.Autonomous(true)));

        assertThat(auto.config().approvalPolicy()).isEqualTo(ApprovalPolicy.AUTO);
        assertThat(auto.config().maxPending()).isEqualTo(7);
        assertThat(auto.config().maxSkillBytes()).isEqualTo(1234);
        assertThat(auto.config().autonomousEnabled()).isTrue();
    }

    @Test
    void slugNormalizesSkillName() {
        assertThat(SkillWorkshopService.slug("Morning Catchup!")).isEqualTo("morning-catchup");
        assertThat(SkillWorkshopService.slug("!!!")).isEqualTo("skill");
    }

    private static final class FakeStore implements SkillProposalStore {

        private final List<SkillProposal> rows = new ArrayList<>();
        private long nextId = 1;

        @Override
        public SkillProposal save(SkillProposal proposal) {
            SkillProposal stored = proposal.id() > 0
                    ? proposal
                    : new SkillProposal(nextId++, proposal.proposalId(), proposal.type(), proposal.name(),
                            proposal.description(), proposal.content(), proposal.status(),
                            proposal.targetHash(), proposal.reason(), proposal.createdAt(), Instant.now());
            rows.removeIf(row -> row.id() == stored.id());
            rows.add(stored);
            return stored;
        }

        @Override
        public Optional<SkillProposal> findByProposalId(String proposalId) {
            return rows.stream().filter(row -> row.proposalId().equals(proposalId)).findFirst();
        }

        @Override
        public List<SkillProposal> findAll() {
            return List.copyOf(rows);
        }

        @Override
        public long countByName(String name) {
            return rows.stream().filter(row -> row.name().equals(name)).count();
        }

        @Override
        public long countActive() {
            return rows.stream()
                    .filter(row -> row.status() == SkillProposalStatus.PENDING
                            || row.status() == SkillProposalStatus.QUARANTINED)
                    .count();
        }
    }

    private static final class FakeWriter implements SkillWorkshopWriter {

        private final Map<String, String> files = new LinkedHashMap<>();

        @Override
        public boolean exists(String name) {
            return files.containsKey(name);
        }

        @Override
        public String contentHash(String name) {
            String content = files.get(name);
            return content == null ? "" : sha256Hex(content.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public Skill write(Skill skill) {
            files.put(skill.name(), skill.content());
            return skill;
        }

        private void createLive(String name, String content) {
            files.put(name, content);
        }

        private String content(String name) {
            return files.get(name);
        }

        private static String sha256Hex(byte[] bytes) {
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}