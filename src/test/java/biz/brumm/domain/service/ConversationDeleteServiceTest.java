package biz.brumm.domain.service;

import biz.brumm.domain.port.out.ConversationStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class ConversationDeleteServiceTest {

    @Mock
    private ConversationStore conversationStore;

    @Test
    void deleteConversationDelegatesToStore() {
        ConversationDeleteService service = new ConversationDeleteService(conversationStore);

        service.deleteConversation("ctx-1");

        verify(conversationStore).deleteByContextId("ctx-1");
    }

    @Test
    void deleteConversationWithBlankContextIdIsNoOp() {
        ConversationDeleteService service = new ConversationDeleteService(conversationStore);

        service.deleteConversation(" ");
        service.deleteConversation(null);

        verifyNoInteractions(conversationStore);
    }
}
