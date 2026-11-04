package biz.brumm.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "jclaw.ai.openai")
public record OpenAiProperties(String baseUrl, String apiKey, String model) {
    public OpenAiProperties {
        if (baseUrl == null || baseUrl.isBlank()) baseUrl = "https://api.openai.com";
        if (apiKey == null) apiKey = "";
        if (model == null || model.isBlank()) model = "gpt-4o-mini";
    }
}