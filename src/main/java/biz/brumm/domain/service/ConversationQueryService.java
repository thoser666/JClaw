package biz.brumm.domain.service;

import biz.brumm.domain.model.ConversationMessage;
import biz.brumm.domain.port.in.GetConversationUseCase;
import biz.brumm.domain.port.out.ConversationStore;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ConversationQueryService implements GetConversationUseCase {

    private final ConversationStore conversationStore;

    public ConversationQueryService(ConversationStore conversationStore) {
        this.conversationStore = conversationStore;
    }

    @Override
    public List<ConversationMessage> getConversation(String contextId) {
        if (contextId == null || contextId.isBlank()) {
            return List.of();
        }
        return conversationStore.findByContextId(contextId);
    }
}
