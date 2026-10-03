package biz.brumm.infrastructure.adapter.out.persistence;

import biz.brumm.domain.model.GoalStatus;
import biz.brumm.domain.model.SessionGoal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class H2GoalStoreTest {

    @Autowired
    private H2GoalStore store;

    @Test
    void saveAndFindBySessionId() {
        Instant now = Instant.now();
        SessionGoal goal = new SessionGoal("g1", "Schreibe README", GoalStatus.ACTIVE, null, null, now, null);

        store.save(goal);
        Optional<SessionGoal> found = store.findBySessionId("g1");

        assertThat(found).isPresent();
        assertThat(found.get().sessionId()).isEqualTo("g1");
        assertThat(found.get().objective()).isEqualTo("Schreibe README");
        assertThat(found.get().status()).isEqualTo(GoalStatus.ACTIVE);
    }

    @Test
    void saveUpdatesExistingGoal() {
        Instant now = Instant.now();
        store.save(new SessionGoal("g2", "Altes Ziel", GoalStatus.ACTIVE, null, null, now, null));

        store.save(new SessionGoal("g2", "Neues Ziel", GoalStatus.ACTIVE, null, null, now, null));

        SessionGoal found = store.findBySessionId("g2").orElseThrow();
        assertThat(found.objective()).isEqualTo("Neues Ziel");
    }

    @Test
    void deleteBySessionIdRemovesGoal() {
        Instant now = Instant.now();
        store.save(new SessionGoal("g3", "Löschen", GoalStatus.ACTIVE, null, null, now, null));

        store.deleteBySessionId("g3");

        assertThat(store.findBySessionId("g3")).isEmpty();
    }

    @Test
    void findBySessionIdReturnsEmptyForMissing() {
        assertThat(store.findBySessionId("nonexistent")).isEmpty();
    }
}