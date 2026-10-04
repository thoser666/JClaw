package biz.brumm.infrastructure.adapter.in.web;

import biz.brumm.domain.model.BackgroundTask;
import biz.brumm.domain.model.BackgroundTaskStatus;
import biz.brumm.domain.service.BackgroundTaskService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(BackgroundTaskRestController.class)
class BackgroundTaskRestControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BackgroundTaskService backgroundTaskService;

    private static final BackgroundTask COMPLETED_TASK = new BackgroundTask(
            "bt1", "s1", "Fasse zusammen", BackgroundTaskStatus.COMPLETED, "Ergebnis", null,
            Instant.parse("2026-09-01T08:00:00Z"),
            Instant.parse("2026-09-01T08:00:01Z"), Instant.parse("2026-09-01T08:00:05Z"));

    private static final BackgroundTask PENDING_TASK = new BackgroundTask(
            "bt1", "s1", "Fasse zusammen", BackgroundTaskStatus.PENDING, null, null,
            Instant.parse("2026-09-01T08:00:00Z"), null, null);

    @TestConfiguration
    static class ExecutorConfig {
        @Bean
        Executor executor() {
            return Runnable::run;
        }
    }

    @Test
    void startReturns202WithPendingTask() throws Exception {
        when(backgroundTaskService.start("s1", "Fasse zusammen")).thenReturn(PENDING_TASK);

        mockMvc.perform(post("/api/v1/sessions/s1/background-tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\": \"Fasse zusammen\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value("bt1"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void startReturns400WhenPromptMissing() throws Exception {
        mockMvc.perform(post("/api/v1/sessions/s1/background-tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listReturnsTasks() throws Exception {
        when(backgroundTaskService.findBySession("s1")).thenReturn(List.of(COMPLETED_TASK));

        mockMvc.perform(get("/api/v1/sessions/s1/background-tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("bt1"))
                .andExpect(jsonPath("$[0].status").value("COMPLETED"));
    }

    @Test
    void getReturnsTask() throws Exception {
        when(backgroundTaskService.findById("bt1")).thenReturn(Optional.of(COMPLETED_TASK));

        mockMvc.perform(get("/api/v1/background-tasks/bt1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void getReturns404WhenMissing() throws Exception {
        when(backgroundTaskService.findById("missing")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/background-tasks/missing"))
                .andExpect(status().isNotFound());
    }

    @Test
    void eventsEmitsCompletionWhenTaskAlreadyTerminal() throws Exception {
        when(backgroundTaskService.findById("bt1")).thenReturn(Optional.of(COMPLETED_TASK));

        MvcResult mvcResult = mockMvc.perform(get("/api/v1/background-tasks/bt1/events"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("event:completion")));
    }

    @Test
    void eventsEmitsErrorForUnknownTask() throws Exception {
        when(backgroundTaskService.findById("missing")).thenReturn(Optional.empty());

        MvcResult mvcResult = mockMvc.perform(get("/api/v1/background-tasks/missing/events"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("event:error")));
    }

    @Test
    void startVerifiesServiceCall() throws Exception {
        when(backgroundTaskService.start("s1", "Test")).thenReturn(PENDING_TASK);

        mockMvc.perform(post("/api/v1/sessions/s1/background-tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\": \"Test\"}"))
                .andExpect(status().isAccepted());

        verify(backgroundTaskService).start("s1", "Test");
    }
}