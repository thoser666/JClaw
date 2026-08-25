package biz.brumm.config;

import biz.brumm.domain.port.out.CronJobStore;
import biz.brumm.domain.service.CronSchedulerService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring-Konfiguration für das Cron-Job-System (P1-12).
 * {@link CronSchedulerService} ist immer verfügbar (für REST-API und Tests).
 * Der Scheduler-Thread wird nur gestartet, wenn {@code jclaw.cron.enabled=true}.
 */
@Configuration
public class CronConfiguration {

    @Bean
    public CronSchedulerService cronSchedulerService(CronJobStore cronJobStore, CronProperties cronProperties) {
        return new CronSchedulerService(cronJobStore, cronProperties);
    }
}
