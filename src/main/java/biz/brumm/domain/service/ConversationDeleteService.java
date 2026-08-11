package biz.brumm.domain.service;

import biz.brumm.domain.port.in.DeleteConversationUseCase;
import biz.brumm.domain.port.out.ConversationStore;
import org.springframework.stereotype.Service;

@Service
public class ConversationDeleteService implements DeleteConversationUseCase {

    private final ConversationStore conversationStore;

    public ConversationDeleteService(ConversationStore conversationStore) {
        this.conversationStore = conversationStore;
    }

    @Override
    public void deleteConversation(String contextId) {
        if (contextId == null || contextId.isBlank()) {
            return;
        }
        conversationStore.deleteByContextId(contextId);
    }
}
