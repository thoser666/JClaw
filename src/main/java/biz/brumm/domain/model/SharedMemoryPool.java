package biz.brumm.domain.model;

import java.time.Instant;

/**
 * Gemeinsamer Langzeit-Memory-Pool, den mehrere Agents/Contexts teilen (P4-15).
 */
public record SharedMemoryPool(String id, String name, String description, Instant createdAt) {
}