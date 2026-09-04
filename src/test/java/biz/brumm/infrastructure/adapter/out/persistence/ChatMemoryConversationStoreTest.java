package biz.brumm.infrastructure.adapter.out.persistence;

import biz.brumm.domain.model.ConversationMessage;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChatMemoryConversationStoreTest {

    private final ChatMemoryRepository repository = new InMemoryChatMemoryRepository();

    @Test
    void mapsSpringAiMessagesToDomainMessages() {
        repository.saveAll("ctx-1", List.of(new SystemMessage("System"), new UserMessage("Frage")));
        ChatMemoryConversationStore store = new ChatMemoryConversationStore(repository);

        List<ConversationMessage> messages = store.findByContextId("ctx-1");

        assertThat(messages).extracting(ConversationMessage::role).containsExactly("SYSTEM", "USER");
        assertThat(messages).extracting(ConversationMessage::text).containsExactly("System", "Frage");
    }

    @Test
    void returnsEmptyForUnknownContextId() {
        ChatMemoryConversationStore store = new ChatMemoryConversationStore(repository);

        assertThat(store.findByContextId("unbekannt")).isEmpty();
    }

    @Test
    void deleteRemovesConversation() {
        repository.saveAll("ctx-1", List.of(new UserMessage("a")));
        ChatMemoryConversationStore store = new ChatMemoryConversationStore(repository);

        store.deleteByContextId("ctx-1");

        assertThat(store.findByContextId("ctx-1")).isEmpty();
    }

    @Test
    void saveAllMapsDomainMessagesBackToSpringAiMessages() {
        ChatMemoryConversationStore store = new ChatMemoryConversationStore(repository);

        store.saveAll("ctx-2", List.of(
                new ConversationMessage("SYSTEM", "System"),
                new ConversationMessage("USER", "Frage"),
                new ConversationMessage("ASSISTANT", "Antwort"),
                new ConversationMessage("UNBEKANNT", "Fallback")));

        List<ConversationMessage> read = store.findByContextId("ctx-2");
        assertThat(read).extracting(ConversationMessage::role)
                .containsExactly("SYSTEM", "USER", "ASSISTANT", "USER");
        assertThat(read).extracting(ConversationMessage::text)
                .containsExactly("System", "Frage", "Antwort", "Fallback");
    }
}
