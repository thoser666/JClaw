package biz.brumm.infrastructure.adapter.in.web;

import biz.brumm.domain.model.PluginOverview;
import biz.brumm.domain.model.PluginType;
import biz.brumm.domain.port.in.ListPluginsUseCase;
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

@WebMvcTest(PluginRestController.class)
class PluginRestControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ListPluginsUseCase listPluginsUseCase;

    @Test
    void getPluginsReturnsOverviews() throws Exception {
        when(listPluginsUseCase.listPlugins()).thenReturn(List.of(
                new PluginOverview("acme/demo", "Demo", "1.0.0", "Test.", PluginType.OPENCLAW, true, ""),
                new PluginOverview("broken", null, null, null, PluginType.OPENCLAW, false, "Feld 'id' fehlt.")));

        mockMvc.perform(get("/api/v1/plugins"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value("acme/demo"))
                .andExpect(jsonPath("$[0].type").value("OPENCLAW"))
                .andExpect(jsonPath("$[0].valid").value(true))
                .andExpect(jsonPath("$[1].valid").value(false))
                .andExpect(jsonPath("$[1].validationMessage").value("Feld 'id' fehlt."));
    }

    @Test
    void getPluginsReturnsEmptyListWhenNoPlugins() throws Exception {
        when(listPluginsUseCase.listPlugins()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/plugins"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }
}
