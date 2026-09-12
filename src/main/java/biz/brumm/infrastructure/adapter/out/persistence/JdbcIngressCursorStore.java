package biz.brumm.infrastructure.adapter.out.persistence;

import biz.brumm.domain.model.IngressCursor;
import biz.brumm.domain.port.out.IngressCursorStore;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * H2/JDBC-Implementierung des {@link IngressCursorStore} (P3-08).
 * <p>
 * Tabellen: {@code channel_ingress} (Cursor je Channel) und
 * {@code channel_ingress_item} (dauerhaft angenommene Items, PK (channel_id, item_id)).
 */
@Repository
public class JdbcIngressCursorStore implements IngressCursorStore {

    private final JdbcTemplate jdbc;

    public JdbcIngressCursorStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<IngressCursor> loadCursor(String channelId) {
        List<IngressCursor> results = jdbc.query(
                "SELECT channel_id, last_item_id, updated_at FROM channel_ingress WHERE channel_id = ?",
                (rs, rowNum) -> new IngressCursor(
                        rs.getString("channel_id"),
                        rs.getString("last_item_id"),
                        rs.getTimestamp("updated_at").toInstant()),
                channelId);
        return results.stream().findFirst();
    }

    @Override
    public void saveCursor(IngressCursor cursor) {
        int updated = jdbc.update(
                "UPDATE channel_ingress SET last_item_id = ?, updated_at = ? WHERE channel_id = ?",
                cursor.lastItemId(), toTimestamp(cursor.updatedAt()), cursor.channelId());
        if (updated == 0) {
            jdbc.update(
                    "INSERT INTO channel_ingress (channel_id, last_item_id, updated_at) VALUES (?, ?, ?)",
                    cursor.channelId(), cursor.lastItemId(), toTimestamp(cursor.updatedAt()));
        }
    }

    @Override
    public boolean admitItem(String channelId, String itemId, Instant seenAt) {
        try {
            return jdbc.update(
                    "INSERT INTO channel_ingress_item (channel_id, item_id, seen_at) VALUES (?, ?, ?)",
                    channelId, itemId, toTimestamp(seenAt)) > 0;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    @Override
    public void pruneItemsOlderThan(String channelId, Instant olderThan) {
        jdbc.update(
                "DELETE FROM channel_ingress_item WHERE channel_id = ? AND seen_at < ?",
                channelId, toTimestamp(olderThan));
    }

    @Override
    public int countItems(String channelId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM channel_ingress_item WHERE channel_id = ?",
                Integer.class, channelId);
        return count == null ? 0 : count;
    }

    private static java.sql.Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : java.sql.Timestamp.from(instant);
    }
}