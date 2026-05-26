package biz.brumm.domain.model;

import java.time.Instant;

public record AgentResponse(String content, Instant timestamp) {
    public static AgentResponse of(String content) {
        return new AgentResponse(content, Instant.now());
    }
}