package biz.brumm.infrastructure.adapter.out.persistence;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link ChatMemoryRepository} auf Basis einer H2-Dateidatenbank. Nachrichten werden
 * als JSON pro Zeile gespeichert und beim Laden wieder in Spring-AI-{@link Message}
 * Objekte überführt.
 */
@Component
public class JdbcChatMemoryRepository implements ChatMemoryRepository {

    private static final String SELECT_MESSAGES =
            "SELECT message_json FROM chat_message WHERE conversation_id = ? ORDER BY message_order";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcChatMemoryRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<String> findConversationIds() {
        return jdbcTemplate.queryForList("SELECT DISTINCT conversation_id FROM chat_message ORDER BY conversation_id",
                String.class);
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        List<String> rows = jdbcTemplate.queryForList(SELECT_MESSAGES, String.class, conversationId);
        return rows.stream().map(this::deserialize).toList();
    }

    @Override
    @Transactional
    public void saveAll(String conversationId, List<Message> messages) {
        jdbcTemplate.update("DELETE FROM chat_message WHERE conversation_id = ?", conversationId);
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            jdbcTemplate.update(
                    "INSERT INTO chat_message (conversation_id, message_order, message_type, message_json) VALUES (?, ?, ?, ?)",
                    conversationId, i, message.getMessageType().getValue(), serialize(message));
        }
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        jdbcTemplate.update("DELETE FROM chat_message WHERE conversation_id = ?", conversationId);
    }

    private String serialize(Message message) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", message.getMessageType().name());
        if (message.getText() != null) {
            node.put("text", message.getText());
        }
        if (message instanceof AssistantMessage assistantMessage) {
            ArrayNode toolCalls = node.putArray("toolCalls");
            for (AssistantMessage.ToolCall toolCall : assistantMessage.getToolCalls()) {
                toolCalls.addObject()
                        .put("id", toolCall.id())
                        .put("type", toolCall.type())
                        .put("name", toolCall.name())
                        .put("arguments", toolCall.arguments());
            }
        } else if (message instanceof ToolResponseMessage toolResponseMessage) {
            ArrayNode responses = node.putArray("responses");
            for (ToolResponseMessage.ToolResponse response : toolResponseMessage.getResponses()) {
                responses.addObject()
                        .put("id", response.id())
                        .put("name", response.name())
                        .put("responseData", response.responseData());
            }
        }
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JacksonException e) {
            throw new IllegalStateException("Konversationsnachricht kann nicht serialisiert werden.", e);
        }
    }

    private Message deserialize(String json) {
        JsonNode node;
        try {
            node = objectMapper.readTree(json);
        } catch (JacksonException e) {
            throw new IllegalStateException("Gespeicherte Konversationsnachricht ist beschädigt.", e);
        }

        MessageType type = MessageType.valueOf(node.get("type").asString());
        String text = node.hasNonNull("text") ? node.get("text").asString() : null;

        return switch (type) {
            case SYSTEM -> new SystemMessage(text);
            case USER -> new UserMessage(text);
            case ASSISTANT -> {
                List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
                JsonNode toolCallsNode = node.get("toolCalls");
                if (toolCallsNode != null) {
                    for (JsonNode toolCall : toolCallsNode) {
                        toolCalls.add(new AssistantMessage.ToolCall(
                                toolCall.get("id").asString(),
                                toolCall.get("type").asString(),
                                toolCall.get("name").asString(),
                                toolCall.get("arguments").asString()));
                    }
                }
                yield AssistantMessage.builder().content(text).toolCalls(toolCalls).build();
            }
            case TOOL -> {
                List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
                JsonNode responsesNode = node.get("responses");
                if (responsesNode != null) {
                    for (JsonNode response : responsesNode) {
                        responses.add(new ToolResponseMessage.ToolResponse(
                                response.get("id").asString(),
                                response.get("name").asString(),
                                response.get("responseData").asString()));
                    }
                }
                yield ToolResponseMessage.builder().responses(responses).build();
            }
        };
    }
}
