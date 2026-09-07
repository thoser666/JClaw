package biz.brumm.infrastructure.adapter.out.channel;

import biz.brumm.domain.model.Channel;
import biz.brumm.domain.model.ChannelMessage;
import biz.brumm.domain.model.ChannelType;
import biz.brumm.domain.model.MessageDirection;
import biz.brumm.domain.port.out.ChannelAdapter;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MattermostChannelAdapterTest {

    private Channel channel(String base, Map<String, Object> extra) {
        Map<String, Object> cfg = new java.util.HashMap<>();
        cfg.put("incomingWebhookUrl", base + "/hooks/abc123");
        cfg.putAll(extra);
        return new Channel("mm1", "Mattermost Test", ChannelType.MATTERMOST, true, cfg,
                Instant.now(), Instant.now());
    }

    @Test
    void channelTypeIsMattermost() {
        assertThat(new MattermostChannelAdapter().channelType()).isEqualTo(ChannelType.MATTERMOST);
    }

    @Test
    void availabilityRequiresEnabledAndWebhookUrl() {
        MattermostChannelAdapter adapter = new MattermostChannelAdapter();
        Channel ok = new Channel("c", "n", ChannelType.MATTERMOST, true,
                Map.of("incomingWebhookUrl", "https://mm.example/hooks/x"), Instant.now(), Instant.now());
        Channel noWebhook = new Channel("c", "n", ChannelType.MATTERMOST, true, Map.of(),
                Instant.now(), Instant.now());
        Channel disabled = new Channel("c", "n", ChannelType.MATTERMOST, false,
                Map.of("incomingWebhookUrl", "https://mm.example/hooks/x"), Instant.now(), Instant.now());

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
            respond(exchange, 200, "{}");
        }, base -> {
            Channel ch = channel(base, Map.of("username", "jclaw"));
            MattermostChannelAdapter adapter = new MattermostChannelAdapter();

            ChannelMessage sent = adapter.send(ch,
                    ChannelMessage.outbound(ch.id(), "Hallo Mattermost", "town-square", null));

            assertThat(sent.direction()).isEqualTo(MessageDirection.OUTBOUND);
            assertThat(sent.threadId()).isEqualTo("town-square");
            assertThat(method.get()).isEqualTo("POST");
            assertThat(path.get()).isEqualTo("/hooks/abc123");
            assertThat(contentType.get()).isEqualTo("application/json");
            assertThat(body.get()).contains("\"text\":\"Hallo Mattermost\"");
            assertThat(body.get()).contains("\"channel\":\"town-square\"");
            assertThat(body.get()).contains("\"username\":\"jclaw\"");
        });
    }

    @Test
    void sendWithoutTargetOmitsChannelOverride() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        withServer(exchange -> {
            body.set(readBody(exchange));
            respond(exchange, 200, "{}");
        }, base -> {
            Channel ch = channel(base, Map.of());
            MattermostChannelAdapter adapter = new MattermostChannelAdapter();
            ChannelMessage outbound = new ChannelMessage("m1", ch.id(), null, MessageDirection.OUTBOUND,
                    "Hi", null, null, null, null, Instant.now());

            adapter.send(ch, outbound);

            assertThat(body.get()).contains("\"text\":\"Hi\"");
            assertThat(body.get()).doesNotContain("\"channel\"");
        });
    }

    @Test
    void sendWithoutWebhookUrlThrows() {
        MattermostChannelAdapter adapter = new MattermostChannelAdapter();
        Channel ch = new Channel("c", "n", ChannelType.MATTERMOST, true, Map.of(),
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
                    MattermostChannelAdapter adapter = new MattermostChannelAdapter();

                    assertThatThrownBy(() -> adapter.send(ch,
                            ChannelMessage.outbound(ch.id(), "Hi", "town-square", null)))
                            .isInstanceOf(ChannelAdapter.ChannelException.class)
                            .hasMessageContaining("HTTP 500");
                });
    }

    // --- Empfang (Outgoing Webhook) ---

    @Test
    void verifyWebhookAcceptsMatchingToken() {
        MattermostChannelAdapter adapter = new MattermostChannelAdapter();
        Channel ch = channel("http://localhost:1", Map.of("outgoingWebhookToken", "mm-token"));

        assertThat(adapter.verifyWebhook(ch, "mm-token")).isTrue();
    }

    @Test
    void verifyWebhookRejectsWrongToken() {
        MattermostChannelAdapter adapter = new MattermostChannelAdapter();
        Channel ch = channel("http://localhost:1", Map.of("outgoingWebhookToken", "mm-token"));

        assertThat(adapter.verifyWebhook(ch, "falsch")).isFalse();
    }

    @Test
    void verifyWebhookWithoutConfiguredTokenAccepts() {
        MattermostChannelAdapter adapter = new MattermostChannelAdapter();
        Channel ch = channel("http://localhost:1", Map.of());

        assertThat(adapter.verifyWebhook(ch, "anything")).isTrue();
    }

    @Test
    void inboundFromWebhookParsesOutgoingWebhook() {
        MattermostChannelAdapter adapter = new MattermostChannelAdapter();
        Channel ch = channel("http://localhost:1", Map.of());
        String payload = "{"
                + "\"token\":\"mm-token\","
                + "\"team_id\":\"team1\","
                + "\"channel_id\":\"channel1\","
                + "\"channel_name\":\"town-square\","
                + "\"timestamp\":1498239987679,"
                + "\"user_id\":\"user1\","
                + "\"user_name\":\"max\","
                + "\"post_id\":\"post123\","
                + "\"text\":\"@bot Hallo aus Mattermost\","
                + "\"trigger_word\":\"@bot\""
                + "}";

        ChannelMessage msg = adapter.inboundFromWebhook(ch, payload);

        assertThat(msg).isNotNull();
        assertThat(msg.content()).isEqualTo("Hallo aus Mattermost");
        assertThat(msg.senderId()).isEqualTo("user1");
        assertThat(msg.senderName()).isEqualTo("max");
        assertThat(msg.threadId()).isEqualTo("channel1");
        assertThat(msg.externalId()).isEqualTo("post123");
        assertThat(msg.direction()).isEqualTo(MessageDirection.INBOUND);
    }

    @Test
    void inboundFromWebhookWithoutTriggerKeepsText() {
        MattermostChannelAdapter adapter = new MattermostChannelAdapter();
        Channel ch = channel("http://localhost:1", Map.of());
        String payload = "{"
                + "\"channel_id\":\"channel1\","
                + "\"user_id\":\"user1\","
                + "\"user_name\":\"max\","
                + "\"text\":\"einfache Nachricht\""
                + "}";

        ChannelMessage msg = adapter.inboundFromWebhook(ch, payload);

        assertThat(msg.content()).isEqualTo("einfache Nachricht");
        assertThat(msg.threadId()).isEqualTo("channel1");
        assertThat(msg.externalId()).isNull();
    }

    @Test
    void inboundFromWebhookIgnoresInvalidOrEmptyPayload() {
        MattermostChannelAdapter adapter = new MattermostChannelAdapter();
        Channel ch = channel("http://localhost:1", Map.of());

        assertThat(adapter.inboundFromWebhook(ch, "{ ungueltig")).isNull();
        assertThat(adapter.inboundFromWebhook(ch, "{}")).isNull();
        assertThat(adapter.inboundFromWebhook(ch, "{\"text\":\"   \"}")).isNull();
    }

    @Test
    void inboundFromWebhookFallsBackSenderForMissingUser() {
        MattermostChannelAdapter adapter = new MattermostChannelAdapter();
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