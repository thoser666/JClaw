package biz.brumm.infrastructure.adapter.out.ai;

import biz.brumm.domain.model.AgentCommand;
import biz.brumm.domain.model.AgentResponse;
import biz.brumm.domain.model.ToolInvocation;
import biz.brumm.domain.port.out.AgentTool;
import biz.brumm.domain.service.AgentLoopLimitExceededException;
import biz.brumm.infrastructure.adapter.out.ai.tool.CalculatorTool;
import biz.brumm.infrastructure.adapter.out.ai.tool.DateTimeTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.ollama.api.OllamaChatOptions;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OllamaAiAdapterTest {

    @Mock
    private ChatModel chatModel;

    @Mock
    private ToolCallingManager toolCallingManager;

    private static final String TOOL_CALL_ID = "call_123";

    private OllamaAiAdapter adapter(List<AgentTool> tools) {
        return adapter(tools, newMemory());
    }

    private OllamaAiAdapter adapter(List<AgentTool> tools, ChatMemory memory) {
        when(chatModel.getDefaultOptions()).thenReturn(OllamaChatOptions.builder().model("qwen3:8b").build());
        return new OllamaAiAdapter(chatModel, toolCallingManager, tools, memory);
    }

    private MessageWindowChatMemory newMemory() {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(20)
                .build();
    }

    @Test
    void executeWithoutToolCallsReturnsAnswerInSingleIteration() {
        when(chatModel.call(any(Prompt.class))).thenReturn(finalResponse("Hallo!"));
        OllamaAiAdapter adapter = adapter(List.of(new DateTimeTool()));

        AgentResponse response = adapter.execute(new AgentCommand("Hallo", null), "System", 5);

        assertThat(response.content()).isEqualTo("Hallo!");
        assertThat(response.iterations()).isEqualTo(1);
        assertThat(response.toolInvocations()).isEmpty();
        verify(chatModel, times(1)).call(any(Prompt.class));
        verify(toolCallingManager, times(0)).executeToolCalls(any(), any());
    }

    @Test
    void executeWithToolCallRunsLoopAndRecordsInvocation() {
        AssistantMessage toolCallMessage = toolCallMessage();
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(toolCallMessage))))
                .thenReturn(finalResponse("Es ist 10:00 Uhr."));

        ToolResponseMessage toolResponse = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(
                        TOOL_CALL_ID, "getCurrentDateTime", "2026-08-06T10:00:00+02:00")))
                .build();
        ToolExecutionResult executionResult = ToolExecutionResult.builder()
                .conversationHistory(List.of(
                        new SystemMessage("System"),
                        new UserMessage("Wie spät ist es?"),
                        toolCallMessage,
                        toolResponse))
                .build();
        when(toolCallingManager.executeToolCalls(any(Prompt.class), any(ChatResponse.class)))
                .thenReturn(executionResult);

        OllamaAiAdapter adapter = adapter(List.of(new DateTimeTool(), new CalculatorTool()));

        AgentResponse response = adapter.execute(new AgentCommand("Wie spät ist es?", null), "System", 5);

        assertThat(response.content()).isEqualTo("Es ist 10:00 Uhr.");
        assertThat(response.iterations()).isEqualTo(2);
        assertThat(response.toolInvocations()).hasSize(1);
        ToolInvocation invocation = response.toolInvocations().get(0);
        assertThat(invocation.name()).isEqualTo("getCurrentDateTime");
        assertThat(invocation.arguments()).isEqualTo("{}");
        assertThat(invocation.result()).isEqualTo("2026-08-06T10:00:00+02:00");
        verify(chatModel, times(2)).call(any(Prompt.class));
        verify(toolCallingManager, times(1)).executeToolCalls(any(Prompt.class), any(ChatResponse.class));
    }

    @Test
    void executeRecordsMissingToolResultAsNoResult() {
        AssistantMessage toolCallMessage = toolCallMessage();
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(toolCallMessage))))
                .thenReturn(finalResponse("fertig"));

        ToolExecutionResult executionResult = ToolExecutionResult.builder()
                .conversationHistory(List.of(
                        new SystemMessage("System"),
                        new UserMessage("Frage"),
                        toolCallMessage,
                        ToolResponseMessage.builder()
                                .responses(List.of(new ToolResponseMessage.ToolResponse(
                                        "call_999", "getCurrentDateTime", "2026-08-06T10:00:00+02:00")))
                                .build()))
                .build();
        when(toolCallingManager.executeToolCalls(any(Prompt.class), any(ChatResponse.class)))
                .thenReturn(executionResult);

        OllamaAiAdapter adapter = adapter(List.of(new DateTimeTool()));

        AgentResponse response = adapter.execute(new AgentCommand("Frage", null), "System", 5);

        assertThat(response.toolInvocations()).hasSize(1);
        assertThat(response.toolInvocations().get(0).result()).isEqualTo("kein Ergebnis");
    }

    @Test
    void executeThrowsWhenIterationLimitIsReached() {
        AssistantMessage toolCallMessage = toolCallMessage();
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(toolCallMessage))));
        ToolExecutionResult executionResult = ToolExecutionResult.builder()
                .conversationHistory(List.of(
                        new SystemMessage("System"),
                        new UserMessage("Läuft ewig"),
                        toolCallMessage,
                        ToolResponseMessage.builder()
                                .responses(List.of(new ToolResponseMessage.ToolResponse(
                                        TOOL_CALL_ID, "getCurrentDateTime", "2026-08-06T10:00:00+02:00")))
                                .build()))
                .build();
        when(toolCallingManager.executeToolCalls(any(Prompt.class), any(ChatResponse.class)))
                .thenReturn(executionResult);

        OllamaAiAdapter adapter = adapter(List.of(new DateTimeTool()));

        assertThatThrownBy(() -> adapter.execute(new AgentCommand("Läuft ewig", null), "System", 3))
                .isInstanceOf(AgentLoopLimitExceededException.class)
                .hasMessageContaining("3");

        verify(chatModel, times(3)).call(any(Prompt.class));
    }

    @Test
    void executeStoresAndReusesConversationForSameContextId() {
        ChatMemory memory = newMemory();
        OllamaAiAdapter adapter = adapter(List.of(), memory);

        when(chatModel.call(any(Prompt.class))).thenReturn(finalResponse("Antwort 1"));
        adapter.execute(new AgentCommand("Frage 1", "ctx-1"), "System", 5);

        when(chatModel.call(any(Prompt.class))).thenReturn(finalResponse("Antwort 2"));
        adapter.execute(new AgentCommand("Frage 2", "ctx-1"), "System", 5);

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel, times(2)).call(captor.capture());

        List<Message> secondPromptMessages = captor.getAllValues().get(1).getInstructions();
        List<String> texts = secondPromptMessages.stream().map(Message::getText).toList();
        assertThat(texts).containsExactly("System", "Frage 1", "Antwort 1", "Frage 2");
        assertThat(memory.get("ctx-1")).extracting(Message::getText).containsExactly("Frage 1", "Antwort 1", "Frage 2", "Antwort 2");
    }

    @Test
    void executeWithoutContextIdKeepsConversationsSeparate() {
        ChatMemory memory = newMemory();
        OllamaAiAdapter adapter = adapter(List.of(), memory);

        when(chatModel.call(any(Prompt.class))).thenReturn(finalResponse("Antwort A"));
        adapter.execute(new AgentCommand("Frage A", "ctx-a"), "System", 5);

        when(chatModel.call(any(Prompt.class))).thenReturn(finalResponse("Antwort B"));
        adapter.execute(new AgentCommand("Frage B", null), "System", 5);

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel, times(2)).call(captor.capture());

        List<String> secondPromptTexts = captor.getAllValues().get(1).getInstructions()
                .stream().map(Message::getText).toList();
        assertThat(secondPromptTexts).doesNotContain("Frage A");
        assertThat(memory.get("ctx-a")).extracting(Message::getText).containsExactly("Frage A", "Antwort A");
    }

    private AssistantMessage toolCallMessage() {
        return AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        TOOL_CALL_ID, "function", "getCurrentDateTime", "{}")))
                .build();
    }

    private ChatResponse finalResponse(String content) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(content).build())));
    }
}
