package biz.brumm.domain.service;

import biz.brumm.domain.port.out.BackgroundTaskStore;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class BackgroundTaskRecoveryRunnerTest {

    @Test
    void recoversOrphanedRunningTasksOnStartup() {
        BackgroundTaskStore store = mock(BackgroundTaskStore.class);
        BackgroundTaskRecoveryRunner runner = new BackgroundTaskRecoveryRunner(store);

        runner.run(null);

        verify(store).markOrphanedRunningAsFailed(anyString());
    }
}