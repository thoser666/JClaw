package biz.brumm.infrastructure.adapter.in.web;

import biz.brumm.domain.model.*;
import biz.brumm.domain.service.ChannelService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ChannelRestController.class)
class ChannelRestControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChannelService channelService;

    private Channel createTestChannel() {
        return new Channel("ch-1", "Telegram Bot", ChannelType.TELEGRAM, true,
                Map.of("token", "abc"), Instant.now(), Instant.now());
    }

    @Test
    void listReturnsChannels() throws Exception {
        when(channelService.findAll()).thenReturn(List.of(createTestChannel()));

        mockMvc.perform(get("/api/v1/channels"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("ch-1"))
                .andExpect(jsonPath("$[0].name").value("Telegram Bot"))
                .andExpect(jsonPath("$[0].type").value("TELEGRAM"));
    }

    @Test
    void getReturnsChannel() throws Exception {
        when(channelService.findById("ch-1")).thenReturn(Optional.of(createTestChannel()));

        mockMvc.perform(get("/api/v1/channels/ch-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Telegram Bot"));
    }

    @Test
    void getReturns404WhenNotFound() throws Exception {
        when(channelService.findById("999")).thenReturn(Optional.empty());
        mockMvc.perform(get("/api/v1/channels/999"))
                .andExpect(status().isNotFound());
    }

    @Test
    void createValidChannelReturnsChannel() throws Exception {
        Channel saved = createTestChannel();
        when(channelService.save(any())).thenReturn(saved);

        mockMvc.perform(post("/api/v1/channels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Telegram Bot\",\"type\":\"TELEGRAM\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Telegram Bot"));
    }

    @Test
    void createMissingNameReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/channels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"TELEGRAM\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void createUnknownTypeReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/channels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Test\",\"type\":\"UNKNOWN\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Unbekannter Channel-Typ: UNKNOWN"));
    }

    @Test
    void deleteReturnsDeleted() throws Exception {
        when(channelService.findById("ch-1")).thenReturn(Optional.of(createTestChannel()));
        mockMvc.perform(delete("/api/v1/channels/ch-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true));
        verify(channelService).delete("ch-1");
    }

    @Test
    void deleteReturns404WhenNotFound() throws Exception {
        when(channelService.findById("999")).thenReturn(Optional.empty());
        mockMvc.perform(delete("/api/v1/channels/999"))
                .andExpect(status().isNotFound());
    }

    @Test
    void adaptersReturnsAvailableTypes() throws Exception {
        when(channelService.availableAdapterTypes()).thenReturn(java.util.Set.of(ChannelType.TELEGRAM));

        mockMvc.perform(get("/api/v1/channels/adapters"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available[0]").value("TELEGRAM"));
    }
}
