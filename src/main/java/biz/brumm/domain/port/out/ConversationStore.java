package biz.brumm.domain.port.out;

import biz.brumm.domain.model.ConversationMessage;

import java.util.List;

/**
 * Liest und schreibt gespeicherte Konversationsnachrichten einer {@code contextId}.
 */
public interface ConversationStore {

    List<ConversationMessage> findByContextId(String contextId);

    /**
     * Ersetzt die gespeicherten Nachrichten einer {@code contextId} vollständig
     * (wird u. a. vom Memory-Vault-Read-Back für User-Änderungen genutzt).
     */
    void saveAll(String contextId, List<ConversationMessage> messages);

    void deleteByContextId(String contextId);
}
