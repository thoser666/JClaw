package biz.brumm.infrastructure.adapter.in.web;

import biz.brumm.domain.model.ConversationMessage;
import biz.brumm.domain.port.in.GetConversationUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ConversationRestController.class)
class ConversationRestControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GetConversationUseCase getConversationUseCase;

    @Test
    void getConversationReturnsMessages() throws Exception {
        when(getConversationUseCase.getConversation("ctx-1")).thenReturn(List.of(
                new ConversationMessage("USER", "Hallo"),
                new ConversationMessage("ASSISTANT", "Hi!")));

        mockMvc.perform(get("/api/v1/conversations/ctx-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].role").value("USER"))
                .andExpect(jsonPath("$[0].text").value("Hallo"))
                .andExpect(jsonPath("$[1].role").value("ASSISTANT"))
                .andExpect(jsonPath("$[1].text").value("Hi!"));
    }

    @Test
    void getConversationReturnsEmptyListForUnknownContext() throws Exception {
        when(getConversationUseCase.getConversation("unbekannt")).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/conversations/unbekannt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }
}
