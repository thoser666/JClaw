package biz.brumm.infrastructure.adapter.out.channel;

import biz.brumm.domain.model.Channel;
import biz.brumm.domain.model.ChannelMessage;
import biz.brumm.domain.model.ChannelType;
import biz.brumm.domain.model.MessageDirection;
import biz.brumm.domain.port.out.ChannelAdapter;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoogleChatChannelAdapterTest {

    private Channel channel(String base, Map<String, Object> extra) {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("webhookUrl", base + "/v1/spaces/AAA/messages?key=k&amp;token=t");
        cfg.putAll(extra);
        return new Channel("gc1", "Google Chat Test", ChannelType.GOOGLE_CHAT, true, cfg,
                Instant.now(), Instant.now());
    }

    @Test
    void channelTypeIsGoogleChat() {
        assertThat(new GoogleChatChannelAdapter().channelType()).isEqualTo(ChannelType.GOOGLE_CHAT);
    }

    @Test
    void availabilityRequiresEnabledAndWebhookUrl() {
        GoogleChatChannelAdapter adapter = new GoogleChatChannelAdapter();
        Channel ok = new Channel("c", "n", ChannelType.GOOGLE_CHAT, true,
                Map.of("webhookUrl", "https://chat.googleapis.com/v1/spaces/AAA/messages"), Instant.now(), Instant.now());
        Channel noWebhook = new Channel("c", "n", ChannelType.GOOGLE_CHAT, true, Map.of(),
                Instant.now(), Instant.now());
        Channel disabled = new Channel("c", "n", ChannelType.GOOGLE_CHAT, false,
                Map.of("webhookUrl", "https://chat.googleapis.com/v1/spaces/AAA/messages"), Instant.now(), Instant.now());

        assertThat(adapter.isAvailable(ok)).isTrue();
        assertThat(adapter.isAvailable(noWebhook)).isFalse();
        assertThat(adapter.isAvailable(disabled)).isFalse();
        assertThat(adapter.isAvailable(null)).isFalse();
    }

    @Test
    void sendPostsJsonToWebhookAndCapturesExternalId() throws Exception {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        withServer(exchange -> {
            method.set(exchange.getRequestMethod());
            path.set(exchange.getRequestURI().getRawPath());
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            body.set(readBody(exchange));
            respond(exchange, 200, "{\"name\":\"spaces/AAA/messages/BBB\"}");
        }, base -> {
            GoogleChatChannelAdapter adapter = new GoogleChatChannelAdapter();
            Channel ch = channel(base, Map.of());
            ChannelMessage outbound = ChannelMessage.outbound(ch.id(), "Hallo Google Chat", null, null);

            ChannelMessage sent = adapter.send(ch, outbound);

            assertThat(sent.direction()).isEqualTo(MessageDirection.OUTBOUND);
            assertThat(sent.externalId()).isEqualTo("spaces/AAA/messages/BBB");
            assertThat(sent.threadId()).isNull();
            assertThat(method.get()).isEqualTo("POST");
            assertThat(path.get()).isEqualTo("/v1/spaces/AAA/messages");
            assertThat(contentType.get()).isEqualTo("application/json");
            assertThat(body.get()).contains("\"text\":\"Hallo Google Chat\"");
        });
    }

    @Test
    void sendWithoutContentThrows() throws Exception {
        withServer(exchange -> respond(exchange, 200, "{}"),
                base -> {
                    GoogleChatChannelAdapter adapter = new GoogleChatChannelAdapter();
                    Channel ch = channel(base, Map.of());

                    assertThatThrownBy(() -> adapter.send(ch,
                            ChannelMessage.outbound(ch.id(), "   ", null, null)))
                            .isInstanceOf(ChannelAdapter.ChannelException.class)
                            .hasMessageContaining("leer");
                });
    }

    @Test
    void sendWithoutWebhookUrlThrows() {
        GoogleChatChannelAdapter adapter = new GoogleChatChannelAdapter();
        Channel ch = new Channel("c", "n", ChannelType.GOOGLE_CHAT, true, Map.of(),
                Instant.now(), Instant.now());

        assertThatThrownBy(() -> adapter.send(ch,
                ChannelMessage.outbound(ch.id(), "Hi", null, null)))
                .isInstanceOf(ChannelAdapter.ChannelException.class)
                .hasMessageContaining("webhookUrl");
    }

    @Test
    void sendReportsHttpError() throws Exception {
        withServer(exchange -> respond(exchange, 401, "{}"),
                base -> {
                    GoogleChatChannelAdapter adapter = new GoogleChatChannelAdapter();
                    Channel ch = channel(base, Map.of());

                    assertThatThrownBy(() -> adapter.send(ch,
                            ChannelMessage.outbound(ch.id(), "Hi", null, null)))
                            .isInstanceOf(ChannelAdapter.ChannelException.class)
                            .hasMessageContaining("HTTP 401");
                });
    }

    @Test
    void sendIgnoresEmptyResponseBody() throws Exception {
        withServer(exchange -> respond(exchange, 200, ""),
                base -> {
                    GoogleChatChannelAdapter adapter = new GoogleChatChannelAdapter();
                    Channel ch = channel(base, Map.of());

                    ChannelMessage sent = adapter.send(ch,
                            ChannelMessage.outbound(ch.id(), "Hi", null, null));

                    assertThat(sent.externalId()).isNull();
                });
    }

    // --- Empfang (Ereignis-Webhook) ---

    @Test
    void verifyWebhookAcceptsMatchingToken() {
        GoogleChatChannelAdapter adapter = new GoogleChatChannelAdapter();
        Channel ch = channel("http://localhost:1", Map.of("verifyToken", "gc-token"));

        assertThat(adapter.verifyWebhook(ch, "gc-token")).isTrue();
    }

    @Test
    void verifyWebhookRejectsWrongToken() {
        GoogleChatChannelAdapter adapter = new GoogleChatChannelAdapter();
        Channel ch = channel("http://localhost:1", Map.of("verifyToken", "gc-token"));

        assertThat(adapter.verifyWebhook(ch, "falsch")).isFalse();
    }

    @Test
    void verifyWebhookWithoutConfiguredTokenAccepts() {
        GoogleChatChannelAdapter adapter = new GoogleChatChannelAdapter();
        Channel ch = channel("http://localhost:1", Map.of());

        assertThat(adapter.verifyWebhook(ch, "anything")).isTrue();
    }

    @Test
    void inboundFromWebhookParsesMessageEvent() {
        GoogleChatChannelAdapter adapter = new GoogleChatChannelAdapter();
        Channel ch = channel("http://localhost:1", Map.of());
        String payload = "{"
                + "\"type\":\"MESSAGE\","
                + "\"message\":{"
                + "  \"name\":\"spaces/AAA/messages/BBB\","
                + "  \"text\":\"@jclaw Hallo aus Google Chat\","
                + "  \"argumentText\":\"Hallo aus Google Chat\","
                + "  \"sender\":{\"name\":\"users/123\",\"displayName\":\"Max\"},"
                + "  \"space\":{\"name\":\"spaces/AAA\",\"type\":\"DM\"}"
                + "}"
                + "}";

        ChannelMessage msg = adapter.inboundFromWebhook(ch, payload);

        assertThat(msg).isNotNull();
        assertThat(msg.content()).isEqualTo("Hallo aus Google Chat");
        assertThat(msg.senderId()).isEqualTo("users/123");
        assertThat(msg.senderName()).isEqualTo("Max");
        assertThat(msg.threadId()).isEqualTo("spaces/AAA");
        assertThat(msg.externalId()).isEqualTo("spaces/AAA/messages/BBB");
        assertThat(msg.direction()).isEqualTo(MessageDirection.INBOUND);
    }

    @Test
    void inboundFromWebhookWithoutArgumentTextStripsMention() {
        GoogleChatChannelAdapter adapter = new GoogleChatChannelAdapter();
        Channel ch = channel("http://localhost:1", Map.of());
        String payload = "{"
                + "\"type\":\"MESSAGE\","
                + "\"message\":{"
                + "  \"name\":\"spaces/AAA/messages/BBB\","
                + "  \"text\":\"@jclaw Hallo aus Google Chat\","
                + "  \"sender\":{\"name\":\"users/123\"},"
                + "  \"space\":{\"name\":\"spaces/AAA\"}"
                + "}"
                + "}";

        ChannelMessage msg = adapter.inboundFromWebhook(ch, payload);

        assertThat(msg).isNotNull();
        assertThat(msg.content()).isEqualTo("Hallo aus Google Chat");
        assertThat(msg.senderName()).isEqualTo("users/123");
    }

    @Test
    void inboundFromWebhookIgnoresNonMessageEvents() {
        GoogleChatChannelAdapter adapter = new GoogleChatChannelAdapter();
        Channel ch = channel("http://localhost:1", Map.of());

        assertThat(adapter.inboundFromWebhook(ch, "{\"type\":\"ADDED_TO_SPACE\"}")).isNull();
        assertThat(adapter.inboundFromWebhook(ch, "{}")).isNull();
        assertThat(adapter.inboundFromWebhook(ch, "{ ungueltig")).isNull();
    }

    // --- Helfer ---

    private static void withServer(Consumer<HttpExchange> handler, ThrowingConsumer<String> test)
            throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                handler.accept(exchange);
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            test.accept("http://127.0.0.1:" + server.getAddress().getPort());
        } finally {
            server.stop(0);
        }
    }

    @FunctionalInterface
    interface ThrowingConsumer<T> {
        void accept(T t) throws Exception;
    }

    private static void respond(HttpExchange exchange, int status, String content) {
        try {
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static String readBody(HttpExchange exchange) {
        try {
            return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}