package biz.brumm.infrastructure.adapter.out.persistence;

import biz.brumm.domain.model.ConversationMessage;
import biz.brumm.domain.port.out.ConversationStore;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@link ConversationStore} auf Basis des Spring-AI-{@link ChatMemoryRepository}.
 * Bildet Spring-AI-{@link Message} Objekte auf die domain-unabhaengigen
 * {@link ConversationMessage} ab.
 */
@Component
public class ChatMemoryConversationStore implements ConversationStore {

    private final ChatMemoryRepository chatMemoryRepository;

    public ChatMemoryConversationStore(ChatMemoryRepository chatMemoryRepository) {
        this.chatMemoryRepository = chatMemoryRepository;
    }

    @Override
    public List<ConversationMessage> findByContextId(String contextId) {
        return chatMemoryRepository.findByConversationId(contextId).stream()
                .map(this::toDomainMessage)
                .toList();
    }

    private ConversationMessage toDomainMessage(Message message) {
        return new ConversationMessage(message.getMessageType().name(), message.getText());
    }
}
