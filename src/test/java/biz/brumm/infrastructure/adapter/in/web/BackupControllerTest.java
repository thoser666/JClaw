package biz.brumm.infrastructure.adapter.in.web;

import biz.brumm.domain.service.BackupService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(BackupController.class)
class BackupControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BackupService backupService;

    @Test
    void createReturnsArchive() throws Exception {
        when(backupService.createBackup()).thenReturn(Path.of("/tmp/backup.zip"));
        mockMvc.perform(post("/api/v1/backup/create"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(true));
    }

    @Test
    void listReturnsBackups() throws Exception {
        when(backupService.listBackups()).thenReturn(List.of());
        mockMvc.perform(get("/api/v1/backup"))
                .andExpect(status().isOk());
    }

    @Test
    void verifyReturnsValidity() throws Exception {
        when(backupService.verifyBackup(Path.of("/tmp/x.zip"))).thenReturn(true);
        mockMvc.perform(post("/api/v1/backup/verify").param("path", "/tmp/x.zip"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));
    }
}