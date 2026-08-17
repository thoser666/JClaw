package biz.brumm.infrastructure.adapter.out.persistence;

import biz.brumm.domain.model.Session;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class H2SessionStoreTest {

    @Autowired
    private H2SessionStore store;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void saveAndFindById() {
        Instant now = Instant.now();
        Session session = new Session("test-id", "Test Session", now, now, now);

        store.save(session);
        Optional<Session> found = store.findById("test-id");

        assertThat(found).isPresent();
        assertThat(found.get().sessionId()).isEqualTo("test-id");
        assertThat(found.get().displayName()).isEqualTo("Test Session");
    }

    @Test
    void findByIdReturnsEmptyForMissing() {
        assertThat(store.findById("nonexistent")).isEmpty();
    }

    @Test
    void findAllReturnsSessionsOrderedByLastInteractionDesc() {
        Instant now = Instant.now();
        Session s1 = new Session("s1", "Older", now.minus(1, ChronoUnit.HOURS), now.minus(1, ChronoUnit.HOURS), now.minus(1, ChronoUnit.HOURS));
        Session s2 = new Session("s2", "Newer", now, now, now);
        store.save(s1);
        store.save(s2);

        List<Session> all = store.findAll();

        assertThat(all).hasSize(2);
        assertThat(all.get(0).sessionId()).isEqualTo("s2");
        assertThat(all.get(1).sessionId()).isEqualTo("s1");
    }

    @Test
    void saveUpdatesExistingSession() {
        Instant now = Instant.now();
        store.save(new Session("u1", null, now, now, now));
        store.save(new Session("u1", "Updated", now, now, now));

        Session found = store.findById("u1").orElseThrow();
        assertThat(found.displayName()).isEqualTo("Updated");
    }

    @Test
    void deleteByIdRemovesSession() {
        Instant now = Instant.now();
        store.save(new Session("del1", "Delete Me", now, now, now));

        store.deleteById("del1");

        assertThat(store.findById("del1")).isEmpty();
    }

    @Test
    void deleteByIdDoesNothingForMissing() {
        store.deleteById("nonexistent");
    }

    @Test
    void saveAndFindWithNullDisplayName() {
        Instant now = Instant.now();
        store.save(new Session("nd1", null, now, now, now));

        Session found = store.findById("nd1").orElseThrow();
        assertThat(found.displayName()).isNull();
    }

    @Test
    void chatMessageTableStillExists() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM chat_message WHERE 1=0", Integer.class);
        assertThat(count).isNotNull();
    }
}
