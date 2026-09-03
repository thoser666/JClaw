package biz.brumm.infrastructure.adapter.in.web;

import biz.brumm.domain.model.MemoryDocument;
import biz.brumm.domain.service.MemoryVaultService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MemoryVaultController.class)
class MemoryVaultControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MemoryVaultService memoryVaultService;

    @Test
    void syncStoresVaultDocument() throws Exception {
        when(memoryVaultService.syncConversation("ctx-1")).thenReturn(true);

        mockMvc.perform(post("/api/v1/memory/ctx-1/sync"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationId").value("ctx-1"))
                .andExpect(jsonPath("$.stored").value(true));

        verify(memoryVaultService).syncConversation("ctx-1");
    }

    @Test
    void syncWithEmptyConversationReportsNotStored() throws Exception {
        when(memoryVaultService.syncConversation("leer")).thenReturn(false);

        mockMvc.perform(post("/api/v1/memory/leer/sync"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stored").value(false));
    }

    @Test
    void listReturnsDocuments() throws Exception {
        when(memoryVaultService.listDocuments()).thenReturn(List.of(
                new MemoryDocument("ctx-1", "Titel", Instant.parse("2026-09-03T10:00:00Z"),
                        List.of("a"), "Inhalt")));

        mockMvc.perform(get("/api/v1/memory"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].conversationId").value("ctx-1"))
                .andExpect(jsonPath("$[0].title").value("Titel"))
                .andExpect(jsonPath("$[0].content").value("Inhalt"));
    }
}
