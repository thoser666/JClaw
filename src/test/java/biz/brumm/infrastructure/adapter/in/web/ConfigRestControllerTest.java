package biz.brumm.infrastructure.adapter.in.web;

import biz.brumm.config.json5.Json5ConfigReloadService;
import biz.brumm.config.json5.Json5ConfigValidationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ConfigRestController.class)
class ConfigRestControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private Json5ConfigReloadService reloadService;

    @Test
    void configApplyReturnsOkOnSuccess() throws Exception {
        when(reloadService.reload()).thenReturn(true);

        mockMvc.perform(post("/api/v1/config.apply"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.message").value("Konfiguration erfolgreich neu geladen."));
    }

    @Test
    void configApplyReturnsSkippedWhenNoConfig() throws Exception {
        when(reloadService.reload()).thenReturn(false);

        mockMvc.perform(post("/api/v1/config.apply"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("skipped"));
    }

    @Test
    void configApplyReturnsBadRequestOnValidationError() throws Exception {
        when(reloadService.reload())
                .thenThrow(new Json5ConfigValidationException(List.of("Fehler")));

        mockMvc.perform(post("/api/v1/config.apply"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("Validierungsfehler")))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("Fehler")));
    }

    @Test
    void configApplyReturns500OnIoError() throws Exception {
        when(reloadService.reload())
                .thenThrow(new IOException("Datei nicht lesbar"));

        mockMvc.perform(post("/api/v1/config.apply"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("I/O-Fehler: Datei nicht lesbar"));
    }
}
