package biz.brumm.domain.port.in;

import biz.brumm.domain.model.AgentCommand;
import biz.brumm.domain.model.AgentResponse;

public interface ExecuteTaskUseCase {
    AgentResponse handle(AgentCommand command);
}