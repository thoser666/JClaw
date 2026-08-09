package biz.brumm.domain.service;

import biz.brumm.config.ClawAgentProperties;
import biz.brumm.config.SkillProperties;
import biz.brumm.domain.model.AgentCommand;
import biz.brumm.domain.model.AgentResponse;
import biz.brumm.domain.model.Skill;
import biz.brumm.domain.port.out.AiProviderPort;
import biz.brumm.domain.port.out.SkillProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClawAgentServiceTest {

    @Mock
    private AiProviderPort aiProviderPort;

    @Mock
    private SkillProvider skillProvider;

    @Test
    void handlePassesSystemPromptAndMaxIterationsToProvider() {
        ClawAgentProperties properties = new ClawAgentProperties(8, 10);
        ClawAgentService service = new ClawAgentService(aiProviderPort, properties, new SkillProperties("./skills", List.of()), skillProvider);
        AgentResponse expected = AgentResponse.of("Antwort");
        when(skillProvider.findAll()).thenReturn(List.of());
        when(aiProviderPort.execute(any(AgentCommand.class), contains("JClaw"), eq(8))).thenReturn(expected);

        AgentResponse result = service.handle(new AgentCommand("Aufgabe", "ctx-1"));

        assertThat(result).isSameAs(expected);
        verify(aiProviderPort).execute(any(AgentCommand.class), contains("JClaw"), eq(8));
    }

    @Test
    void handleUsesConfiguredMaxIterations() {
        ClawAgentProperties properties = new ClawAgentProperties(3, 10);
        ClawAgentService service = new ClawAgentService(aiProviderPort, properties, new SkillProperties("./skills", List.of()), skillProvider);
        when(skillProvider.findAll()).thenReturn(List.of());
        when(aiProviderPort.execute(any(AgentCommand.class), any(String.class), eq(3)))
                .thenReturn(AgentResponse.of("Antwort"));

        service.handle(new AgentCommand("Aufgabe", null));

        verify(aiProviderPort).execute(any(AgentCommand.class), any(String.class), eq(3));
    }

    @Test
    void handleIncludesEnabledSkillsInSystemPrompt() {
        ClawAgentProperties properties = new ClawAgentProperties(8, 10);
        SkillProperties skillProperties = new SkillProperties("./skills", List.of("code-review"));
        ClawAgentService service = new ClawAgentService(aiProviderPort, properties, skillProperties, skillProvider);
        Skill codeReview = new Skill("code-review", "Prueft Pull Requests.", "Pruefe Aenderungen auf Bugs.", "/skills/code-review");
        Skill disabled = new Skill("docs", "Schreibt Doku.", "Erstelle Doku.", "/skills/docs");
        when(skillProvider.findAll()).thenReturn(List.of(disabled, codeReview));
        when(aiProviderPort.execute(any(AgentCommand.class), any(String.class), eq(8)))
                .thenReturn(AgentResponse.of("Antwort"));

        service.handle(new AgentCommand("Aufgabe", null));

        verify(aiProviderPort).execute(any(AgentCommand.class), argThat(prompt ->
                        prompt.contains("### Skill: code-review")
                                && prompt.contains("Pruefe Aenderungen auf Bugs.")
                                && !prompt.contains("Erstelle Doku.")),
                eq(8));
    }
}
