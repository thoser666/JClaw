package biz.brumm.domain.model;

import java.util.List;

/**
 * Ergebnis einer Compaction-Operation.
 *
 * @param compactedMessages Die komprimierte Nachrichtenliste (Summary + aktuelle Nachrichten)
 * @param summary           Die erzeugte Zusammenfassung
 * @param messagesRemoved   Anzahl der entfernten Nachrichten
 * @param messagesRetained  Anzahl der beibehaltenen Nachrichten
 */
public record CompactionResult(List<String> compactedMessages, String summary,
                                int messagesRemoved, int messagesRetained) {

    public static CompactionResult of(List<String> compactedMessages, String summary,
                                       int messagesRemoved, int messagesRetained) {
        return new CompactionResult(compactedMessages, summary, messagesRemoved, messagesRetained);
    }
}
