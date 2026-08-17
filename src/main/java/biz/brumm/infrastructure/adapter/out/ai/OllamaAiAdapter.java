package biz.brumm.infrastructure.adapter.out.ai;

import biz.brumm.domain.model.AgentCommand;
import biz.brumm.domain.model.AgentResponse;
import biz.brumm.domain.model.ToolInvocation;
import biz.brumm.domain.port.out.AgentTool;
import biz.brumm.domain.port.out.AiProviderPort;
import biz.brumm.domain.port.out.ToolPolicy;
import biz.brumm.domain.service.AgentLoopLimitExceededException;
import biz.brumm.infrastructure.mcp.McpToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Component
public class OllamaAiAdapter implements AiProviderPort {

    private static final Logger log = LoggerFactory.getLogger(OllamaAiAdapter.class);

    private final ChatModel chatModel;
    private final ToolCallingManager toolCallingManager;
    private final ChatMemory chatMemory;
    private final List<ToolCallback> toolCallbacks;

    public OllamaAiAdapter(ChatModel chatModel, ToolCallingManager toolCallingManager, List<AgentTool> tools,
                           ObjectProvider<McpToolRegistry> mcpToolRegistry, ChatMemory chatMemory,
                           ToolPolicy toolPolicy) {
        this.chatModel = chatModel;
        this.toolCallingManager = toolCallingManager;
        this.chatMemory = chatMemory;
        List<ToolCallback> callbacks = new ArrayList<>(List.of(ToolCallbacks.from(tools.toArray())));
        McpToolRegistry registry = mcpToolRegistry.getIfAvailable();
        if (registry != null) {
            callbacks.addAll(registry.toolCallbacks());
        }
        this.toolCallbacks = filterByPolicy(callbacks, toolPolicy);
    }

    private List<ToolCallback> filterByPolicy(List<ToolCallback> callbacks, ToolPolicy toolPolicy) {
        List<ToolCallback> enabled = callbacks.stream()
                .filter(callback -> toolPolicy.isToolEnabled(callback.getToolDefinition().name()))
                .toList();
        if (enabled.size() != callbacks.size()) {
            List<String> disabled = callbacks.stream()
                    .map(callback -> callback.getToolDefinition().name())
                    .filter(name -> !toolPolicy.isToolEnabled(name))
                    .toList();
            log.info("Tool-Policy deaktiviert {} Werkzeug(e): {}", disabled.size(), String.join(", ", disabled));
        }
        return List.copyOf(enabled);
    }

    @Override
    public AgentResponse execute(AgentCommand command, String systemPrompt, int maxIterations) {
        boolean hasContext = command.contextId() != null && !command.contextId().isBlank();

        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));
        if (hasContext) {
            messages.addAll(chatMemory.get(command.contextId()));
        }
        messages.add(new UserMessage(command.prompt()));

        ChatOptions defaultOptions = chatModel.getOptions();
        ToolCallingChatOptions.Builder<?> builder = defaultOptions instanceof ToolCallingChatOptions toolCallingOptions
                ? toolCallingOptions.mutate()
                : ToolCallingChatOptions.builder().model(defaultOptions.getModel());
        ChatOptions options = builder.toolCallbacks(toolCallbacks).build();

        List<ToolInvocation> toolInvocations = new ArrayList<>();
        Prompt prompt = new Prompt(messages, options);
        int iterations = 0;

        for (; iterations < maxIterations; iterations++) {
            ChatResponse response = chatModel.call(prompt);
            AssistantMessage assistantMessage = response.getResult().getOutput();

            if (!assistantMessage.hasToolCalls()) {
                log.info("Agent abgeschlossen nach {} Iteration(en) und {} Tool-Aufruf(en).",
                        iterations + 1, toolInvocations.size());
                if (hasContext) {
                    storeConversation(prompt, assistantMessage, command.contextId());
                }
                return new AgentResponse(assistantMessage.getText(), Instant.now(), toolInvocations, iterations + 1, null);
            }

            ToolExecutionResult toolExecutionResult = toolCallingManager.executeToolCalls(prompt, response);
            recordToolInvocations(assistantMessage, toolExecutionResult, toolInvocations);
            prompt = new Prompt(toolExecutionResult.conversationHistory(), options);
        }

        log.warn("Agent hat nach {} Iteration(en) keine finale Antwort geliefert.", maxIterations);
        throw new AgentLoopLimitExceededException(maxIterations);
    }

    private void storeConversation(Prompt finalPrompt, AssistantMessage finalAnswer, String contextId) {
        List<Message> conversation = new ArrayList<>(finalPrompt.getInstructions().stream()
                .filter(message -> !(message instanceof SystemMessage))
                .toList());
        conversation.add(finalAnswer);

        chatMemory.clear(contextId);
        chatMemory.add(contextId, List.copyOf(conversation));
        log.info("Konversation für contextId '{}' mit {} Nachricht(en) gespeichert.", contextId, conversation.size());
    }

    private void recordToolInvocations(AssistantMessage assistantMessage, ToolExecutionResult toolExecutionResult,
                                       List<ToolInvocation> target) {
        for (AssistantMessage.ToolCall toolCall : assistantMessage.getToolCalls()) {
            String result = findToolResult(toolExecutionResult, toolCall.id());
            target.add(new ToolInvocation(toolCall.name(), toolCall.arguments(), result));
            log.info("Tool-Aufruf: {} (Argumente={}) -> {}", toolCall.name(), toolCall.arguments(), result);
        }
    }

    private String findToolResult(ToolExecutionResult toolExecutionResult, String toolCallId) {
        for (Message message : toolExecutionResult.conversationHistory()) {
            if (message instanceof ToolResponseMessage toolResponseMessage) {
                for (ToolResponseMessage.ToolResponse toolResponse : toolResponseMessage.getResponses()) {
                    if (toolResponse.id().equals(toolCallId)) {
                        return toolResponse.responseData();
                    }
                }
            }
        }
        return "kein Ergebnis";
    }
}
