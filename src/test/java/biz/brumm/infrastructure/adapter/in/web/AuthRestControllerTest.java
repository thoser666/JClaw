package biz.brumm.infrastructure.adapter.in.web;

import biz.brumm.domain.model.ApiKey;
import biz.brumm.domain.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthRestController.class)
class AuthRestControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    @Test
    void listTokensReturnsAllTokens() throws Exception {
        ApiKey key = new ApiKey("id-1", "test-key",
                "hash123", Instant.parse("2026-08-16T10:00:00Z"));
        when(authService.listApiKeys()).thenReturn(List.of(key));

        mockMvc.perform(get("/api/v1/auth/tokens"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("id-1"))
                .andExpect(jsonPath("$[0].name").value("test-key"));
    }

    @Test
    void createTokenReturnsRawToken() throws Exception {
        when(authService.createApiKey("new-key")).thenReturn("raw-token-123");

        mockMvc.perform(post("/api/v1/auth/tokens")
                        .contentType("application/json")
                        .content("{\"name\": \"new-key\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("raw-token-123"))
                .andExpect(jsonPath("$.name").value("new-key"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void createTokenReturns400WhenNameMissing() throws Exception {
        mockMvc.perform(post("/api/v1/auth/tokens")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    @Test
    void deleteTokenReturns200() throws Exception {
        mockMvc.perform(delete("/api/v1/auth/tokens/id-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));

        verify(authService).deleteApiKey("id-1");
    }
}
