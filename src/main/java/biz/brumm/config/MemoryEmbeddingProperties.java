package biz.brumm.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Konfiguration für das semantische Memory-Retrieval (P4-02):
 * {@code jclaw.memory.embedding.enabled}, {@code .base-url}, {@code .model}.
 */
@ConfigurationProperties(prefix = "jclaw.memory.embedding")
public record MemoryEmbeddingProperties(
        boolean enabled,
        String baseUrl,
        String model
) {
    public MemoryEmbeddingProperties {
        if (baseUrl == null || baseUrl.isBlank()) baseUrl = "http://localhost:11434";
        if (model == null || model.isBlank()) model = "nomic-embed-text";
    }
}