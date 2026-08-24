package biz.brumm.infrastructure.adapter.out.ai;

import biz.brumm.config.CompactionProperties;
import biz.brumm.domain.model.CompactionResult;
import biz.brumm.domain.service.CompactionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LlmCompactionServiceTest {

    @Mock
    private ChatModel chatModel;

    @Test
    void isNotNeededWhenDisabled() {
        CompactionProperties props = new CompactionProperties(false, 10, 4, null);
        CompactionService service = new LlmCompactionService(chatModel, props);

        List<Message> messages = createMessages(15);
        assertThat(service.isCompactionNeeded(messages)).isFalse();
    }

    @Test
    void isNotNeededWhenBelowThreshold() {
        CompactionProperties props = new CompactionProperties(true, 20, 4, null);
        CompactionService service = new LlmCompactionService(chatModel, props);

        List<Message> messages = createMessages(10);
        assertThat(service.isCompactionNeeded(messages)).isFalse();
    }

    @Test
    void isNeededWhenAboveThreshold() {
        CompactionProperties props = new CompactionProperties(true, 10, 4, null);
        CompactionService service = new LlmCompactionService(chatModel, props);

        List<Message> messages = createMessages(15);
        assertThat(service.isCompactionNeeded(messages)).isTrue();
    }

    @Test
    void compactSummarizesOlderMessages() {
        CompactionProperties props = new CompactionProperties(true, 5, 2, "Fasse zusammen.");
        CompactionService service = new LlmCompactionService(chatModel, props);

        ChatResponse chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Zusammenfassung der Konversation."))));
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse);

        List<Message> messages = createMessages(10);
        CompactionResult result = service.compact(messages);

        assertThat(result.summary()).isEqualTo("Zusammenfassung der Konversation.");
        assertThat(result.messagesRemoved()).isEqualTo(8);
        assertThat(result.messagesRetained()).isEqualTo(2);
        assertThat(result.compactedMessages()).hasSize(3); // summary + 2 retained
    }

    @Test
    void compactRetainsSystemMessage() {
        CompactionProperties props = new CompactionProperties(true, 3, 2, "Fasse zusammen.");
        CompactionService service = new LlmCompactionService(chatModel, props);

        ChatResponse chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Summary."))));
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse);

        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage("Du bist ein Assistent."));
        for (int i = 0; i < 6; i++) {
            messages.add(new UserMessage("Nachricht " + i));
        }

        CompactionResult result = service.compact(messages);

        // SystemMessage + summary + 2 retained = 4
        assertThat(result.compactedMessages()).hasSize(4);
        assertThat(result.compactedMessages().get(0)).contains("Assistent");
    }

    @Test
    void compactNoOpWhenBelowRetainCount() {
        CompactionProperties props = new CompactionProperties(true, 10, 10, "Fasse zusammen.");
        CompactionService service = new LlmCompactionService(chatModel, props);

        List<Message> messages = createMessages(5);
        CompactionResult result = service.compact(messages);

        assertThat(result.messagesRemoved()).isZero();
        assertThat(result.compactedMessages()).hasSize(5);
    }

    private List<Message> createMessages(int count) {
        List<Message> messages = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            messages.add(new UserMessage("Nachricht " + i));
        }
        return messages;
    }
}
