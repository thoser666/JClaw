package biz.brumm.infrastructure.adapter.out.persistence;

import biz.brumm.domain.model.IngressCursor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class JdbcIngressCursorStoreTest {

    @Autowired
    private JdbcIngressCursorStore store;

    @Test
    void loadCursorEmptyForMissingChannel() {
        assertThat(store.loadCursor("nonexistent")).isEmpty();
    }

    @Test
    void saveAndLoadCursor() {
        Instant now = Instant.now();
        store.saveCursor(new IngressCursor("c1", "msg-42", now));

        Optional<IngressCursor> cursor = store.loadCursor("c1");

        assertThat(cursor).isPresent();
        assertThat(cursor.get().channelId()).isEqualTo("c1");
        assertThat(cursor.get().lastItemId()).isEqualTo("msg-42");
        assertThat(cursor.get().updatedAt()).isNotNull();
    }

    @Test
    void saveCursorUpdatesExisting() {
        Instant now = Instant.now();
        store.saveCursor(new IngressCursor("c1", "msg-42", now));
        store.saveCursor(new IngressCursor("c1", "msg-99", now.plusSeconds(10)));

        Optional<IngressCursor> cursor = store.loadCursor("c1");

        assertThat(cursor).isPresent();
        assertThat(cursor.get().lastItemId()).isEqualTo("msg-99");
    }

    @Test
    void admitItemNewlyAdmits() {
        assertThat(store.admitItem("c1", "item-1", Instant.now())).isTrue();
    }

    @Test
    void admitItemRejectsDuplicate() {
        Instant now = Instant.now();
        assertThat(store.admitItem("c1", "item-1", now)).isTrue();

        assertThat(store.admitItem("c1", "item-1", now)).isFalse();
    }

    @Test
    void admitItemIsIndependentPerChannel() {
        Instant now = Instant.now();
        assertThat(store.admitItem("c1", "item-1", now)).isTrue();

        assertThat(store.admitItem("c2", "item-1", now)).isTrue();
    }

    @Test
    void countItemsCountsAdmitted() {
        store.admitItem("c1", "a", Instant.now());
        store.admitItem("c1", "b", Instant.now());

        assertThat(store.countItems("c1")).isEqualTo(2);
    }

    @Test
    void pruneItemsOlderThanRemovesOldKeepsNew() {
        store.admitItem("c1", "old", Instant.now().minus(10, ChronoUnit.DAYS));
        store.admitItem("c1", "new", Instant.now());

        store.pruneItemsOlderThan("c1", Instant.now().minus(1, ChronoUnit.DAYS));

        assertThat(store.countItems("c1")).isEqualTo(1);
        assertThat(store.loadCursor("c1")).isEmpty();
        assertThat(store.admitItem("c1", "old", Instant.now())).isTrue();
    }

    @Test
    void pruneDoesNotAffectOtherChannels() {
        store.admitItem("c1", "old", Instant.now().minus(10, ChronoUnit.DAYS));
        store.admitItem("c2", "old", Instant.now().minus(10, ChronoUnit.DAYS));

        store.pruneItemsOlderThan("c1", Instant.now().minus(1, ChronoUnit.DAYS));

        assertThat(store.countItems("c1")).isZero();
        assertThat(store.countItems("c2")).isEqualTo(1);
    }
}