package biz.brumm.config;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChatMemoryConfigTest {

    @Test
    void createsWindowedMemoryWithConfiguredWindow() {
        ChatMemoryConfig config = new ChatMemoryConfig();
        ChatMemory memory = config.chatMemory(new ClawAgentProperties(8, 3));

        memory.add("ctx", List.of(
                new UserMessage("a"),
                new UserMessage("b"),
                new UserMessage("c"),
                new UserMessage("d")));

        assertThat(memory.get("ctx")).extracting(Message::getText).containsExactly("b", "c", "d");
    }
}
