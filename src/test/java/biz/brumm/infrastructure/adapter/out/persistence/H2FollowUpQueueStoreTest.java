package biz.brumm.infrastructure.adapter.out.persistence;

import biz.brumm.domain.model.FollowUp;
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
class H2FollowUpQueueStoreTest {

    @Autowired
    private H2FollowUpQueueStore store;

    @Test
    void enqueueAndFindPending() {
        Instant now = Instant.now();
        FollowUp followUp = new FollowUp("f1", "s1", "Fasse zusammen", now, null);

        store.enqueue(followUp);
        List<FollowUp> pending = store.findPendingBySessionId("s1");

        assertThat(pending).extracting(FollowUp::id).containsExactly("f1");
        assertThat(pending.get(0).deliveredAt()).isNull();
    }

    @Test
    void markDeliveredExcludesFromPending() {
        Instant now = Instant.now();
        store.enqueue(new FollowUp("f2", "s1", "Weiter", now, null));

        FollowUp delivered = store.markDelivered("f2", now.plusSeconds(1));

        assertThat(delivered.deliveredAt()).isNotNull();
        assertThat(store.findPendingBySessionId("s1")).isEmpty();
    }

    @Test
    void findByIdReturnsEmptyForMissing() {
        assertThat(store.findById("nonexistent")).isEmpty();
    }

    @Test
    void deleteByIdRemovesFollowUp() {
        Instant now = Instant.now();
        store.enqueue(new FollowUp("f3", "s1", "Löschen", now, null));

        store.deleteById("f3");

        assertThat(store.findById("f3")).isEmpty();
    }
}