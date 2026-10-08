package biz.brumm.domain.service;

import biz.brumm.config.ClawAgentProperties;
import biz.brumm.config.SkillProperties;
import biz.brumm.domain.model.AgentCommand;
import biz.brumm.domain.model.AgentResponse;
import biz.brumm.domain.model.Session;
import biz.brumm.domain.model.Skill;
import biz.brumm.domain.port.in.ExecuteTaskUseCase;
import biz.brumm.domain.port.out.AiProviderPort;
import biz.brumm.domain.port.out.PluginHookDispatcher;
import biz.brumm.domain.port.out.SkillProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
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
    private final PluginHookDispatcher pluginHooks;

    public ClawAgentService(AiProviderPort aiProviderPort, ClawAgentProperties properties,
                            SkillProperties skillProperties, SkillProvider skillProvider,
                            SessionService sessionService, PluginHookDispatcher pluginHooks) {
        this.aiProviderPort = aiProviderPort;
        this.properties = properties;
        this.skillProperties = skillProperties;
        this.skillProvider = skillProvider;
        this.sessionService = sessionService;
        this.pluginHooks = pluginHooks;
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

        String resolvedContextId = effectiveContextId;

        // before_agent_run (OpenClaw): blockbarer Hook vor der eigentlichen Verarbeitung.
        // Blockiert, liefert der Agent eine Antwort ohne den Provider zu rufen.
        PluginHookDispatcher.HookOutcome runDecision = pluginHooks.dispatch("before_agent_run",
                Map.of("agent", "jclaw", "task", command.prompt(), "sessionId", sessionId == null ? "" : sessionId));
        if (runDecision.blocked()) {
            log.warn("Agent-Ausführung blockiert durch before_agent_run-Hook: {}", runDecision.message());
            return AgentResponse.blocked("Agent-Ausführung blockiert durch Plugin-Hook: " + runDecision.message());
        }

        AgentCommand resolvedCommand = new AgentCommand(command.prompt(), resolvedContextId);
        AgentResponse rawResponse = aiProviderPort.execute(resolvedCommand, buildSystemPrompt(),
                properties.maxIterations());

        // before_agent_finalize (OpenClaw): beobachtet vor dem Zusammenbau der finalen
        // Antwort (Katalog-Registrierung, JClaw emittiert die Stage ohne Block-Auswertung).
        pluginHooks.dispatch("before_agent_finalize",
                Map.of("agent", "jclaw", "sessionId", sessionId == null ? "" : sessionId));

        AgentResponse finalResponse;
        if (sessionId != null) {
            sessionService.touchSession(sessionId, command.prompt());
            finalResponse = new AgentResponse(rawResponse.content(), rawResponse.timestamp(),
                    rawResponse.toolInvocations(), rawResponse.iterations(), sessionId);
        } else {
            finalResponse = rawResponse;
        }

        // agent_end (OpenClaw): beobachtet nach Abschluss des Agent-Laufs.
        pluginHooks.dispatch("agent_end",
                Map.of("agent", "jclaw", "sessionId", sessionId == null ? "" : sessionId,
                        "iterations", finalResponse.iterations(), "timestamp", Instant.now().toString()));
        return finalResponse;
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
