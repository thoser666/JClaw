package biz.brumm;

import biz.brumm.config.ClawAgentProperties;
import biz.brumm.config.SkillProperties;
import biz.brumm.domain.port.in.ExecuteTaskUseCase;
import biz.brumm.domain.port.out.AiProviderPort;
import biz.brumm.infrastructure.adapter.in.web.TaskRestController;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1",
        "spring.sql.init.mode=always"
})
class JClawApplicationTests {

    @Autowired
    private ExecuteTaskUseCase executeTaskUseCase;

    @Autowired
    private AiProviderPort aiProviderPort;

    @Autowired
    private TaskRestController taskRestController;

    @Autowired
    private ChatMemory chatMemory;

    @Autowired
    private ClawAgentProperties clawAgentProperties;

    @Autowired
    private SkillProperties skillProperties;

    @Test
    void contextLoads() {
        assertThat(executeTaskUseCase).isNotNull();
        assertThat(aiProviderPort).isNotNull();
        assertThat(taskRestController).isNotNull();
        assertThat(chatMemory).isNotNull();
        assertThat(clawAgentProperties).isNotNull();
    }

    @Test
    void agentPropertiesAreBoundFromConfiguration() {
        assertThat(clawAgentProperties.maxIterations()).isEqualTo(8);
        assertThat(clawAgentProperties.maxHistoryMessages()).isEqualTo(10);
    }

    @Test
    void skillPropertiesUseDefaults() {
        assertThat(skillProperties.dir()).isEqualTo("./skills");
        assertThat(skillProperties.enabled()).isEmpty();
    }

    @Test
    void chatMemoryWindowMatchesConfiguredHistoryLimit() {
        for (int i = 0; i < 12; i++) {
            chatMemory.add("cfg-test", List.of(new UserMessage("Nachricht " + i)));
        }

        assertThat(chatMemory.get("cfg-test")).hasSize(10);
        assertThat(chatMemory.get("cfg-test").get(0).getText()).isEqualTo("Nachricht 2");
    }

}
