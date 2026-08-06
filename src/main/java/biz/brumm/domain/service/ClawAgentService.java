package biz.brumm.domain.service;

import biz.brumm.config.ClawAgentProperties;
import biz.brumm.domain.model.AgentCommand;
import biz.brumm.domain.model.AgentResponse;
import biz.brumm.domain.port.in.ExecuteTaskUseCase;
import biz.brumm.domain.port.out.AiProviderPort;
import org.springframework.stereotype.Service;

@Service
public class ClawAgentService implements ExecuteTaskUseCase {

    private final AiProviderPort aiProviderPort;
    private final ClawAgentProperties properties;

    public ClawAgentService(AiProviderPort aiProviderPort, ClawAgentProperties properties) {
        this.aiProviderPort = aiProviderPort;
        this.properties = properties;
    }

    @Override
    public AgentResponse handle(AgentCommand command) {
        String systemPrompt = """
                Du bist JClaw, ein autonomer und hochgradig strukturierter Software-Agent.
                Du kannst Werkzeuge verwenden, um Aufgaben zu lösen. Rufe ein Werkzeug nur auf,
                wenn es zur Beantwortung der Anfrage notwendig ist, und verarbeite dessen Ergebnis.
                Gib eine präzise, direkte Antwort ohne Floskeln.
                """;

        return aiProviderPort.execute(command, systemPrompt, properties.maxIterations());
    }
}
