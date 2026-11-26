package biz.brumm.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Konfiguration für den Speech/Media-Provider (P4-03):
 * {@code jclaw.media.provider.enabled}, {@code .provider}, {@code .base-url},
 * {@code .api-key}, {@code .model}, {@code .voice}, {@code .tts-format},
 * {@code .stt-format}.
 */
@ConfigurationProperties(prefix = "jclaw.media.provider")
public record MediaProviderProperties(
        boolean enabled,
        String provider,
        String baseUrl,
        String apiKey,
        String model,
        String voice,
        String ttsFormat,
        String sttFormat
) {
    public MediaProviderProperties {
        if (provider == null || provider.isBlank()) provider = "openai";
        if (baseUrl == null || baseUrl.isBlank()) baseUrl = "https://api.openai.com";
        if (model == null || model.isBlank()) model = "tts-1";
        if (ttsFormat == null || ttsFormat.isBlank()) ttsFormat = "mp3";
        if (sttFormat == null || sttFormat.isBlank()) sttFormat = "mp3";
    }
}