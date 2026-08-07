package biz.brumm.infrastructure.adapter.in.web;

import biz.brumm.domain.model.AgentCommand;
import biz.brumm.domain.model.AgentResponse;
import biz.brumm.domain.model.ToolInvocation;
import biz.brumm.domain.port.in.ExecuteTaskUseCase;
import biz.brumm.domain.service.AgentLoopLimitExceededException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TaskRestController.class)
class TaskRestControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ExecuteTaskUseCase executeTaskUseCase;

    @Test
    void postWithValidRequestReturnsAgentResponse() throws Exception {
        AgentResponse response = new AgentResponse("Antwort", Instant.parse("2026-08-06T08:00:00Z"),
                List.of(new ToolInvocation("calculate", "{\"expression\":\"2+2\"}", "4")), 2);
        when(executeTaskUseCase.handle(argThat(command -> command.prompt().equals("Hallo")
                && command.contextId().equals("c1")))).thenReturn(response);

        mockMvc.perform(post("/api/v1/tasks")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"prompt":"Hallo","contextId":"c1"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("Antwort"))
                .andExpect(jsonPath("$.iterations").value(2))
                .andExpect(jsonPath("$.toolInvocations", hasSize(1)))
                .andExpect(jsonPath("$.toolInvocations[0].name").value("calculate"));

        verify(executeTaskUseCase).handle(argThat(command -> command.prompt().equals("Hallo")
                && command.contextId().equals("c1")));
    }

    @Test
    void postForwardsCommandToUseCase() throws Exception {
        when(executeTaskUseCase.handle(new AgentCommand("Aufgabe", "ctx-9")))
                .thenReturn(AgentResponse.of("ok"));

        mockMvc.perform(post("/api/v1/tasks")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"prompt":"Aufgabe","contextId":"ctx-9"}"""))
                .andExpect(status().isOk());

        verify(executeTaskUseCase).handle(new AgentCommand("Aufgabe", "ctx-9"));
    }

    @Test
    void postWithBlankPromptReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/tasks")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"prompt":"","contextId":"c1"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Prompt darf nicht leer sein."));
    }

    @Test
    void postWithMalformedJsonReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/tasks")
                        .contentType(APPLICATION_JSON)
                        .content("{ invalid"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void postWithoutContextIdForwardsNullContext() throws Exception {
        when(executeTaskUseCase.handle(argThat(command -> command.prompt().equals("Hallo")
                && command.contextId() == null))).thenReturn(AgentResponse.of("ok"));

        mockMvc.perform(post("/api/v1/tasks")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"prompt":"Hallo"}"""))
                .andExpect(status().isOk());

        verify(executeTaskUseCase).handle(argThat(command -> command.prompt().equals("Hallo")
                && command.contextId() == null));
    }

    @Test
    void postWithLoopLimitExceededReturnsServerError() throws Exception {
        when(executeTaskUseCase.handle(any(AgentCommand.class)))
                .thenThrow(new AgentLoopLimitExceededException(8));

        mockMvc.perform(post("/api/v1/tasks")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"prompt":"Aufgabe","contextId":"c1"}"""))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("Der Agent hat die maximale Anzahl von 8 Iteration(en) überschritten."));
    }
}
