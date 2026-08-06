package biz.brumm.domain.port.out;

import biz.brumm.domain.model.AgentCommand;
import biz.brumm.domain.model.AgentResponse;

public interface AiProviderPort {

    /**
     * Führt einen Agentenlauf aus: Das Sprachmodell kann in mehreren Iterationen
     * Werkzeuge aufrufen, deren Ergebnisse zurückgespielt werden, bis eine finale
     * Antwort vorliegt oder {@code maxIterations} erreicht ist.
     */
    AgentResponse execute(AgentCommand command, String systemPrompt, int maxIterations);
}
