package biz.brumm.domain.port.out;

import biz.brumm.domain.model.CronJob;

import java.util.List;
import java.util.Optional;

/**
 * Persistenz-Schnittstelle für Cron-Jobs.
 */
public interface CronJobStore {

    Optional<CronJob> findById(String id);

    List<CronJob> findAll();

    List<CronJob> findEnabled();

    CronJob save(CronJob cronJob);

    void deleteById(String id);
}
