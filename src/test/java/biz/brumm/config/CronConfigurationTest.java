package biz.brumm.config;

import biz.brumm.domain.port.out.CronJobStore;
import biz.brumm.domain.service.CronSchedulerService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class CronConfigurationTest {

    @Mock
    private CronJobStore cronJobStore;

    @Mock
    private CronProperties cronProperties;

    @InjectMocks
    private CronSchedulerService cronSchedulerService;

    @Test
    void cronSchedulerServiceCanBeInstantiated() {
        assertThat(cronSchedulerService).isNotNull();
    }
}
