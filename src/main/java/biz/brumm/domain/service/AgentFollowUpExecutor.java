package biz.brumm.domain.service;

import biz.brumm.domain.model.AgentCommand;
import biz.brumm.domain.port.in.ExecuteTaskUseCase;
import org.springframework.stereotype.Component;

/**
 * Reale Bindeglied der Follow-up-Queue an den Agenten.
 */
@Component
public class AgentFollowUpExecutor implements FollowUpExecutor {

    private final ExecuteTaskUseCase executeTaskUseCase;

    public AgentFollowUpExecutor(ExecuteTaskUseCase executeTaskUseCase) {
        this.executeTaskUseCase = executeTaskUseCase;
    }

    @Override
    public void execute(String sessionId, String prompt) {
        executeTaskUseCase.handle(new AgentCommand(prompt, sessionId));
    }
}