package biz.brumm.domain.service;

import biz.brumm.domain.model.CompactionResult;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * Komprimiert den Konversationskontext, wenn Session-Grenzen erreicht werden.
 * Ersetzt ältere Nachrichten durch eine vom LLM erzeugte Zusammenfassung.
 */
public interface CompactionService {

    /**
     * Prüft, ob Compaction für die gegebene Nachrichtenliste nötig ist.
     */
    boolean isCompactionNeeded(List<Message> messages);

    /**
     * Komprimiert die Nachrichtenliste: Ersetzt ältere Nachrichten durch eine Zusammenfassung.
     *
     * @param messages Die aktuelle Nachrichtenliste (inkl. SystemMessage)
     * @return Die komprimierte Nachrichtenliste
     */
    CompactionResult compact(List<Message> messages);
}
