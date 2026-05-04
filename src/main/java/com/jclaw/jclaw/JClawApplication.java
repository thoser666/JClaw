package com.jclaw.jclaw;

import org.springframework.ai.autoconfigure.ollama.OllamaAutoConfiguration;
import org.springframework.ai.autoconfigure.ollama.OllamaConnectionDetails;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication(exclude = {OllamaAutoConfiguration.class})
public class JClawApplication {

    public static void main(String[] args) {
        SpringApplication.run(JClawApplication.class, args);
    }

    /**
     * Workaround for Spring AI 1.0.0-M1 AOT issue.
     * Manually defining OllamaConnectionDetails and excluding OllamaAutoConfiguration
     * prevents the AOT processor from failing on the private PropertiesOllamaConnectionDetails 
     * inner class while still providing the necessary connection details.
     */
    @Bean
    public OllamaConnectionDetails ollamaConnectionDetails() {
        return new OllamaConnectionDetails() {
            @Override
            public String getBaseUrl() {
                return "http://localhost:11434";
            }
        };
    }

    @Bean
    public OllamaApi ollamaApi(OllamaConnectionDetails connectionDetails) {
        return new OllamaApi(connectionDetails.getBaseUrl());
    }

    @Bean
    public ChatModel ollamaChatModel(OllamaApi ollamaApi) {
        return new OllamaChatModel(ollamaApi);
    }
}
