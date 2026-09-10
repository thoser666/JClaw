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

class SynologyChatChannelAdapterTest {

    private Channel channel(String base, Map<String, Object> extra) {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("incomingWebhookUrl", base + "/webhook/abc123");
        cfg.putAll(extra);
        return new Channel("sc1", "Synology Chat Test", ChannelType.SYNOSOLOGY_CHAT, true, cfg,
                Instant.now(), Instant.now());
    }

    @Test
    void channelTypeIsSynologyChat() {
        assertThat(new SynologyChatChannelAdapter().channelType()).isEqualTo(ChannelType.SYNOSOLOGY_CHAT);
    }

    @Test
    void availabilityRequiresEnabledAndWebhookUrl() {
        SynologyChatChannelAdapter adapter = new SynologyChatChannelAdapter();
        Channel ok = new Channel("c", "n", ChannelType.SYNOSOLOGY_CHAT, true,
                Map.of("incomingWebhookUrl", "https://synology:5000/webhook/x"), Instant.now(), Instant.now());
        Channel noWebhook = new Channel("c", "n", ChannelType.SYNOSOLOGY_CHAT, true, Map.of(),
                Instant.now(), Instant.now());
        Channel disabled = new Channel("c", "n", ChannelType.SYNOSOLOGY_CHAT, false,
                Map.of("incomingWebhookUrl", "https://synology:5000/webhook/x"), Instant.now(), Instant.now());

        assertThat(adapter.isAvailable(ok)).isTrue();
        assertThat(adapter.isAvailable(noWebhook)).isFalse();
        assertThat(adapter.isAvailable(disabled)).isFalse();
        assertThat(adapter.isAvailable(null)).isFalse();
    }

    @Test
    void sendPostsJsonToIncomingWebhook() throws Exception {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        withServer(exchange -> {
            method.set(exchange.getRequestMethod());
            path.set(exchange.getRequestURI().getPath());
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            body.set(readBody(exchange));
            respond(exchange, 200, "{\"text\":\"ok\"}");
        }, base -> {
            Channel ch = channel(base, Map.of("channel", "#general"));
            SynologyChatChannelAdapter adapter = new SynologyChatChannelAdapter();

            ChannelMessage sent = adapter.send(ch,
                    ChannelMessage.outbound(ch.id(), "Hallo Synology Chat", "town-square", null));

            assertThat(sent.direction()).isEqualTo(MessageDirection.OUTBOUND);
            assertThat(sent.threadId()).isEqualTo("town-square");
            assertThat(method.get()).isEqualTo("POST");
            assertThat(path.get()).isEqualTo("/webhook/abc123");
            assertThat(contentType.get()).isEqualTo("application/json");
            assertThat(body.get()).contains("\"text\":\"Hallo Synology Chat\"");
            assertThat(body.get()).contains("\"channel_name\":\"town-square\"");
        });
    }

    @Test
    void sendWithoutTargetFallsBackToConfiguredChannel() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        withServer(exchange -> {
            body.set(readBody(exchange));
            respond(exchange, 200, "{}");
        }, base -> {
            Channel ch = channel(base, Map.of("channel", "#general"));
            SynologyChatChannelAdapter adapter = new SynologyChatChannelAdapter();
            ChannelMessage outbound = new ChannelMessage("m1", ch.id(), null, MessageDirection.OUTBOUND,
                    "Hi", null, null, null, null, Instant.now());

            adapter.send(ch, outbound);

            assertThat(body.get()).contains("\"text\":\"Hi\"");
            assertThat(body.get()).contains("\"channel_name\":\"#general\"");
        });
    }

    @Test
    void sendWithoutAnyTargetOmitsChannelOverride() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        withServer(exchange -> {
            body.set(readBody(exchange));
            respond(exchange, 200, "{}");
        }, base -> {
            Channel ch = channel(base, Map.of());
            SynologyChatChannelAdapter adapter = new SynologyChatChannelAdapter();
            ChannelMessage outbound = new ChannelMessage("m1", ch.id(), null, MessageDirection.OUTBOUND,
                    "Hi", null, null, null, null, Instant.now());

            adapter.send(ch, outbound);

            assertThat(body.get()).contains("\"text\":\"Hi\"");
            assertThat(body.get()).doesNotContain("\"channel_name\"");
        });
    }

    @Test
    void sendWithoutWebhookUrlThrows() {
        SynologyChatChannelAdapter adapter = new SynologyChatChannelAdapter();
        Channel ch = new Channel("c", "n", ChannelType.SYNOSOLOGY_CHAT, true, Map.of(),
                Instant.now(), Instant.now());

        assertThatThrownBy(() -> adapter.send(ch,
                ChannelMessage.outbound(ch.id(), "Hi", "town-square", null)))
                .isInstanceOf(ChannelAdapter.ChannelException.class)
                .hasMessageContaining("incomingWebhookUrl");
    }

    @Test
    void sendReportsHttpError() throws Exception {
        withServer(exchange -> respond(exchange, 500, "{}"),
                base -> {
                    Channel ch = channel(base, Map.of());
                    SynologyChatChannelAdapter adapter = new SynologyChatChannelAdapter();

                    assertThatThrownBy(() -> adapter.send(ch,
                            ChannelMessage.outbound(ch.id(), "Hi", "town-square", null)))
                            .isInstanceOf(ChannelAdapter.ChannelException.class)
                            .hasMessageContaining("HTTP 500");
                });
    }

    // --- Empfang (Outgoing Webhook) ---

    @Test
    void verifyWebhookAcceptsMatchingToken() {
        SynologyChatChannelAdapter adapter = new SynologyChatChannelAdapter();
        Channel ch = channel("http://localhost:1", Map.of("outgoingWebhookToken", "sc-token"));

        assertThat(adapter.verifyWebhook(ch, "sc-token")).isTrue();
    }

    @Test
    void verifyWebhookRejectsWrongToken() {
        SynologyChatChannelAdapter adapter = new SynologyChatChannelAdapter();
        Channel ch = channel("http://localhost:1", Map.of("outgoingWebhookToken", "sc-token"));

        assertThat(adapter.verifyWebhook(ch, "falsch")).isFalse();
    }

    @Test
    void verifyWebhookWithoutConfiguredTokenAccepts() {
        SynologyChatChannelAdapter adapter = new SynologyChatChannelAdapter();
        Channel ch = channel("http://localhost:1", Map.of());

        assertThat(adapter.verifyWebhook(ch, "anything")).isTrue();
    }

    @Test
    void inboundFromWebhookParsesOutgoingWebhook() {
        SynologyChatChannelAdapter adapter = new SynologyChatChannelAdapter();
        Channel ch = channel("http://localhost:1", Map.of());
        String payload = "{"
                + "\"token\":\"sc-token\","
                + "\"team_name\":\"team1\","
                + "\"user_id\":\"user1\","
                + "\"user_name\":\"max\","
                + "\"channel_id\":\"channel1\","
                + "\"channel_name\":\"general\","
                + "\"text\":\"Hallo aus Synology Chat\","
                + "\"post_id\":\"post123\""
                + "}";

        ChannelMessage msg = adapter.inboundFromWebhook(ch, payload);

        assertThat(msg).isNotNull();
        assertThat(msg.content()).isEqualTo("Hallo aus Synology Chat");
        assertThat(msg.senderId()).isEqualTo("user1");
        assertThat(msg.senderName()).isEqualTo("max");
        assertThat(msg.threadId()).isEqualTo("channel1");
        assertThat(msg.externalId()).isEqualTo("post123");
        assertThat(msg.direction()).isEqualTo(MessageDirection.INBOUND);
    }

    @Test
    void inboundFromWebhookFallsBackToChannelNameWhenNoChannelId() {
        SynologyChatChannelAdapter adapter = new SynologyChatChannelAdapter();
        Channel ch = channel("http://localhost:1", Map.of());

        ChannelMessage msg = adapter.inboundFromWebhook(ch,
                "{\"channel_name\":\"general\",\"user_name\":\"max\",\"text\":\"Hi\",\"post_id\":\"p1\"}");

        assertThat(msg.threadId()).isEqualTo("general");
    }

    @Test
    void inboundFromWebhookIgnoresInvalidOrEmptyPayload() {
        SynologyChatChannelAdapter adapter = new SynologyChatChannelAdapter();
        Channel ch = channel("http://localhost:1", Map.of());

        assertThat(adapter.inboundFromWebhook(ch, "{ ungueltig")).isNull();
        assertThat(adapter.inboundFromWebhook(ch, "{}")).isNull();
        assertThat(adapter.inboundFromWebhook(ch, "{\"text\":\"   \"}")).isNull();
    }

    @Test
    void inboundFromWebhookFallsBackSenderForMissingUser() {
        SynologyChatChannelAdapter adapter = new SynologyChatChannelAdapter();
        Channel ch = channel("http://localhost:1", Map.of());

        ChannelMessage msg = adapter.inboundFromWebhook(ch,
                "{\"channel_id\":\"c1\",\"text\":\"Hallo\"}");

        assertThat(msg.senderId()).isEqualTo("unbekannt");
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