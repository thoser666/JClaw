package biz.brumm.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Stellt ein {@link EmbeddingModel} (Ollama) bereit, wenn
 * {@code jclaw.memory.embedding.enabled=true} (Deny-by-Default).
 * Wird vom {@code MemoryRetrievalService} über {@code ObjectProvider} genutzt.
 */
@Configuration
@EnableConfigurationProperties(MemoryEmbeddingProperties.class)
public class MemoryEmbeddingConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "jclaw.memory.embedding", name = "enabled", havingValue = "true")
    public EmbeddingModel memoryEmbeddingModel(MemoryEmbeddingProperties properties) {
        OllamaApi api = OllamaApi.builder().baseUrl(properties.baseUrl()).build();
        OllamaEmbeddingOptions options = OllamaEmbeddingOptions.builder()
                .model(properties.model())
                .build();
        return OllamaEmbeddingModel.builder()
                .ollamaApi(api)
                .options(options)
                .build();
    }
}