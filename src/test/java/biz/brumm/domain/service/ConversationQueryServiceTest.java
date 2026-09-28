package biz.brumm.domain.service;

import biz.brumm.config.GuardrailProperties;
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

    private ConversationQueryService service(GuardrailProperties guardrailProperties) {
        return new ConversationQueryService(conversationStore,
                new CredentialLeakGuard(guardrailProperties, null));
    }

    @Test
    void getConversationReturnsStoredMessages() {
        ConversationQueryService service = service(new GuardrailProperties(false, List.of()));
        when(conversationStore.findByContextId("ctx-1")).thenReturn(List.of(
                new ConversationMessage("USER", "Hallo"),
                new ConversationMessage("ASSISTANT", "Hi!")));

        List<ConversationMessage> messages = service.getConversation("ctx-1");

        assertThat(messages).extracting(ConversationMessage::role).containsExactly("USER", "ASSISTANT");
        assertThat(messages).extracting(ConversationMessage::text).containsExactly("Hallo", "Hi!");
    }

    @Test
    void getConversationWithBlankContextIdReturnsEmpty() {
        ConversationQueryService service = service(new GuardrailProperties(false, List.of()));

        assertThat(service.getConversation(" ")).isEmpty();
        assertThat(service.getConversation(null)).isEmpty();
    }

    @Test
    void getConversationReturnsEmptyForUnknownContext() {
        ConversationQueryService service = service(new GuardrailProperties(false, List.of()));
        when(conversationStore.findByContextId("unbekannt")).thenReturn(List.of());

        assertThat(service.getConversation("unbekannt")).isEmpty();
    }

    @Test
    void getConversationRedactsKnownSecretFromMessageTexts() {
        ConversationQueryService service = service(
                new GuardrailProperties(true, List.of("TOPSECRET")));
        when(conversationStore.findByContextId("ctx-1")).thenReturn(List.of(
                new ConversationMessage("USER", "Passwort TOPSECRET im Chat"),
                new ConversationMessage("ASSISTANT", "Ok, [REDACTED] gemeint")));

        List<ConversationMessage> messages = service.getConversation("ctx-1");

        assertThat(messages.get(0).text()).isEqualTo("Passwort [REDACTED] im Chat");
        assertThat(messages.get(1).text()).isEqualTo("Ok, [REDACTED] gemeint");
    }
}