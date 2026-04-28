package com.jclaw.jclaw.service;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class JClawService {

    private final ChatModel chatModel;

    @Value("classpath:/prompts/system-agent.st")
    private Resource systemPromptResource;

    public JClawService(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    public String processWithSystemPrompt(String userMessage) {
        // Variablen für das Template füllen
        SystemPromptTemplate systemPromptTemplate = new SystemPromptTemplate(systemPromptResource);
        var systemMessage = systemPromptTemplate.createMessage(Map.of(
                "current_time", LocalDateTime.now().toString(),
                "os_name", System.getProperty("os.name"),
                "user_home", System.getProperty("user.home")
        ));

        // User Nachricht hinzufügen
        var userMessageObj = new UserMessage(userMessage);

        // Prompt senden
        Prompt prompt = new Prompt(List.of(systemMessage, userMessageObj));
        return chatModel.call(prompt).getResult().getOutput().getContent();
    }
}
