package biz.brumm.infrastructure.adapter.out.ai;

import biz.brumm.config.OpenAiProperties;
import biz.brumm.domain.model.AgentCommand;
import biz.brumm.domain.model.AgentResponse;
import biz.brumm.domain.model.CompactionResult;
import biz.brumm.domain.model.ToolInvocation;
import biz.brumm.domain.port.out.AgentTool;
import biz.brumm.domain.port.out.AiProviderPort;
import biz.brumm.domain.port.out.HookCallback;
import biz.brumm.domain.port.out.ToolPolicy;
import biz.brumm.domain.service.CompactionService;
import biz.brumm.domain.service.MemoryVaultService;
import biz.brumm.infrastructure.mcp.McpToolRegistry;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * OpenAI-kompatibler Provider-Adapter (P4-04): spricht eine OpenAI-kompatible
 * Chat-Completions-API (OpenAI, Azure, Ollama-/v1, vLLM, ...) über java.net.http
 * an und implementiert den Agent-Loop inkl. Tool-Calls.
 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "jclaw.ai", name = "provider", havingValue = "openai")
public class OpenAiCompatibleAiAdapter implements AiProviderPort {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleAiAdapter.class);

    private final OpenAiProperties properties;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final List<ToolCallback> toolCallbacks;
    private final ChatMemory chatMemory;
    private final HookCallback hookCallback;
    private final CompactionService compactionService;
    private final MemoryVaultService memoryVaultService;
    private final ObjectProvider<McpToolRegistry> mcpToolRegistryProvider;
    private final ObjectProvider<org.springframework.ai.chat.model.ChatModel> chatModelProvider;

    public OpenAiCompatibleAiAdapter(OpenAiProperties properties, List<AgentTool> tools,
                                     org.springframework.beans.factory.ObjectProvider<McpToolRegistry> mcpToolRegistry,
                                     ChatMemory chatMemory, ToolPolicy toolPolicy,
                                     org.springframework.beans.factory.ObjectProvider<HookCallback> hookCallbackProvider,
                                     org.springframework.beans.factory.ObjectProvider<CompactionService> compactionServiceProvider,
                                     org.springframework.beans.factory.ObjectProvider<MemoryVaultService> memoryVaultServiceProvider,
                                     org.springframework.beans.factory.ObjectProvider<org.springframework.ai.chat.model.ChatModel> chatModelProvider) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
        this.mapper = new ObjectMapper();
        this.chatMemory = chatMemory;
        this.hookCallback = hookCallbackProvider.getIfAvailable();
        this.compactionService = compactionServiceProvider.getIfAvailable();
        this.memoryVaultService = memoryVaultServiceProvider.getIfAvailable();
        this.mcpToolRegistryProvider = mcpToolRegistry;
        this.chatModelProvider = chatModelProvider;
        List<ToolCallback> callbacks = new ArrayList<>(List.of(ToolCallbacks.from(tools.toArray())));
        McpToolRegistry mcpRegistry = mcpToolRegistry.getIfAvailable();
        if (mcpRegistry != null) callbacks.addAll(mcpRegistry.toolCallbacks());
        this.toolCallbacks = filterByPolicy(callbacks, toolPolicy);
    }

    private List<ToolCallback> filterByPolicy(List<ToolCallback> callbacks, ToolPolicy toolPolicy) {
        return callbacks.stream()
                .filter(c -> toolPolicy.isToolEnabled(c.getToolDefinition().name()))
                .toList();
    }

    @Override
    public AgentResponse execute(AgentCommand command, String systemPrompt, int maxIterations) {
        boolean hasContext = command.contextId() != null && !command.contextId().isBlank();
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        if (hasContext) {
            List<Message> history = chatMemory.get(command.contextId());
            if (compactionService != null && compactionService.isCompactionNeeded(history)) {
                if (memoryVaultService != null) {
                    memoryVaultService.syncConversation(command.contextId());
                }
                CompactionResult result = compactionService.compact(history);
                history = result.compactedMessages().stream()
                        .map(t -> (Message) new UserMessage(t)).toList();
            }
            for (Message m : history) {
                messages.add(Map.of("role", roleOf(m), "content", m.getText()));
            }
        }
        messages.add(Map.of("role", "user", "content", command.prompt()));

        List<ToolInvocation> invocations = new ArrayList<>();
        int iterations = 0;
        String finalContent = "";
        for (int i = 0; i < maxIterations; i++) {
            iterations++;
            try {
                ObjectNode req = mapper.createObjectNode();
                req.put("model", properties.model());
                ArrayNode msgs = req.putArray("messages");
                for (Map<String, Object> m : messages) {
                    ObjectNode mn = msgs.addObject();
                    mn.put("role", (String) m.get("role"));
                    Object c = m.get("content");
                    if (c != null) mn.put("content", c.toString());
                    if (m.containsKey("tool_calls")) mn.set("tool_calls", (JsonNode) m.get("tool_calls"));
                    if (m.containsKey("tool_call_id")) mn.put("tool_call_id", (String) m.get("tool_call_id"));
                }
                if (!toolCallbacks.isEmpty()) {
                    ArrayNode tools = req.putArray("tools");
                    for (ToolCallback cb : toolCallbacks) {
                        ToolDefinition td = cb.getToolDefinition();
                        ObjectNode t = tools.addObject();
                        t.put("type", "function");
                        ObjectNode fn = t.putObject("function");
                        fn.put("name", td.name());
                        fn.put("description", td.description());
                        try {
                            fn.set("parameters", mapper.readTree(td.inputSchema()));
                        } catch (Exception e) {
                            fn.putObject("parameters");
                        }
                    }
                    req.put("tool_choice", "auto");
                }

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(properties.baseUrl() + "/v1/chat/completions"))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + properties.apiKey())
                        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(req)))
                        .timeout(Duration.ofSeconds(120))
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 400) {
                    throw new RuntimeException("OpenAI HTTP " + response.statusCode() + ": " + response.body());
                }
                JsonNode root = mapper.readTree(response.body());
                JsonNode choice = root.path("choices").get(0);
                JsonNode msg = choice.path("message");
                String content = msg.path("content").asText(null);
                JsonNode toolCalls = msg.path("tool_calls");
                if (toolCalls.isArray() && toolCalls.size() > 0) {
                    // assistant message with tool_calls
                    ObjectNode am = mapper.createObjectNode();
                    am.put("role", "assistant");
                    if (content != null) am.put("content", content); else am.putNull("content");
                    ArrayNode tcs = am.putArray("tool_calls");
                    List<String> toolNames = new ArrayList<>();
                    List<String> toolArgs = new ArrayList<>();
                    List<String> toolIds = new ArrayList<>();
                    for (JsonNode tc : toolCalls) {
                        tcs.add(tc);
                        toolIds.add(tc.path("id").asText());
                        toolNames.add(tc.path("function").path("name").asText());
                        toolArgs.add(tc.path("function").path("arguments").asText("{}"));
                    }
                    messages.add(mapper.convertValue(am, Map.class));
                    // execute tools
                    for (int j = 0; j < toolNames.size(); j++) {
                        String tname = toolNames.get(j);
                        String targs = toolArgs.get(j);
                        String tid = toolIds.get(j);
                        String result = executeTool(tname, targs);
                        invocations.add(new ToolInvocation(tname, targs, result));
                        Map<String, Object> tm = new java.util.LinkedHashMap<>();
                        tm.put("role", "tool");
                        tm.put("tool_call_id", tid);
                        tm.put("content", result == null ? "" : result);
                        messages.add(tm);
                    }
                    continue;
                }
                finalContent = content == null ? "" : content;
                break;
            } catch (Exception e) {
                log.error("OpenAI-Adapter Fehler: {}", e.getMessage());
                finalContent = "Fehler: " + e.getMessage();
                break;
            }
        }
        if (hasContext) {
            chatMemory.add(command.contextId(), new UserMessage(command.prompt()));
            if (finalContent != null && !finalContent.isBlank()) {
                chatMemory.add(command.contextId(), new org.springframework.ai.chat.messages.AssistantMessage(finalContent));
            }
        }
        return new AgentResponse(finalContent, java.time.Instant.now(), invocations, iterations, command.contextId());
    }

    private String executeTool(String name, String args) {
        for (ToolCallback cb : toolCallbacks) {
            if (cb.getToolDefinition().name().equals(name)) {
                try {
                    return cb.call(args);
                } catch (Exception e) {
                    return "Fehler: " + e.getMessage();
                }
            }
        }
        return "Unbekanntes Tool: " + name;
    }

    private String roleOf(Message m) {
        if (m instanceof SystemMessage) return "system";
        if (m instanceof org.springframework.ai.chat.messages.AssistantMessage) return "assistant";
        if (m instanceof org.springframework.ai.chat.messages.ToolResponseMessage) return "tool";
        return "user";
    }
}