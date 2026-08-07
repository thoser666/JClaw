package biz.brumm.domain.service;

import biz.brumm.config.ClawAgentProperties;
import biz.brumm.domain.model.AgentCommand;
import biz.brumm.domain.model.AgentResponse;
import biz.brumm.domain.port.out.AiProviderPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClawAgentServiceTest {

    @Mock
    private AiProviderPort aiProviderPort;

    @Test
    void handlePassesSystemPromptAndMaxIterationsToProvider() {
        ClawAgentProperties properties = new ClawAgentProperties(8, 10);
        ClawAgentService service = new ClawAgentService(aiProviderPort, properties);
        AgentResponse expected = AgentResponse.of("Antwort");
        when(aiProviderPort.execute(any(AgentCommand.class), contains("JClaw"), eq(8))).thenReturn(expected);

        AgentResponse result = service.handle(new AgentCommand("Aufgabe", "ctx-1"));

        assertThat(result).isSameAs(expected);
        verify(aiProviderPort).execute(any(AgentCommand.class), contains("JClaw"), eq(8));
    }

    @Test
    void handleUsesConfiguredMaxIterations() {
        ClawAgentProperties properties = new ClawAgentProperties(3, 10);
        ClawAgentService service = new ClawAgentService(aiProviderPort, properties);
        when(aiProviderPort.execute(any(AgentCommand.class), any(String.class), eq(3)))
                .thenReturn(AgentResponse.of("Antwort"));

        service.handle(new AgentCommand("Aufgabe", null));

        verify(aiProviderPort).execute(any(AgentCommand.class), any(String.class), eq(3));
    }
}
