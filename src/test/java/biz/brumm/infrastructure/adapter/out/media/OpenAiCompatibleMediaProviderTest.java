package biz.brumm.infrastructure.adapter.out.media;

import biz.brumm.config.MediaProviderProperties;
import biz.brumm.domain.port.out.MediaProvider;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiCompatibleMediaProviderTest {

    private MediaProviderProperties props(String baseUrl) {
        return new MediaProviderProperties(true, "openai", baseUrl, "test-key", "tts-1", "alloy", "mp3", "mp3");
    }

    private HttpServer server(String responseBody, AtomicReference<String> path,
                              AtomicReference<String> auth) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        return server;
    }

    @Test
    void textToSpeechReturnsBase64Audio() throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        HttpServer server = server("{\"audio\":\"base64audiodata\"}", path, auth);
        OpenAiCompatibleMediaProvider provider = new OpenAiCompatibleMediaProvider(props("http://127.0.0.1:" + server.getAddress().getPort()));

        byte[] audio = provider.textToSpeech("Hallo Welt", "alloy", "mp3");

        assertThat(path.get()).isEqualTo("/v1/audio/speech");
        assertThat(auth.get()).isEqualTo("Bearer test-key");
        assertThat(audio).isEqualTo(java.util.Base64.getDecoder().decode("base64audiodata"));
        server.stop(0);
    }

    @Test
    void textToSpeechPropagatesErrors() throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        HttpServer server = server("{\"error\":{\"message\":\"Modell nicht gefunden\"}}", path, new AtomicReference<>());
        OpenAiCompatibleMediaProvider provider = new OpenAiCompatibleMediaProvider(props("http://127.0.0.1:" + server.getAddress().getPort()));

        assertThatThrownBy(() -> provider.textToSpeech("Hallo", null, null))
                .isInstanceOf(MediaProvider.MediaProviderException.class)
                .hasMessageContaining("Modell nicht gefunden");
        assertThat(path.get()).isEqualTo("/v1/audio/speech");
        server.stop(0);
    }

    @Test
    void speechToTextReturnsTranscription() throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        HttpServer server = server("{\"text\":\"Guten Morgen\"}", path, auth);
        OpenAiCompatibleMediaProvider provider = new OpenAiCompatibleMediaProvider(props("http://127.0.0.1:" + server.getAddress().getPort()));

        String text = provider.speechToText(new byte[] {1, 2, 3}, "mp3");

        assertThat(path.get()).isEqualTo("/v1/audio/transcriptions");
        assertThat(auth.get()).isEqualTo("Bearer test-key");
        assertThat(text).isEqualTo("Guten Morgen");
        server.stop(0);
    }

    @Test
    void unavailableWhenDisabled() {
        MediaProviderProperties disabled = new MediaProviderProperties(false, "openai", "http://x", "key", "m", "v", "f", "f");
        OpenAiCompatibleMediaProvider provider = new OpenAiCompatibleMediaProvider(disabled);
        assertThat(provider.isAvailable()).isFalse();
    }

    @Test
    void unavailableWhenNoApiKey() {
        MediaProviderProperties noKey = new MediaProviderProperties(true, "openai", "http://x", "", "m", "v", "f", "f");
        OpenAiCompatibleMediaProvider provider = new OpenAiCompatibleMediaProvider(noKey);
        assertThat(provider.isAvailable()).isFalse();
    }
}