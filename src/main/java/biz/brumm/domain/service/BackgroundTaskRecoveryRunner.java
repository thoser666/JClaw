package biz.brumm.domain.service;

import biz.brumm.domain.port.out.BackgroundTaskStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Markiert beim Gateway-Start verwaiste RUNNING-Background-Tasks als FAILED
 * (ein Neustart beendet laufende Agent-Ausführungen).
 */
@Component
public class BackgroundTaskRecoveryRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BackgroundTaskRecoveryRunner.class);

    private final BackgroundTaskStore store;

    public BackgroundTaskRecoveryRunner(BackgroundTaskStore store) {
        this.store = store;
    }

    @Override
    public void run(ApplicationArguments args) {
        int recovered = store.markOrphanedRunningAsFailed("Gateway-Neustart: Task wurde nicht abgeschlossen.");
        if (recovered > 0) {
            log.info("{} verwaiste(r) Background-Task(s) als FAILED markiert.", recovered);
        }
    }
}