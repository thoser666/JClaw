package biz.brumm.domain.service;

import biz.brumm.domain.model.AgentCommand;
import biz.brumm.domain.port.in.ExecuteTaskUseCase;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AgentFollowUpExecutorTest {

    private final ExecuteTaskUseCase executeTaskUseCase = mock(ExecuteTaskUseCase.class);
    private final AgentFollowUpExecutor executor = new AgentFollowUpExecutor(executeTaskUseCase);

    @Test
    void executeDispatchesAgentCommand() {
        executor.execute("s1", "Fasse zusammen");

        verify(executeTaskUseCase).handle(new AgentCommand("Fasse zusammen", "s1"));
    }
}