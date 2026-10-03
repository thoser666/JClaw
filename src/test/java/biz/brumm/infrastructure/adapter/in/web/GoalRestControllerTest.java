package biz.brumm.infrastructure.adapter.in.web;

import biz.brumm.domain.model.GoalStatus;
import biz.brumm.domain.model.SessionGoal;
import biz.brumm.domain.service.GoalOperationException;
import biz.brumm.domain.service.GoalService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(GoalRestController.class)
class GoalRestControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GoalService goalService;

    private static final SessionGoal GOAL = new SessionGoal(
            "s1", "Schreibe README", GoalStatus.ACTIVE, null, null,
            Instant.parse("2026-09-01T08:00:00Z"), null);

    @Test
    void getReturnsGoal() throws Exception {
        when(goalService.getGoal("s1")).thenReturn(Optional.of(GOAL));

        mockMvc.perform(get("/api/v1/sessions/s1/goal"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("s1"))
                .andExpect(jsonPath("$.objective").value("Schreibe README"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void getReturns404WhenMissing() throws Exception {
        when(goalService.getGoal("missing")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/sessions/missing/goal"))
                .andExpect(status().isNotFound());
    }

    @Test
    void startReturnsCreatedGoal() throws Exception {
        when(goalService.startGoal(eq("s1"), eq("Schreibe README"), any())).thenReturn(GOAL);

        mockMvc.perform(post("/api/v1/sessions/s1/goal/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"objective\": \"Schreibe README\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void startReturns409WhenGoalExists() throws Exception {
        when(goalService.startGoal(eq("s1"), eq("Ziel"), any()))
                .thenThrow(new GoalOperationException("Goal error: bereits vorhanden", false));

        mockMvc.perform(post("/api/v1/sessions/s1/goal/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"objective\": \"Ziel\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void startReturns400WhenObjectiveMissing() throws Exception {
        mockMvc.perform(post("/api/v1/sessions/s1/goal/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void editReturnsRewordedGoal() throws Exception {
        when(goalService.rewordGoal("s1", "Neue Formulierung"))
                .thenReturn(new SessionGoal("s1", "Neue Formulierung", GoalStatus.ACTIVE, null, null,
                        Instant.parse("2026-09-01T08:00:00Z"), null));

        mockMvc.perform(post("/api/v1/sessions/s1/goal/edit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"objective\": \"Neue Formulierung\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.objective").value("Neue Formulierung"));
    }

    @Test
    void editReturns404WhenMissing() throws Exception {
        when(goalService.rewordGoal("missing", "Ziel"))
                .thenThrow(new GoalOperationException("kein Ziel", true));

        mockMvc.perform(post("/api/v1/sessions/missing/goal/edit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"objective\": \"Ziel\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void pauseReturnsPausedGoal() throws Exception {
        when(goalService.pauseGoal("s1", "Pause"))
                .thenReturn(new SessionGoal("s1", "Ziel", GoalStatus.PAUSED, "Pause", null,
                        Instant.parse("2026-09-01T08:00:00Z"), null));

        mockMvc.perform(post("/api/v1/sessions/s1/goal/pause")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\": \"Pause\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAUSED"));
    }

    @Test
    void resumeReturns409WhenComplete() throws Exception {
        when(goalService.resumeGoal("s1", null))
                .thenThrow(new GoalOperationException("abgeschlossen", false));

        mockMvc.perform(post("/api/v1/sessions/s1/goal/resume"))
                .andExpect(status().isConflict());
    }

    @Test
    void clearReturnsCleared() throws Exception {
        mockMvc.perform(delete("/api/v1/sessions/s1/goal"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cleared").value(true));

        verify(goalService).clearGoal("s1");
    }
}