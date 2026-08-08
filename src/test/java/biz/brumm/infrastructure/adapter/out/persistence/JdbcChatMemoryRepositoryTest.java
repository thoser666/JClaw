package biz.brumm.infrastructure.adapter.out.persistence;

import tools.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcChatMemoryRepositoryTest {

    private final String dbName = UUID.randomUUID().toString();
    private JdbcChatMemoryRepository repository;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + dbName + ";DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS chat_message (
                    conversation_id VARCHAR(255) NOT NULL,
                    message_order INT NOT NULL,
                    message_type VARCHAR(32) NOT NULL,
                    message_json CLOB NOT NULL,
                    PRIMARY KEY (conversation_id, message_order)
                );
                """);
        repository = new JdbcChatMemoryRepository(jdbcTemplate, new ObjectMapper());
    }

    @Test
    void roundTripsAllMessageTypes() {
        AssistantMessage assistant = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call_1", "function", "calculate", "{\"expression\":\"2+2\"}")))
                .build();
        ToolResponseMessage toolResponse = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("call_1", "calculate", "4")))
                .build();
        List<Message> messages = List.of(
                new SystemMessage("System"),
                new UserMessage("Frage"),
                assistant,
                toolResponse);

        repository.saveAll("ctx-1", messages);
        List<Message> loaded = repository.findByConversationId("ctx-1");

        assertThat(loaded).extracting(Message::getMessageType)
                .containsExactly(MessageType.SYSTEM, MessageType.USER, MessageType.ASSISTANT, MessageType.TOOL);
        assertThat(loaded).extracting(Message::getText)
                .containsExactly("System", "Frage", "", "");

        AssistantMessage loadedAssistant = (AssistantMessage) loaded.get(2);
        assertThat(loadedAssistant.getToolCalls()).hasSize(1);
        assertThat(loadedAssistant.getToolCalls().get(0).id()).isEqualTo("call_1");
        assertThat(loadedAssistant.getToolCalls().get(0).name()).isEqualTo("calculate");
        assertThat(loadedAssistant.getToolCalls().get(0).arguments()).isEqualTo("{\"expression\":\"2+2\"}");

        ToolResponseMessage loadedToolResponse = (ToolResponseMessage) loaded.get(3);
        assertThat(loadedToolResponse.getResponses()).hasSize(1);
        assertThat(loadedToolResponse.getResponses().get(0).id()).isEqualTo("call_1");
        assertThat(loadedToolResponse.getResponses().get(0).name()).isEqualTo("calculate");
        assertThat(loadedToolResponse.getResponses().get(0).responseData()).isEqualTo("4");
    }

    @Test
    void saveAllReplacesExistingMessages() {
        repository.saveAll("ctx-1", List.of(new UserMessage("a")));

        repository.saveAll("ctx-1", List.of(new UserMessage("b"), new UserMessage("c")));

        assertThat(repository.findByConversationId("ctx-1"))
                .extracting(Message::getText)
                .containsExactly("b", "c");
    }

    @Test
    void deleteByConversationIdRemovesConversation() {
        repository.saveAll("ctx-1", List.of(new UserMessage("a")));

        repository.deleteByConversationId("ctx-1");

        assertThat(repository.findByConversationId("ctx-1")).isEmpty();
        assertThat(repository.findConversationIds()).doesNotContain("ctx-1");
    }

    @Test
    void findConversationIdsReturnsDistinctIds() {
        repository.saveAll("ctx-1", List.of(new UserMessage("a")));
        repository.saveAll("ctx-1", List.of(new UserMessage("b")));
        repository.saveAll("ctx-2", List.of(new UserMessage("c")));

        assertThat(repository.findConversationIds()).containsExactly("ctx-1", "ctx-2");
    }

    @Test
    void persistedMessagesSurviveAcrossMemoryInstances() {
        MessageWindowChatMemory first = MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(3)
                .build();
        first.add("ctx-1", List.of(
                new UserMessage("a"),
                new UserMessage("b"),
                new UserMessage("c"),
                new UserMessage("d")));

        assertThat(first.get("ctx-1")).extracting(Message::getText).containsExactly("b", "c", "d");

        MessageWindowChatMemory restarted = MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(3)
                .build();
        assertThat(restarted.get("ctx-1")).extracting(Message::getText).containsExactly("b", "c", "d");
        assertThat(repository.findConversationIds()).containsExactly("ctx-1");
    }
}
