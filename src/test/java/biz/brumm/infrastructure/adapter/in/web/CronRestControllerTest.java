package biz.brumm.infrastructure.adapter.in.web;

import biz.brumm.domain.model.CronJob;
import biz.brumm.domain.port.out.CronJobStore;
import biz.brumm.domain.service.CronSchedulerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(CronRestController.class)
class CronRestControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CronJobStore cronJobStore;

    @MockitoBean
    private CronSchedulerService cronSchedulerService;

    @Test
    void listReturnsAllJobs() throws Exception {
        CronJob job = new CronJob("1", "Test Job", "0 */6 * * *", "Test prompt", null,
                true, null, Instant.now(), Instant.now());
        when(cronJobStore.findAll()).thenReturn(List.of(job));

        mockMvc.perform(get("/api/v1/cron-jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("1"))
                .andExpect(jsonPath("$[0].name").value("Test Job"))
                .andExpect(jsonPath("$[0].cronExpression").value("0 */6 * * *"));
    }

    @Test
    void getByIdReturnsJob() throws Exception {
        CronJob job = new CronJob("1", "Test", "0 */6 * * *", "prompt", null,
                true, null, Instant.now(), Instant.now());
        when(cronJobStore.findById("1")).thenReturn(Optional.of(job));

        mockMvc.perform(get("/api/v1/cron-jobs/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("1"));
    }

    @Test
    void getByIdReturns404WhenNotFound() throws Exception {
        when(cronJobStore.findById("999")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/cron-jobs/999"))
                .andExpect(status().isNotFound());
    }

    @Test
    void createValidJobReturnsJob() throws Exception {
        CronJob saved = new CronJob("new-id", "New Job", "0 9 * * *", "Hello", null,
                true, null, Instant.now(), Instant.now());
        when(cronSchedulerService.createJob("New Job", "0 9 * * *", "Hello", null)).thenReturn(saved);

        mockMvc.perform(post("/api/v1/cron-jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"New Job\",\"cronExpression\":\"0 9 * * *\",\"prompt\":\"Hello\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("new-id"))
                .andExpect(jsonPath("$.name").value("New Job"));
    }

    @Test
    void createMissingNameReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/cron-jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cronExpression\":\"0 9 * * *\",\"prompt\":\"Hello\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void createInvalidCronReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/cron-jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Test\",\"cronExpression\":\"invalid\",\"prompt\":\"Hello\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("Ung")));
    }

    @Test
    void deleteExistingJobReturnsDeleted() throws Exception {
        CronJob job = new CronJob("1", "Test", "0 * * * *", "prompt", null,
                true, null, Instant.now(), Instant.now());
        when(cronJobStore.findById("1")).thenReturn(Optional.of(job));

        mockMvc.perform(delete("/api/v1/cron-jobs/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true));

        verify(cronJobStore).deleteById("1");
    }

    @Test
    void deleteNotFoundReturns404() throws Exception {
        when(cronJobStore.findById("999")).thenReturn(Optional.empty());

        mockMvc.perform(delete("/api/v1/cron-jobs/999"))
                .andExpect(status().isNotFound());
    }
}
