package biz.brumm.infrastructure.adapter.out.ai;

import biz.brumm.domain.model.AgentCommand;
import biz.brumm.domain.port.out.AiProviderPort;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

@Component
public class OllamaAiAdapter implements AiProviderPort {

    private final ChatClient.Builder chatClientBuilder;

    public OllamaAiAdapter(ChatClient.Builder chatClientBuilder) {
        this.chatClientBuilder = chatClientBuilder;
    }

    @Override
    public String generateAnswer(AgentCommand command, String systemPrompt) {
        ChatClient client = chatClientBuilder
                .defaultSystem(systemPrompt)
                .build();

        return client.prompt()
                .user(command.prompt())
                .call()
                .content();
    }
}