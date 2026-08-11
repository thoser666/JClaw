package biz.brumm.domain.port.out;

import biz.brumm.domain.model.ConversationMessage;

import java.util.List;

/**
 * Liest gespeicherte Konversationsnachrichten einer {@code contextId}.
 */
public interface ConversationStore {

    List<ConversationMessage> findByContextId(String contextId);

    void deleteByContextId(String contextId);
}
