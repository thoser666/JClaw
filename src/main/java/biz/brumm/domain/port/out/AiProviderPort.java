package biz.brumm.domain.port.out;

import biz.brumm.domain.model.AgentCommand;

public interface AiProviderPort {
    String generateAnswer(AgentCommand command, String systemPrompt);
}