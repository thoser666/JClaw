package biz.brumm.domain.service;

import biz.brumm.domain.model.ConversationMessage;
import biz.brumm.domain.port.in.GetConversationUseCase;
import biz.brumm.domain.port.out.ConversationStore;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ConversationQueryService implements GetConversationUseCase {

    private final ConversationStore conversationStore;
    private final CredentialLeakGuard credentialLeakGuard;

    public ConversationQueryService(ConversationStore conversationStore,
                                    CredentialLeakGuard credentialLeakGuard) {
        this.conversationStore = conversationStore;
        this.credentialLeakGuard = credentialLeakGuard;
    }

    @Override
    public List<ConversationMessage> getConversation(String contextId) {
        if (contextId == null || contextId.isBlank()) {
            return List.of();
        }
        return conversationStore.findByContextId(contextId).stream()
                .map(this::sanitize)
                .toList();
    }

    private ConversationMessage sanitize(ConversationMessage message) {
        String redacted = credentialLeakGuard.redact(message.text());
        if (redacted.equals(message.text())) {
            return message;
        }
        return new ConversationMessage(message.role(), redacted);
    }
}
