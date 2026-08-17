package biz.brumm.domain.service;

import biz.brumm.config.ClawAgentProperties;
import biz.brumm.config.SessionProperties;
import biz.brumm.config.SkillProperties;
import biz.brumm.domain.model.AgentCommand;
import biz.brumm.domain.model.AgentResponse;
import biz.brumm.domain.model.Session;
import biz.brumm.domain.model.Skill;
import biz.brumm.domain.port.out.AiProviderPort;
import biz.brumm.domain.port.out.ConversationStore;
import biz.brumm.domain.port.out.SessionStore;
import biz.brumm.domain.port.out.SkillProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClawAgentServiceTest {

    @Mock
    private AiProviderPort aiProviderPort;

    @Mock
    private SkillProvider skillProvider;

    @Mock
    private SessionStore sessionStore;

    @Mock
    private ConversationStore conversationStore;

    private SessionService sessionService() {
        return new SessionService(sessionStore, conversationStore,
                new SessionProperties("none", 4, 60));
    }

    @Test
    void handlePassesSystemPromptAndMaxIterationsToProvider() {
        ClawAgentProperties properties = new ClawAgentProperties(8, 10);
        ClawAgentService service = new ClawAgentService(aiProviderPort, properties,
                new SkillProperties("./skills", List.of()), skillProvider, sessionService());
        AgentResponse expected = AgentResponse.of("Antwort");
        when(skillProvider.findAll()).thenReturn(List.of());
        when(sessionStore.findById("ctx-1")).thenReturn(Optional.empty());
        when(sessionStore.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(aiProviderPort.execute(any(AgentCommand.class), contains("JClaw"), eq(8))).thenReturn(expected);

        AgentResponse result = service.handle(new AgentCommand("Aufgabe", "ctx-1"));

        assertThat(result.content()).isEqualTo("Antwort");
        assertThat(result.sessionId()).isEqualTo("ctx-1");
        verify(aiProviderPort).execute(any(AgentCommand.class), contains("JClaw"), eq(8));
    }

    @Test
    void handleUsesConfiguredMaxIterations() {
        ClawAgentProperties properties = new ClawAgentProperties(3, 10);
        ClawAgentService service = new ClawAgentService(aiProviderPort, properties,
                new SkillProperties("./skills", List.of()), skillProvider, sessionService());
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
        ClawAgentService service = new ClawAgentService(aiProviderPort, properties, skillProperties,
                skillProvider, sessionService());
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

    @Test
    void handleOmitsBlankSkillDescriptionAndContent() {
        ClawAgentProperties properties = new ClawAgentProperties(8, 10);
        SkillProperties skillProperties = new SkillProperties("./skills", List.of("lean"));
        ClawAgentService service = new ClawAgentService(aiProviderPort, properties, skillProperties,
                skillProvider, sessionService());
        Skill lean = new Skill("lean", "   ", "   ", "/skills/lean");
        when(skillProvider.findAll()).thenReturn(List.of(lean));
        when(aiProviderPort.execute(any(AgentCommand.class), any(String.class), eq(8)))
                .thenReturn(AgentResponse.of("Antwort"));

        service.handle(new AgentCommand("Aufgabe", null));

        verify(aiProviderPort).execute(any(AgentCommand.class), argThat(prompt ->
                        prompt.contains("### Skill: lean")
                                && !prompt.contains("Beschreibung:")),
                eq(8));
    }

    @Test
    void handleWithoutContextIdDoesNotTouchSession() {
        ClawAgentProperties properties = new ClawAgentProperties(8, 10);
        ClawAgentService service = new ClawAgentService(aiProviderPort, properties,
                new SkillProperties("./skills", List.of()), skillProvider, sessionService());
        when(skillProvider.findAll()).thenReturn(List.of());
        when(aiProviderPort.execute(any(AgentCommand.class), any(String.class), eq(8)))
                .thenReturn(AgentResponse.of("Antwort"));

        AgentResponse result = service.handle(new AgentCommand("Aufgabe", null));

        assertThat(result.sessionId()).isNull();
        verify(sessionStore, never()).findById(any());
    }

    @Test
    void handleWithExistingSessionDoesNotCreateNewOne() {
        ClawAgentProperties properties = new ClawAgentProperties(8, 10);
        ClawAgentService service = new ClawAgentService(aiProviderPort, properties,
                new SkillProperties("./skills", List.of()), skillProvider, sessionService());
        Session existing = new Session("s1", "Titel", Instant.now(), Instant.now(), Instant.now());
        when(sessionStore.findById("s1")).thenReturn(Optional.of(existing));
        when(sessionStore.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(skillProvider.findAll()).thenReturn(List.of());
        when(aiProviderPort.execute(any(AgentCommand.class), any(String.class), eq(8)))
                .thenReturn(AgentResponse.of("ok"));

        AgentResponse result = service.handle(new AgentCommand("Aufgabe", "s1"));

        assertThat(result.sessionId()).isEqualTo("s1");
    }

    @Test
    void handleCreatesSessionWhenNotFound() {
        ClawAgentProperties properties = new ClawAgentProperties(8, 10);
        ClawAgentService service = new ClawAgentService(aiProviderPort, properties,
                new SkillProperties("./skills", List.of()), skillProvider, sessionService());
        when(sessionStore.findById("new-s")).thenReturn(Optional.empty());
        when(sessionStore.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(skillProvider.findAll()).thenReturn(List.of());
        when(aiProviderPort.execute(any(AgentCommand.class), any(String.class), eq(8)))
                .thenReturn(AgentResponse.of("ok"));

        AgentResponse result = service.handle(new AgentCommand("Aufgabe", "new-s"));

        assertThat(result.sessionId()).isEqualTo("new-s");
    }
}
