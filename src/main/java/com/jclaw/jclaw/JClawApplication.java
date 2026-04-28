package com.jclaw.jclaw;

import org.springframework.ai.autoconfigure.ollama.OllamaConnectionDetails;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class JClawApplication {

    public static void main(String[] args) {
        SpringApplication.run(JClawApplication.class, args);
    }

    /**
     * Workaround for Spring AI 1.0.0-M1 AOT issue.
     * Manually defining OllamaConnectionDetails prevents the AOT processor from 
     * failing on the private PropertiesOllamaConnectionDetails inner class.
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
}
