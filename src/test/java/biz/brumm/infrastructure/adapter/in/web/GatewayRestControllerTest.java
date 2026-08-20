package biz.brumm.infrastructure.adapter.in.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(GatewayRestController.class)
class GatewayRestControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void getStatusReturnsRunningStatus() throws Exception {
        mockMvc.perform(get("/api/v1/gateway/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("running"))
                .andExpect(jsonPath("$.startedAt").isNotEmpty())
                .andExpect(jsonPath("$.uptime").isNumber());
    }

    @Test
    void getInfoReturnsSystemInformation() throws Exception {
        mockMvc.perform(get("/api/v1/gateway/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("jclaw"))
                .andExpect(jsonPath("$.serverPort").isNumber())
                .andExpect(jsonPath("$.config.maxIterations").isNumber())
                .andExpect(jsonPath("$.config.sessionResetMode").isNotEmpty());
    }
}
