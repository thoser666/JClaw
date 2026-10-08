package biz.brumm.domain.model;

import java.time.Instant;
import java.util.List;

public record AgentResponse(String content, Instant timestamp, List<ToolInvocation> toolInvocations, int iterations, String sessionId) {

    public AgentResponse {
        toolInvocations = List.copyOf(toolInvocations);
    }

    public static AgentResponse of(String content) {
        return new AgentResponse(content, Instant.now(), List.of(), 1, null);
    }

    /** Blockierte Ausführung (z. B. durch before_agent_run-Hook): keine Iterationen, keine Tools. */
    public static AgentResponse blocked(String content) {
        return new AgentResponse(content, Instant.now(), List.of(), 0, null);
    }
}
