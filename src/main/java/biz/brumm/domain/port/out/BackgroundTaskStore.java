package biz.brumm.domain.port.out;

import biz.brumm.domain.model.BackgroundTask;

import java.util.List;
import java.util.Optional;

public interface BackgroundTaskStore {

    BackgroundTask save(BackgroundTask task);

    Optional<BackgroundTask> findById(String id);

    List<BackgroundTask> findBySessionId(String sessionId);

    /**
     * Markiert nach einem Gateway-Neustart verwaiste RUNNING-Tasks als FAILED.
     *
     * @return Anzahl der wiederhergestellten Tasks
     */
    int markOrphanedRunningAsFailed(String message);
}