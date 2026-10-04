package biz.brumm.infrastructure.adapter.out.persistence;

import biz.brumm.domain.model.BackgroundTask;
import biz.brumm.domain.model.BackgroundTaskStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class H2BackgroundTaskStoreTest {

    @Autowired
    private H2BackgroundTaskStore store;

    private BackgroundTask pendingTask(String id, String sessionId, Instant now) {
        return new BackgroundTask(id, sessionId, "Prompt", BackgroundTaskStatus.PENDING, null, null, now, null, null);
    }

    @Test
    void saveAndFindById() {
        Instant now = Instant.now();
        store.save(pendingTask("bt1", "s1", now));

        Optional<BackgroundTask> found = store.findById("bt1");

        assertThat(found).isPresent();
        assertThat(found.get().sessionId()).isEqualTo("s1");
        assertThat(found.get().status()).isEqualTo(BackgroundTaskStatus.PENDING);
    }

    @Test
    void findBySessionIdOrdersNewestFirst() {
        Instant now = Instant.now();
        store.save(pendingTask("bt-old", "s1", now.minusSeconds(60)));
        store.save(pendingTask("bt-new", "s1", now));

        List<BackgroundTask> tasks = store.findBySessionId("s1");

        assertThat(tasks).extracting(BackgroundTask::id).containsExactly("bt-new", "bt-old");
    }

    @Test
    void findByIdReturnsEmptyForMissing() {
        assertThat(store.findById("nonexistent")).isEmpty();
    }

    @Test
    void saveUpdatesExistingTask() {
        Instant now = Instant.now();
        store.save(pendingTask("bt-upd", "s1", now));

        store.save(new BackgroundTask("bt-upd", "s1", "Prompt", BackgroundTaskStatus.FAILED,
                null, "Kaputt", now, null, now));

        BackgroundTask updated = store.findById("bt-upd").orElseThrow();
        assertThat(updated.status()).isEqualTo(BackgroundTaskStatus.FAILED);
        assertThat(updated.error()).isEqualTo("Kaputt");
    }

    @Test
    void markOrphanedRunningAsFailedFlipsAndCounts() {
        Instant now = Instant.now();
        store.save(new BackgroundTask("bt-run", "s1", "Prompt", BackgroundTaskStatus.RUNNING, null, null, now, now, null));
        store.save(pendingTask("bt-pend", "s1", now));

        int recovered = store.markOrphanedRunningAsFailed("Neustart");

        assertThat(recovered).isEqualTo(1);
        assertThat(store.findById("bt-run").orElseThrow().status()).isEqualTo(BackgroundTaskStatus.FAILED);
        assertThat(store.findById("bt-pend").orElseThrow().status()).isEqualTo(BackgroundTaskStatus.PENDING);
    }
}