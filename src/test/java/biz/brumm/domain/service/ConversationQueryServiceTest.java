package biz.brumm.domain.service;

import biz.brumm.domain.model.ConversationMessage;
import biz.brumm.domain.port.out.ConversationStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConversationQueryServiceTest {

    @Mock
    private ConversationStore conversationStore;

    @Test
    void getConversationReturnsStoredMessages() {
        ConversationQueryService service = new ConversationQueryService(conversationStore);
        when(conversationStore.findByContextId("ctx-1")).thenReturn(List.of(
                new ConversationMessage("USER", "Hallo"),
                new ConversationMessage("ASSISTANT", "Hi!")));

        List<ConversationMessage> messages = service.getConversation("ctx-1");

        assertThat(messages).extracting(ConversationMessage::role).containsExactly("USER", "ASSISTANT");
        assertThat(messages).extracting(ConversationMessage::text).containsExactly("Hallo", "Hi!");
    }

    @Test
    void getConversationWithBlankContextIdReturnsEmpty() {
        ConversationQueryService service = new ConversationQueryService(conversationStore);

        assertThat(service.getConversation(" ")).isEmpty();
        assertThat(service.getConversation(null)).isEmpty();
    }

    @Test
    void getConversationReturnsEmptyForUnknownContext() {
        ConversationQueryService service = new ConversationQueryService(conversationStore);
        when(conversationStore.findByContextId("unbekannt")).thenReturn(List.of());

        assertThat(service.getConversation("unbekannt")).isEmpty();
    }
}
