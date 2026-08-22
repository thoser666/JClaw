package biz.brumm.domain.model;

import java.time.Instant;

public record ApiKey(String id, String name, String tokenHash, Instant createdAt) {
}
