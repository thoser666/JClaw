package biz.brumm.domain.model;

import java.time.Instant;

public record Session(String sessionId, String displayName, String group,
                      Instant sessionStartedAt, Instant lastInteractionAt, Instant updatedAt) {

    public Session(String sessionId, String displayName, Instant sessionStartedAt,
                   Instant lastInteractionAt, Instant updatedAt) {
        this(sessionId, displayName, null, sessionStartedAt, lastInteractionAt, updatedAt);
    }
}
