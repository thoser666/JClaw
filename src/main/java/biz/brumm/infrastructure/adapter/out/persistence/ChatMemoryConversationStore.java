package biz.brumm.infrastructure.adapter.out.persistence;

import biz.brumm.domain.model.ConversationMessage;
import biz.brumm.domain.port.out.ConversationStore;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@link ConversationStore} auf Basis des Spring-AI-{@link ChatMemoryRepository}.
 * Bildet Spring-AI-{@link Message} Objekte auf die domain-unabhaengigen
 * {@link ConversationMessage} ab und zurück.
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

    @Override
    public void saveAll(String contextId, List<ConversationMessage> messages) {
        List<Message> springMessages = messages.stream()
                .map(this::toSpringMessage)
                .toList();
        chatMemoryRepository.saveAll(contextId, springMessages);
    }

    @Override
    public void deleteByContextId(String contextId) {
        chatMemoryRepository.deleteByConversationId(contextId);
    }

    private ConversationMessage toDomainMessage(Message message) {
        return new ConversationMessage(message.getMessageType().name(), message.getText());
    }

    private Message toSpringMessage(ConversationMessage message) {
        String role = message.role() == null ? "" : message.role().toUpperCase();
        String text = message.text() == null ? "" : message.text();
        MessageType type;
        try {
            type = MessageType.valueOf(role);
        } catch (IllegalArgumentException e) {
            type = MessageType.USER;
        }
        return switch (type) {
            case SYSTEM -> new SystemMessage(text);
            case ASSISTANT -> new AssistantMessage(text);
            default -> new UserMessage(text);
        };
    }
}
