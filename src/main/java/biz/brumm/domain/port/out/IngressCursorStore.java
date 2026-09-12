package biz.brumm.domain.port.out;

import biz.brumm.domain.model.IngressCursor;

import java.time.Instant;
import java.util.Optional;

/**
 * Dauerhafter Speicher für Channel-Ingress-Zustand (P3-08).
 * <p>
 * Kapselt die <b>durable admission</b> von eingehenden Nachrichten: einen
 * Polling-Checkpoint (Cursor) sowie die Menge der bereits übernommenen Items
 * (Deduplizierung über den Neustart hinweg).
 */
public interface IngressCursorStore {

    /**
     * Lädt den zuletzt gespeicherten Cursor eines Channels.
     */
    Optional<IngressCursor> loadCursor(String channelId);

    /**
     * Speichert den Cursor eines Channels (insert-or-update).
     */
    void saveCursor(IngressCursor cursor);

    /**
     * Nimmt ein Item dauerhaft an (durable admission).
     *
     * @return {@code true}, wenn das Item neu ist und übernommen werden darf;
     *         {@code false}, wenn es bereits bekannt ist (Duplikat).
     */
    boolean admitItem(String channelId, String itemId, Instant seenAt);

    /**
     * Löscht angenommene Items eines Channels, die älter als {@code olderThan} sind (pruning).
     */
    void pruneItemsOlderThan(String channelId, Instant olderThan);

    /**
     * Anzahl der aktuell gespeicherten Items eines Channels (für Tests/Pruning-Diagnose).
     */
    int countItems(String channelId);
}