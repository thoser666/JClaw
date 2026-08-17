package biz.brumm.infrastructure.adapter.in.web;

import biz.brumm.domain.model.Session;
import biz.brumm.domain.service.SessionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SessionRestController.class)
class SessionRestControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SessionService sessionService;

    @Test
    void listSessionsReturnsAllSessions() throws Exception {
        Session session = new Session("s1", "Test", Instant.parse("2026-08-16T10:00:00Z"),
                Instant.parse("2026-08-16T10:00:00Z"), Instant.parse("2026-08-16T10:00:00Z"));
        when(sessionService.listSessions()).thenReturn(List.of(session));

        mockMvc.perform(get("/api/v1/sessions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sessionId").value("s1"))
                .andExpect(jsonPath("$[0].displayName").value("Test"));
    }

    @Test
    void getSessionReturnsSessionWhenFound() throws Exception {
        Session session = new Session("s1", "Test", Instant.parse("2026-08-16T10:00:00Z"),
                Instant.parse("2026-08-16T10:00:00Z"), Instant.parse("2026-08-16T10:00:00Z"));
        when(sessionService.findSession("s1")).thenReturn(Optional.of(session));

        mockMvc.perform(get("/api/v1/sessions/s1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("s1"));
    }

    @Test
    void getSessionReturns404WhenNotFound() throws Exception {
        when(sessionService.findSession("missing")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/sessions/missing"))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteSessionReturns204() throws Exception {
        mockMvc.perform(delete("/api/v1/sessions/s1"))
                .andExpect(status().isNoContent());

        verify(sessionService).deleteSession("s1");
    }

    @Test
    void deleteNonexistentSessionStillReturns204() throws Exception {
        mockMvc.perform(delete("/api/v1/sessions/missing"))
                .andExpect(status().isNoContent());

        verify(sessionService).deleteSession("missing");
    }
}
