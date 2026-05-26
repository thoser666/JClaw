package biz.brumm.domain.service;

import biz.brumm.domain.model.AgentCommand;
import biz.brumm.domain.model.AgentResponse;
import biz.brumm.domain.port.in.ExecuteTaskUseCase;
import biz.brumm.domain.port.out.AiProviderPort;
import org.springframework.stereotype.Service;

@Service
public class ClawAgentService implements ExecuteTaskUseCase {

    private final AiProviderPort aiProviderPort;

    public ClawAgentService(AiProviderPort aiProviderPort) {
        this.aiProviderPort = aiProviderPort;
    }

    @Override
    public AgentResponse handle(AgentCommand command) {
        String systemPrompt = """
                Du bist JClaw, ein autonomer und hochgradig strukturierter Software-Agent.
                Verarbeite die Eingabe präzise und gib eine direkte Antwort ohne Floskeln.
                """;

        String rawResponse = aiProviderPort.generateAnswer(command, systemPrompt);

        return AgentResponse.of(rawResponse);
    }
}