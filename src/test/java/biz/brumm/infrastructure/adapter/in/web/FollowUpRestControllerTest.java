package biz.brumm.infrastructure.adapter.in.web;

import biz.brumm.domain.model.FollowUp;
import biz.brumm.domain.service.FollowUpQueueService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FollowUpRestController.class)
class FollowUpRestControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FollowUpQueueService followUpQueueService;

    private static final FollowUp FOLLOW_UP = new FollowUp(
            "f1", "s1", "Fasse zusammen", Instant.parse("2026-09-01T08:00:00Z"), null);

    @Test
    void enqueueReturnsEnqueuedFollowUp() throws Exception {
        when(followUpQueueService.enqueue("s1", "Fasse zusammen")).thenReturn(FOLLOW_UP);

        mockMvc.perform(post("/api/v1/sessions/s1/follow-ups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\": \"Fasse zusammen\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("f1"))
                .andExpect(jsonPath("$.deliveredAt").doesNotExist());
    }

    @Test
    void enqueueReturns400WhenPromptMissing() throws Exception {
        mockMvc.perform(post("/api/v1/sessions/s1/follow-ups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listReturnsPendingFollowUps() throws Exception {
        when(followUpQueueService.pending("s1")).thenReturn(List.of(FOLLOW_UP));

        mockMvc.perform(get("/api/v1/sessions/s1/follow-ups"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("f1"));
    }

    @Test
    void drainReturnsDeliveredFollowUps() throws Exception {
        when(followUpQueueService.drain("s1"))
                .thenReturn(List.of(FOLLOW_UP.withDelivered(Instant.parse("2026-09-01T09:00:00Z"))));

        mockMvc.perform(post("/api/v1/sessions/s1/follow-ups/drain"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("f1"))
                .andExpect(jsonPath("$[0].deliveredAt").value("2026-09-01T09:00:00Z"));
    }

    @Test
    void cancelReturns200WhenFound() throws Exception {
        when(followUpQueueService.findById("f1")).thenReturn(Optional.of(FOLLOW_UP));

        mockMvc.perform(delete("/api/v1/follow-ups/f1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true));

        verify(followUpQueueService).cancel("f1");
    }

    @Test
    void cancelReturns404WhenMissing() throws Exception {
        when(followUpQueueService.findById("missing")).thenReturn(Optional.empty());

        mockMvc.perform(delete("/api/v1/follow-ups/missing"))
                .andExpect(status().isNotFound());
    }
}