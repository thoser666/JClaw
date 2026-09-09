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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FeishuChannelAdapterTest {

    private Channel channel(String base, Map<String, Object> extra) {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("webhookUrl", base + "/hook/abc123");
        cfg.putAll(extra);
        return new Channel("fs1", "Feishu Test", ChannelType.FEISHU, true, cfg,
                Instant.now(), Instant.now());
    }

    @Test
    void channelTypeIsFeishu() {
        assertThat(new FeishuChannelAdapter().channelType()).isEqualTo(ChannelType.FEISHU);
    }

    @Test
    void availabilityRequiresEnabledAndWebhookUrl() {
        FeishuChannelAdapter adapter = new FeishuChannelAdapter();
        Channel ok = new Channel("c", "n", ChannelType.FEISHU, true,
                Map.of("webhookUrl", "https://open.feishu.cn/hook/x"), Instant.now(), Instant.now());
        Channel noWebhook = new Channel("c", "n", ChannelType.FEISHU, true, Map.of(),
                Instant.now(), Instant.now());
        Channel disabled = new Channel("c", "n", ChannelType.FEISHU, false,
                Map.of("webhookUrl", "https://open.feishu.cn/hook/x"), Instant.now(), Instant.now());

        assertThat(adapter.isAvailable(ok)).isTrue();
        assertThat(adapter.isAvailable(noWebhook)).isFalse();
        assertThat(adapter.isAvailable(disabled)).isFalse();
        assertThat(adapter.isAvailable(null)).isFalse();
    }

    @Test
    void sendPostsJsonToWebhookAndCapturesExternalId() throws Exception {
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        withServer(exchange -> {
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            body.set(readBody(exchange));
            respond(exchange, 200, "{\"code\":0,\"msg\":\"success\",\"data\":{\"message_id\":\"om_abc123\"}}");
        }, base -> {
            FeishuChannelAdapter adapter = new FeishuChannelAdapter();
            Channel ch = channel(base, Map.of());

            ChannelMessage sent = adapter.send(ch,
                    ChannelMessage.outbound(ch.id(), "Hallo Feishu", null, null));

            assertThat(sent.direction()).isEqualTo(MessageDirection.OUTBOUND);
            assertThat(sent.externalId()).isEqualTo("om_abc123");
            assertThat(sent.threadId()).isNull();
            assertThat(contentType.get()).isEqualTo("application/json; charset=utf-8");
            assertThat(body.get()).contains("\"msg_type\":\"text\"");
            assertThat(body.get()).contains("\"text\":\"Hallo Feishu\"");
        });
    }

    @Test
    void sendWithThreadIdSetsTarget() throws Exception {
        withServer(exchange -> respond(exchange, 200, "{\"code\":0}"),
                base -> {
                    FeishuChannelAdapter adapter = new FeishuChannelAdapter();
                    Channel ch = channel(base, Map.of());

                    ChannelMessage sent = adapter.send(ch,
                            ChannelMessage.outbound(ch.id(), "Hi", "oc_group1", null));

                    assertThat(sent.threadId()).isEqualTo("oc_group1");
                });
    }

    @Test
    void sendWithoutContentThrows() throws Exception {
        withServer(exchange -> respond(exchange, 200, "{\"code\":0}"),
                base -> {
                    FeishuChannelAdapter adapter = new FeishuChannelAdapter();
                    Channel ch = channel(base, Map.of());

                    assertThatThrownBy(() -> adapter.send(ch,
                            ChannelMessage.outbound(ch.id(), "   ", null, null)))
                            .isInstanceOf(ChannelAdapter.ChannelException.class)
                            .hasMessageContaining("leer");
                });
    }

    @Test
    void sendWithoutWebhookUrlThrows() {
        FeishuChannelAdapter adapter = new FeishuChannelAdapter();
        Channel ch = new Channel("c", "n", ChannelType.FEISHU, true, Map.of(),
                Instant.now(), Instant.now());

        assertThatThrownBy(() -> adapter.send(ch,
                ChannelMessage.outbound(ch.id(), "Hi", null, null)))
                .isInstanceOf(ChannelAdapter.ChannelException.class)
                .hasMessageContaining("webhookUrl");
    }

    @Test
    void sendReportsHttpError() throws Exception {
        withServer(exchange -> respond(exchange, 403, "{}"),
                base -> {
                    FeishuChannelAdapter adapter = new FeishuChannelAdapter();
                    Channel ch = channel(base, Map.of());

                    assertThatThrownBy(() -> adapter.send(ch,
                            ChannelMessage.outbound(ch.id(), "Hi", null, null)))
                            .isInstanceOf(ChannelAdapter.ChannelException.class)
                            .hasMessageContaining("HTTP 403");
                });
    }

    @Test
    void sendIgnoresNonZeroCodeInResponse() throws Exception {
        withServer(exchange -> respond(exchange, 200, "{\"code\":9499,\"msg\":\"token invalid\"}"),
                base -> {
                    FeishuChannelAdapter adapter = new FeishuChannelAdapter();
                    Channel ch = channel(base, Map.of());

                    ChannelMessage sent = adapter.send(ch,
                            ChannelMessage.outbound(ch.id(), "Hi", null, null));

                    assertThat(sent.externalId()).isNull();
                });
    }

    // --- Empfang (Event-Subscription-Webhook) ---

    @Test
    void verifyWebhookAcceptsMatchingToken() {
        FeishuChannelAdapter adapter = new FeishuChannelAdapter();
        Channel ch = channel("http://localhost:1", Map.of("verifyToken", "fs-token"));

        assertThat(adapter.verifyWebhook(ch, "fs-token")).isTrue();
    }

    @Test
    void verifyWebhookRejectsWrongToken() {
        FeishuChannelAdapter adapter = new FeishuChannelAdapter();
        Channel ch = channel("http://localhost:1", Map.of("verifyToken", "fs-token"));

        assertThat(adapter.verifyWebhook(ch, "falsch")).isFalse();
    }

    @Test
    void verifyWebhookWithoutConfiguredTokenAccepts() {
        FeishuChannelAdapter adapter = new FeishuChannelAdapter();
        Channel ch = channel("http://localhost:1", Map.of());

        assertThat(adapter.verifyWebhook(ch, "anything")).isTrue();
    }

    @Test
    void inboundFromWebhookParsesEventCallback() {
        FeishuChannelAdapter adapter = new FeishuChannelAdapter();
        Channel ch = channel("http://localhost:1", Map.of());
        String payload = "{"
                + "\"token\":\"fs-token\","
                + "\"type\":\"event_callback\","
                + "\"event\":{"
                + "  \"sender\":{"
                + "    \"sender_id\":{\"user_id\":\"u1\",\"open_id\":\"ou1\"},"
                + "    \"sender_type\":\"user\""
                + "  },"
                + "  \"message\":{"
                + "    \"message_id\":\"om_abc\","
                + "    \"chat_id\":\"oc_group\","
                + "    \"content\":\"{\\\"text\\\":\\\"Hallo aus Feishu\\\"}\","
                + "    \"message_type\":\"text\""
                + "  }"
                + "}"
                + "}";

        ChannelMessage msg = adapter.inboundFromWebhook(ch, payload);

        assertThat(msg).isNotNull();
        assertThat(msg.content()).isEqualTo("Hallo aus Feishu");
        assertThat(msg.senderId()).isEqualTo("u1");
        assertThat(msg.threadId()).isEqualTo("oc_group");
        assertThat(msg.externalId()).isEqualTo("om_abc");
        assertThat(msg.direction()).isEqualTo(MessageDirection.INBOUND);
    }

    @Test
    void inboundFromWebhookFallsBackToOpenId() {
        FeishuChannelAdapter adapter = new FeishuChannelAdapter();
        Channel ch = channel("http://localhost:1", Map.of());
        String payload = "{"
                + "\"type\":\"event_callback\","
                + "\"event\":{"
                + "  \"sender\":{\"sender_id\":{\"open_id\":\"ou_x\"}},"
                + "  \"message\":{"
                + "    \"chat_id\":\"oc_g\","
                + "    \"content\":\"{\\\"text\\\":\\\"Hi\\\"}\","
                + "    \"message_type\":\"text\""
                + "  }"
                + "}"
                + "}";

        ChannelMessage msg = adapter.inboundFromWebhook(ch, payload);

        assertThat(msg.senderId()).isEqualTo("ou_x");
    }

    @Test
    void inboundFromWebhookIgnoresNonTextMessages() {
        FeishuChannelAdapter adapter = new FeishuChannelAdapter();
        Channel ch = channel("http://localhost:1", Map.of());

        String imageEvent = "{\"type\":\"event_callback\",\"event\":{\"message\":{\"message_type\":\"image\",\"chat_id\":\"oc\"}}}";
        assertThat(adapter.inboundFromWebhook(ch, imageEvent)).isNull();

        String urlVerification = "{\"type\":\"url_verification\",\"challenge\":\"abc\"}";
        assertThat(adapter.inboundFromWebhook(ch, urlVerification)).isNull();

        assertThat(adapter.inboundFromWebhook(ch, "{}")).isNull();
        assertThat(adapter.inboundFromWebhook(ch, "{ ungueltig")).isNull();
    }

    @Test
    void inboundFromWebhookFallsBackSenderIdToUnbekannt() {
        FeishuChannelAdapter adapter = new FeishuChannelAdapter();
        Channel ch = channel("http://localhost:1", Map.of());

        ChannelMessage msg = adapter.inboundFromWebhook(ch, "{\"type\":\"event_callback\",\"event\":{\"sender\":{\"sender_type\":\"bot\"},\"message\":{\"chat_id\":\"oc\",\"content\":\"{\\\"text\\\":\\\"Hallo\\\"}\",\"message_type\":\"text\"}}}");

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