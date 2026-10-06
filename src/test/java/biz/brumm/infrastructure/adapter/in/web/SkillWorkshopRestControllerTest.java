package biz.brumm.infrastructure.adapter.in.web;

import biz.brumm.domain.model.ApprovalPolicy;
import biz.brumm.domain.model.SkillProposal;
import biz.brumm.domain.model.SkillProposalStatus;
import biz.brumm.domain.model.SkillProposalType;
import biz.brumm.domain.model.SkillWorkshopConfig;
import biz.brumm.domain.service.SkillWorkshopException;
import biz.brumm.domain.service.SkillWorkshopService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SkillWorkshopRestController.class)
class SkillWorkshopRestControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SkillWorkshopService skillWorkshopService;

    private static final SkillProposal PENDING_CREATE = new SkillProposal(1, "morning-catchup-001",
            SkillProposalType.CREATE, "morning-catchup", "Morgen-Routine", "Tue montags die Inbox.",
            SkillProposalStatus.PENDING, null, null,
            Instant.parse("2026-09-01T08:00:00Z"), Instant.parse("2026-09-01T08:00:00Z"));

    private static final SkillProposal APPLIED = new SkillProposal(1, "morning-catchup-001",
            SkillProposalType.CREATE, "morning-catchup", "Morgen-Routine", "Tue montags die Inbox.",
            SkillProposalStatus.APPLIED, null, null,
            Instant.parse("2026-09-01T08:00:00Z"), Instant.parse("2026-09-01T08:01:00Z"));

    @Test
    void configReturnsPolicyInfo() throws Exception {
        when(skillWorkshopService.config())
                .thenReturn(new SkillWorkshopConfig(ApprovalPolicy.PENDING, 50, 40_000, false));

        mockMvc.perform(get("/api/v1/skills/workshop"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvalPolicy").value("PENDING"))
                .andExpect(jsonPath("$.maxPending").value(50))
                .andExpect(jsonPath("$.maxSkillBytes").value(40000))
                .andExpect(jsonPath("$.autonomousEnabled").value(false));
    }

    @Test
    void proposeCreateReturns201() throws Exception {
        when(skillWorkshopService.proposeCreate("morning-catchup", "Morgen-Routine", "Tue montags die Inbox."))
                .thenReturn(PENDING_CREATE);

        mockMvc.perform(post("/api/v1/skills/workshop/proposals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"morning-catchup\", \"description\": \"Morgen-Routine\", "
                                + "\"content\": \"Tue montags die Inbox.\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.proposalId").value("morning-catchup-001"))
                .andExpect(jsonPath("$.type").value("CREATE"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void proposeCreateReturns400WhenFieldMissing() throws Exception {
        mockMvc.perform(post("/api/v1/skills/workshop/proposals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void proposeUpdateReturns201() throws Exception {
        when(skillWorkshopService.proposeUpdate("qa-check", "QA", "neu"))
                .thenReturn(new SkillProposal(2, "qa-check-001",
                        SkillProposalType.UPDATE, "qa-check", "QA", "neu",
                        SkillProposalStatus.PENDING, "deadbeef", null,
                        Instant.parse("2026-09-01T08:00:00Z"), Instant.parse("2026-09-01T08:00:00Z")));

        mockMvc.perform(post("/api/v1/skills/workshop/proposals/update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"qa-check\", \"description\": \"QA\", \"content\": \"neu\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("UPDATE"))
                .andExpect(jsonPath("$.proposalId").value("qa-check-001"));
    }

    @Test
    void listReturnsProposals() throws Exception {
        when(skillWorkshopService.listProposals()).thenReturn(List.of(PENDING_CREATE));

        mockMvc.perform(get("/api/v1/skills/workshop/proposals"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].proposalId").value("morning-catchup-001"));
    }

    @Test
    void inspectReturnsProposal() throws Exception {
        when(skillWorkshopService.inspect("morning-catchup-001")).thenReturn(PENDING_CREATE);

        mockMvc.perform(get("/api/v1/skills/workshop/proposals/morning-catchup-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void inspectReturns404WhenMissing() throws Exception {
        when(skillWorkshopService.inspect("nicht-da"))
                .thenThrow(new SkillWorkshopException("Vorschlag 'nicht-da' existiert nicht.",
                        SkillWorkshopException.Kind.NOT_FOUND));

        mockMvc.perform(get("/api/v1/skills/workshop/proposals/nicht-da"))
                .andExpect(status().isNotFound());
    }

    @Test
    void reviseReturnsUpdatedProposal() throws Exception {
        when(skillWorkshopService.revise("morning-catchup-001", "Neue Beschreibung", "Neuer Body"))
                .thenReturn(new SkillProposal(1, "morning-catchup-001",
                        SkillProposalType.CREATE, "morning-catchup", "Neue Beschreibung", "Neuer Body",
                        SkillProposalStatus.PENDING, null, null,
                        Instant.parse("2026-09-01T08:00:00Z"), Instant.parse("2026-09-01T08:02:00Z")));

        mockMvc.perform(post("/api/v1/skills/workshop/proposals/morning-catchup-001/revise")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\": \"Neue Beschreibung\", \"content\": \"Neuer Body\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("Neue Beschreibung"));
    }

    @Test
    void applyReturnsApplied() throws Exception {
        when(skillWorkshopService.apply("morning-catchup-001", false)).thenReturn(APPLIED);

        mockMvc.perform(post("/api/v1/skills/workshop/proposals/morning-catchup-001/apply"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"));
    }

    @Test
    void applyReturns403WhenApprovalRequired() throws Exception {
        when(skillWorkshopService.apply("morning-catchup-001", true))
                .thenThrow(new SkillWorkshopException("Operator-Freigabe erforderlich.",
                        SkillWorkshopException.Kind.APPROVAL_REQUIRED));

        mockMvc.perform(post("/api/v1/skills/workshop/proposals/morning-catchup-001/apply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentInitiated\": true}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void applyReturns409WhenStale() throws Exception {
        when(skillWorkshopService.apply(eq("morning-catchup-001"), anyBoolean()))
                .thenThrow(new SkillWorkshopException("Ziel hat sich geändert.",
                        SkillWorkshopException.Kind.CONFLICT));

        mockMvc.perform(post("/api/v1/skills/workshop/proposals/morning-catchup-001/apply"))
                .andExpect(status().isConflict());
    }

    @Test
    void rejectReturnsRejected() throws Exception {
        when(skillWorkshopService.reject(eq("morning-catchup-001"), eq("Duplikat"), anyBoolean()))
                .thenReturn(new SkillProposal(1, "morning-catchup-001",
                        SkillProposalType.CREATE, "morning-catchup", "Morgen-Routine", "Body",
                        SkillProposalStatus.REJECTED, null, "Duplikat",
                        Instant.parse("2026-09-01T08:00:00Z"), Instant.parse("2026-09-01T08:03:00Z")));

        mockMvc.perform(post("/api/v1/skills/workshop/proposals/morning-catchup-001/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"Duplikat\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.reason").value("Duplikat"));
    }

    @Test
    void quarantineReturnsQuarantined() throws Exception {
        when(skillWorkshopService.quarantine(eq("morning-catchup-001"), eq("Sicherheitspruefung"), anyBoolean()))
                .thenReturn(new SkillProposal(1, "morning-catchup-001",
                        SkillProposalType.CREATE, "morning-catchup", "Morgen-Routine", "Body",
                        SkillProposalStatus.QUARANTINED, null, "Sicherheitspruefung",
                        Instant.parse("2026-09-01T08:00:00Z"), Instant.parse("2026-09-01T08:04:00Z")));

        mockMvc.perform(post("/api/v1/skills/workshop/proposals/morning-catchup-001/quarantine")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"Sicherheitspruefung\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("QUARANTINED"));
    }
}