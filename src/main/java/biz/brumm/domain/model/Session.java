package biz.brumm.domain.model;

import java.time.Instant;

public record Session(String sessionId, String displayName, Instant sessionStartedAt, Instant lastInteractionAt, Instant updatedAt) {
}
