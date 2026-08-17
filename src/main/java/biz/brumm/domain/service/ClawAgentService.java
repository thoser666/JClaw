package biz.brumm.domain.service;

import biz.brumm.config.ClawAgentProperties;
import biz.brumm.config.SkillProperties;
import biz.brumm.domain.model.AgentCommand;
import biz.brumm.domain.model.AgentResponse;
import biz.brumm.domain.model.Session;
import biz.brumm.domain.model.Skill;
import biz.brumm.domain.port.in.ExecuteTaskUseCase;
import biz.brumm.domain.port.out.AiProviderPort;
import biz.brumm.domain.port.out.SkillProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class ClawAgentService implements ExecuteTaskUseCase {

    private static final Logger log = LoggerFactory.getLogger(ClawAgentService.class);

    private static final String BASE_SYSTEM_PROMPT = """
            Du bist JClaw, ein autonomer und hochgradig strukturierter Software-Agent.
            Du kannst Werkzeuge verwenden, um Aufgaben zu lösen. Rufe ein Werkzeug nur auf,
            wenn es zur Beantwortung der Anfrage notwendig ist, und verarbeite dessen Ergebnis.
            Gib eine präzise, direkte Antwort ohne Floskeln.
            """;

    private final AiProviderPort aiProviderPort;
    private final ClawAgentProperties properties;
    private final SkillProperties skillProperties;
    private final SkillProvider skillProvider;
    private final SessionService sessionService;

    public ClawAgentService(AiProviderPort aiProviderPort, ClawAgentProperties properties,
                            SkillProperties skillProperties, SkillProvider skillProvider,
                            SessionService sessionService) {
        this.aiProviderPort = aiProviderPort;
        this.properties = properties;
        this.skillProperties = skillProperties;
        this.skillProvider = skillProvider;
        this.sessionService = sessionService;
    }

    @Override
    public AgentResponse handle(AgentCommand command) {
        String effectiveContextId = command.contextId();
        String sessionId = null;

        if (effectiveContextId != null && !effectiveContextId.isBlank()) {
            Session session = sessionService.findSession(effectiveContextId).orElse(null);
            if (session != null && sessionService.shouldReset(session)) {
                effectiveContextId = UUID.randomUUID().toString();
                log.info("Session-Reset: neue Session-ID '{}'.", effectiveContextId);
                session = null;
            }
            if (session == null) {
                session = sessionService.createSession(effectiveContextId);
            }
            sessionId = session.sessionId();
        }

        AgentCommand resolvedCommand = new AgentCommand(command.prompt(), effectiveContextId);
        AgentResponse rawResponse = aiProviderPort.execute(resolvedCommand, buildSystemPrompt(),
                properties.maxIterations());

        if (sessionId != null) {
            sessionService.touchSession(sessionId, command.prompt());
            return new AgentResponse(rawResponse.content(), rawResponse.timestamp(),
                    rawResponse.toolInvocations(), rawResponse.iterations(), sessionId);
        }
        return rawResponse;
    }

    private String buildSystemPrompt() {
        List<Skill> enabledSkills = skillProvider.findAll().stream()
                .filter(skill -> skillProperties.enabled().contains(skill.name()))
                .toList();

        if (enabledSkills.isEmpty()) {
            return BASE_SYSTEM_PROMPT;
        }

        StringBuilder prompt = new StringBuilder(BASE_SYSTEM_PROMPT);
        prompt.append("""
                
                
                Die folgenden Skills stehen zur Verfügung. Nutze sie, wenn sie zur Lösung der Aufgabe beitragen:
                """);
        for (Skill skill : enabledSkills) {
            prompt.append("\n### Skill: ").append(skill.name()).append('\n');
            if (!skill.description().isBlank()) {
                prompt.append("Beschreibung: ").append(skill.description()).append('\n');
            }
            if (!skill.content().isBlank()) {
                prompt.append(skill.content()).append('\n');
            }
        }
        return prompt.toString();
    }
}
