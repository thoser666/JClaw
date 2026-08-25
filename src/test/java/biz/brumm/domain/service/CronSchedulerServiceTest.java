package biz.brumm.domain.service;

import biz.brumm.config.CronProperties;
import biz.brumm.domain.model.CronJob;
import biz.brumm.domain.port.out.CronJobStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CronSchedulerServiceTest {

    @Mock
    private CronJobStore cronJobStore;

    private CronSchedulerService schedulerService;

    private CronProperties cronProperties;

    @BeforeEach
    void setUp() {
        cronProperties = CronProperties.of(true, 60, 3);
        schedulerService = new CronSchedulerService(cronJobStore, cronProperties);
    }

    @Test
    void createJobCalculatesNextRunAndSaves() {
        when(cronJobStore.save(any(CronJob.class))).thenAnswer(inv -> inv.getArgument(0));

        CronJob saved = schedulerService.createJob("Test", "0 */6 * * *", "Prompt", "ctx-1");

        assertThat(saved.id()).isNotBlank();
        assertThat(saved.name()).isEqualTo("Test");
        assertThat(saved.cronExpression()).isEqualTo("0 */6 * * *");
        assertThat(saved.prompt()).isEqualTo("Prompt");
        assertThat(saved.contextId()).isEqualTo("ctx-1");
        assertThat(saved.enabled()).isTrue();
        assertThat(saved.nextRunAt()).isNotNull();

        verify(cronJobStore).save(any(CronJob.class));
    }

    @Test
    void executeJobCallsListenersAndSaves() {
        AtomicBoolean listenerCalled = new AtomicBoolean(false);
        AtomicReference<String> receivedPrompt = new AtomicReference<>();
        schedulerService.addListener((prompt, contextId) -> {
            listenerCalled.set(true);
            receivedPrompt.set(prompt);
        });

        Instant future = Instant.now().plusSeconds(3600);
        CronJob job = new CronJob("1", "Test", "0 */6 * * *", "Test prompt", "ctx-1",
                true, null, future, Instant.now());
        when(cronJobStore.save(any())).thenReturn(job);

        schedulerService.executeJob(job);

        assertThat(listenerCalled.get()).isTrue();
        assertThat(receivedPrompt.get()).isEqualTo("Test prompt");
        verify(cronJobStore).save(any(CronJob.class));
    }

    @Test
    void checkAndExecuteRunsDueJobs() {
        Instant past = Instant.now().minusSeconds(10);
        CronJob dueJob = new CronJob("1", "Due", "0 */6 * * *", "Prompt", null,
                true, null, past, Instant.now());
        when(cronJobStore.findEnabled()).thenReturn(List.of(dueJob));
        when(cronJobStore.save(any())).thenReturn(dueJob);

        schedulerService.checkAndExecute();

        verify(cronJobStore).save(any(CronJob.class));
    }

    @Test
    void checkAndExecuteSkipsFutureJobs() {
        Instant future = Instant.now().plusSeconds(3600);
        CronJob futureJob = new CronJob("1", "Future", "0 */6 * * *", "Prompt", null,
                true, null, future, Instant.now());
        when(cronJobStore.findEnabled()).thenReturn(List.of(futureJob));

        schedulerService.checkAndExecute();

        verify(cronJobStore, never()).save(any());
    }

    @Test
    void recalculateNextRunUpdatesNextRunAt() {
        CronJob job = new CronJob("1", "Test", "0 */6 * * *", "Prompt", null,
                true, null, null, Instant.now());
        CronJob updated = schedulerService.recalculateNextRun(job);

        assertThat(updated.nextRunAt()).isNotNull();
        assertThat(updated.nextRunAt()).isAfter(Instant.now().minusSeconds(1));
    }

    @Test
    void addListenerIsCalledOnExecute() {
        List<String> calls = new ArrayList<>();
        schedulerService.addListener((prompt, ctx) -> calls.add(prompt));
        schedulerService.addListener((prompt, ctx) -> calls.add("second:" + prompt));

        Instant future = Instant.now().plusSeconds(3600);
        CronJob job = new CronJob("1", "Test", "0 */6 * * *", "P", null,
                true, null, future, Instant.now());
        when(cronJobStore.save(any())).thenReturn(job);

        schedulerService.executeJob(job);

        assertThat(calls).containsExactly("P", "second:P");
    }
}
