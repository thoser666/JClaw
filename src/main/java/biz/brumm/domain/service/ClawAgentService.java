package biz.brumm.domain.service;

import biz.brumm.config.ClawAgentProperties;
import biz.brumm.config.SkillProperties;
import biz.brumm.domain.model.AgentCommand;
import biz.brumm.domain.model.AgentResponse;
import biz.brumm.domain.model.Skill;
import biz.brumm.domain.port.in.ExecuteTaskUseCase;
import biz.brumm.domain.port.out.AiProviderPort;
import biz.brumm.domain.port.out.SkillProvider;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ClawAgentService implements ExecuteTaskUseCase {

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

    public ClawAgentService(AiProviderPort aiProviderPort, ClawAgentProperties properties,
                            SkillProperties skillProperties, SkillProvider skillProvider) {
        this.aiProviderPort = aiProviderPort;
        this.properties = properties;
        this.skillProperties = skillProperties;
        this.skillProvider = skillProvider;
    }

    @Override
    public AgentResponse handle(AgentCommand command) {
        return aiProviderPort.execute(command, buildSystemPrompt(), properties.maxIterations());
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
