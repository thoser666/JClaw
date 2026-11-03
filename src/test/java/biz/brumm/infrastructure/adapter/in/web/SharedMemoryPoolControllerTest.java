package biz.brumm.infrastructure.adapter.in.web;

import biz.brumm.domain.model.SharedMemoryPool;
import biz.brumm.domain.service.SharedMemoryPoolService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SharedMemoryPoolController.class)
class SharedMemoryPoolControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SharedMemoryPoolService service;

    @Test
    void createPool() throws Exception {
        when(service.createPool("p1", "Pool", "")).thenReturn(
                new SharedMemoryPool("p1", "Pool", "", java.time.Instant.now()));
        mockMvc.perform(post("/api/v1/memory/pools")
                        .contentType("application/json")
                        .content("{\"id\":\"p1\",\"name\":\"Pool\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("p1"));
    }

    @Test
    void listPools() throws Exception {
        when(service.listPools()).thenReturn(List.of());
        mockMvc.perform(get("/api/v1/memory/pools"))
                .andExpect(status().isOk());
    }

    @Test
    void getUnknownPoolReturns404() throws Exception {
        when(service.getPool("nope")).thenReturn(null);
        mockMvc.perform(get("/api/v1/memory/pools/nope"))
                .andExpect(status().isNotFound());
    }
}