package biz.brumm.infrastructure.adapter.out.ai;

import biz.brumm.config.OpenAiProperties;
import biz.brumm.domain.model.AgentCommand;
import biz.brumm.domain.model.AgentResponse;
import biz.brumm.domain.port.out.ToolPolicy;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OpenAiCompatibleAiAdapterTest {

    @Test
    void executeReturnsContentFromOpenAiEndpoint() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            String body = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Hallo von OpenAI\"},\"finish_reason\":\"stop\"}]}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        int port = server.getAddress().getPort();

        OpenAiProperties props = new OpenAiProperties("http://localhost:" + port, "test-key", "gpt-test");
        ToolPolicy policy = name -> true;
        org.springframework.ai.chat.memory.ChatMemory memory = mock(org.springframework.ai.chat.memory.ChatMemory.class);
        when(memory.get(org.mockito.ArgumentMatchers.anyString())).thenReturn(java.util.List.of());

        OpenAiCompatibleAiAdapter adapter = new OpenAiCompatibleAiAdapter(
                props, java.util.List.of(), mock(org.springframework.beans.factory.ObjectProvider.class),
                memory, policy,
                mock(org.springframework.beans.factory.ObjectProvider.class),
                mock(org.springframework.beans.factory.ObjectProvider.class),
                mock(org.springframework.beans.factory.ObjectProvider.class),
                mock(org.springframework.beans.factory.ObjectProvider.class));

        AgentResponse response = adapter.execute(new AgentCommand("Hi", null), "System", 3);
        assertThat(response.content()).isEqualTo("Hallo von OpenAI");

        server.stop(0);
    }
}