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

class WhatsAppChannelAdapterTest {

    private Channel channel(String base) {
        return new Channel("w1", "WhatsApp Test", ChannelType.WHATSAPP, true,
                Map.of("token", "wa-token", "phoneNumberId", "123456789", "graphUrl", base),
                Instant.now(), Instant.now());
    }

    @Test
    void channelTypeIsWhatsApp() {
        assertThat(new WhatsAppChannelAdapter().channelType()).isEqualTo(ChannelType.WHATSAPP);
    }

    @Test
    void availabilityRequiresEnabledTokenAndPhoneNumberId() {
        WhatsAppChannelAdapter adapter = new WhatsAppChannelAdapter();
        Channel ok = new Channel("c", "n", ChannelType.WHATSAPP, true,
                Map.of("token", "t", "phoneNumberId", "1"), Instant.now(), Instant.now());
        Channel noToken = new Channel("c", "n", ChannelType.WHATSAPP, true,
                Map.of("phoneNumberId", "1"), Instant.now(), Instant.now());
        Channel noPhone = new Channel("c", "n", ChannelType.WHATSAPP, true,
                Map.of("token", "t"), Instant.now(), Instant.now());
        Channel disabled = new Channel("c", "n", ChannelType.WHATSAPP, false,
                Map.of("token", "t", "phoneNumberId", "1"), Instant.now(), Instant.now());

        assertThat(adapter.isAvailable(ok)).isTrue();
        assertThat(adapter.isAvailable(noToken)).isFalse();
        assertThat(adapter.isAvailable(noPhone)).isFalse();
        assertThat(adapter.isAvailable(disabled)).isFalse();
        assertThat(adapter.isAvailable(null)).isFalse();
    }

    @Test
    void sendPostsMessageAndCapturesId() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        withServer(exchange -> {
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            path.set(exchange.getRequestURI().getPath());
            body.set(readBody(exchange));
            respond(exchange, 200,
                    "{\"messages\":[{\"id\":\"wamid.ABC123\"}],\"messaging_product\":\"whatsapp\"}");
        }, base -> {
            Channel ch = channel(base);
            WhatsAppChannelAdapter adapter = new WhatsAppChannelAdapter();
            ChannelMessage outbound = ChannelMessage.outbound(ch.id(), "Hallo WhatsApp", "4915123456789", null);

            ChannelMessage sent = adapter.send(ch, outbound);

            assertThat(sent.externalId()).isEqualTo("wamid.ABC123");
            assertThat(sent.direction()).isEqualTo(MessageDirection.OUTBOUND);
            assertThat(auth.get()).isEqualTo("Bearer wa-token");
            assertThat(path.get()).isEqualTo("/123456789/messages");
            assertThat(body.get()).contains("\"messaging_product\":\"whatsapp\"")
                    .contains("\"to\":\"4915123456789\"")
                    .contains("\"text\":{\"body\":\"Hallo WhatsApp\"}");
        });
    }

    @Test
    void sendWithoutRecipientThrows() throws Exception {
        Channel ch = channel("http://localhost:1");
        WhatsAppChannelAdapter adapter = new WhatsAppChannelAdapter();
        ChannelMessage outbound = ChannelMessage.outbound(ch.id(), "Hi", null, null);

        assertThatThrownBy(() -> adapter.send(ch, outbound))
                .isInstanceOf(ChannelAdapter.ChannelException.class)
                .hasMessageContaining("WhatsApp-Empfaenger");
    }

    @Test
    void sendMissingTokenThrows() throws Exception {
        Channel ch = new Channel("w1", "x", ChannelType.WHATSAPP, true,
                Map.of("phoneNumberId", "123", "graphUrl", "http://localhost:1"),
                Instant.now(), Instant.now());
        WhatsAppChannelAdapter adapter = new WhatsAppChannelAdapter();
        ChannelMessage outbound = ChannelMessage.outbound(ch.id(), "Hi", "4915", null);

        assertThatThrownBy(() -> adapter.send(ch, outbound))
                .isInstanceOf(ChannelAdapter.ChannelException.class)
                .hasMessageContaining("Token");
    }

    @Test
    void sendMissingPhoneNumberIdThrows() throws Exception {
        Channel ch = new Channel("w1", "x", ChannelType.WHATSAPP, true,
                Map.of("token", "t", "graphUrl", "http://localhost:1"), Instant.now(), Instant.now());
        WhatsAppChannelAdapter adapter = new WhatsAppChannelAdapter();
        ChannelMessage outbound = ChannelMessage.outbound(ch.id(), "Hi", "4915", null);

        assertThatThrownBy(() -> adapter.send(ch, outbound))
                .isInstanceOf(ChannelAdapter.ChannelException.class)
                .hasMessageContaining("phoneNumberId");
    }

    @Test
    void sendReportsApiError() throws Exception {
        withServer(exchange -> respond(exchange, 400, "{\"error\":{\"message\":\"Invalid OAuth\"}}"),
                base -> {
                    Channel ch = channel(base);
                    WhatsAppChannelAdapter adapter = new WhatsAppChannelAdapter();
                    ChannelMessage outbound = ChannelMessage.outbound(ch.id(), "Hi", "4915", null);

                    assertThatThrownBy(() -> adapter.send(ch, outbound))
                            .isInstanceOf(ChannelAdapter.ChannelException.class)
                            .hasMessageContaining("HTTP 400");
                });
    }

    @Test
    void verifyWebhookReturnsChallengeWhenValid() {
        Channel ch = new Channel("w1", "x", ChannelType.WHATSAPP, true,
                Map.of("token", "t", "phoneNumberId", "1", "verifyToken", "sekret"),
                Instant.now(), Instant.now());
        WhatsAppChannelAdapter adapter = new WhatsAppChannelAdapter();

        assertThat(adapter.verifyWebhook(ch, "subscribe", "sekret", "challenge-abc"))
                .isEqualTo("challenge-abc");
        assertThat(adapter.verifyWebhook(ch, "wrong", "sekret", "challenge-abc")).isNull();
        assertThat(adapter.verifyWebhook(ch, "subscribe", "falsch", "challenge-abc")).isNull();
    }

    @Test
    void inboundFromWebhookExtractsMessage() throws Exception {
        String payload =
                "{\"object\":\"whatsapp_business_account\"," +
                "\"entry\":[{\"id\":\"WBID\",\"changes\":[{\"value\":{" +
                "\"messaging_product\":\"whatsapp\",\"contacts\":[{\"profile\":{\"name\":\"Max\"},\"wa_id\":\"4915\"}]," +
                "\"messages\":[{\"from\":\"4915\",\"id\":\"wamid.IN1\",\"timestamp\":\"1700000000\"," +
                "\"text\":{\"body\":\"Hallo von WhatsApp\"}}]}}]}]}";

        Channel ch = channel("http://localhost:1");
        WhatsAppChannelAdapter adapter = new WhatsAppChannelAdapter();

        ChannelMessage msg = adapter.inboundFromWebhook(ch, payload);

        assertThat(msg).isNotNull();
        assertThat(msg.content()).isEqualTo("Hallo von WhatsApp");
        assertThat(msg.senderId()).isEqualTo("4915");
        assertThat(msg.senderName()).isEqualTo("Max");
        assertThat(msg.threadId()).isEqualTo("4915");
        assertThat(msg.externalId()).isEqualTo("wamid.IN1");
        assertThat(msg.direction()).isEqualTo(MessageDirection.INBOUND);
    }

    @Test
    void inboundFromWebhookIgnoresNonMessagePayloads() throws Exception {
        Channel ch = channel("http://localhost:1");
        WhatsAppChannelAdapter adapter = new WhatsAppChannelAdapter();

        String statusOnly =
                "{\"entry\":[{\"changes\":[{\"value\":{\"statuses\":[{\"id\":\"s1\"}]}}]}]}";
        String mediaOnly =
                "{\"entry\":[{\"changes\":[{\"value\":{\"messages\":[{\"from\":\"4915\",\"id\":\"m1\"," +
                "\"image\":{\"id\":\"img1\"}}]}}]}]}";

        assertThat(adapter.inboundFromWebhook(ch, statusOnly)).isNull();
        assertThat(adapter.inboundFromWebhook(ch, mediaOnly)).isNull();
        assertThat(adapter.inboundFromWebhook(ch, "kaputt")).isNull();
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
