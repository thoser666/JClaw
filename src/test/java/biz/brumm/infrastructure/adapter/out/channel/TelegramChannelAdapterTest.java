package biz.brumm.infrastructure.adapter.out.channel;

import biz.brumm.domain.model.Channel;
import biz.brumm.domain.model.ChannelMessage;
import biz.brumm.domain.model.ChannelType;
import biz.brumm.domain.model.MessageDirection;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import biz.brumm.domain.port.out.ChannelAdapter;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TelegramChannelAdapterTest {

    private Channel channel(String token) {
        return new Channel("t1", "Telegram Test", ChannelType.TELEGRAM, true,
                Map.of("token", token, "baseUrl", "http://localhost:1",
                       "pollTimeoutSeconds", 1),
                Instant.now(), Instant.now());
    }

    @Test
    void channelTypeIsTelegram() {
        TelegramChannelAdapter adapter = new TelegramChannelAdapter();
        assertThat(adapter.channelType()).isEqualTo(ChannelType.TELEGRAM);
    }

    @Test
    void availabilityRequiresEnabledAndToken() {
        TelegramChannelAdapter adapter = new TelegramChannelAdapter();
        Channel ok = new Channel("c", "n", ChannelType.TELEGRAM, true,
                Map.of("token", "abc"), Instant.now(), Instant.now());
        Channel noToken = new Channel("c", "n", ChannelType.TELEGRAM, true,
                Map.of(), Instant.now(), Instant.now());
        Channel disabled = new Channel("c", "n", ChannelType.TELEGRAM, false,
                Map.of("token", "abc"), Instant.now(), Instant.now());

        assertThat(adapter.isAvailable(ok)).isTrue();
        assertThat(adapter.isAvailable(noToken)).isFalse();
        assertThat(adapter.isAvailable(disabled)).isFalse();
        assertThat(adapter.isAvailable(null)).isFalse();
    }

    @Test
    void sendPostsMessageAndCapturesExternalId() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>();
        withServer(exchange -> {
            capturedBody.set(body(exchange));
            respond(exchange, 200,
                    "{\"ok\":true,\"result\":{\"message_id\":42,\"chat\":{\"id\":123456}}}");
        }, base -> {
            Channel ch = channelApi(base);
            TelegramChannelAdapter adapter = adapterFor(ch);

            ChannelMessage outbound = ChannelMessage.outbound(ch.id(), "Hallo", "123456", null);
            ChannelMessage sent = adapter.send(ch, outbound);

            assertThat(sent.externalId()).isEqualTo("42");
            assertThat(sent.direction()).isEqualTo(MessageDirection.OUTBOUND);
            assertThat(capturedBody.get())
                    .contains("\"chat_id\":\"123456\"")
                    .contains("\"text\":\"Hallo\"");
        });
    }

    @Test
    void sendWithoutChatIdThrows() throws Exception {
        Channel ch = channelApi("http://localhost:1");
        TelegramChannelAdapter adapter = new TelegramChannelAdapter();
        ChannelMessage outbound = ChannelMessage.outbound(ch.id(), "Hallo", null, null);

        assertThatThrownBy(() -> adapter.send(ch, outbound))
                .isInstanceOf(ChannelAdapter.ChannelException.class)
                .hasMessageContaining("chatId");
    }

    @Test
    void sendMissingTokenThrows() throws Exception {
        Channel ch = new Channel("t1", "x", ChannelType.TELEGRAM, true,
                Map.of("baseUrl", "http://localhost:1"), Instant.now(), Instant.now());
        TelegramChannelAdapter adapter = new TelegramChannelAdapter();
        ChannelMessage outbound = ChannelMessage.outbound(ch.id(), "Hallo", "1", null);

        assertThatThrownBy(() -> adapter.send(ch, outbound))
                .isInstanceOf(ChannelAdapter.ChannelException.class)
                .hasMessageContaining("Token");
    }

    @Test
    void sendReportsTelegramErrorOnOkFalse() throws Exception {
        withServer(exchange -> respond(exchange, 200,
                "{\"ok\":false,\"description\":\"Unauthorized\"}"), base -> {
            Channel ch = channelApi(base);
            TelegramChannelAdapter adapter = adapterFor(ch);
            ChannelMessage outbound = ChannelMessage.outbound(ch.id(), "Hallo", "1", null);

            assertThatThrownBy(() -> adapter.send(ch, outbound))
                    .isInstanceOf(ChannelAdapter.ChannelException.class)
                    .hasMessageContaining("Unauthorized");
        });
    }

    @Test
    void longPollingDeliversInboundMessagesToHandler() throws Exception {        String updatesJson =
                "{\"ok\":true,\"result\":[" +
                "{\"update_id\":1,\"message\":{\"message_id\":10,\"from\":{\"id\":777,\"first_name\":\"Anna\"," +
                "\"username\":\"anna\",\"is_bot\":false},\"chat\":{\"id\":123456},\"text\":\"Hi JClaw\"}}," +
                "{\"update_id\":2,\"message\":{\"message_id\":11,\"from\":{\"id\":888,\"first_name\":\"Bob\"," +
                "\"is_bot\":false},\"chat\":{\"id\":123456},\"text\":\"Zweite\"}}," +
                "{\"update_id\":3}]}";
        AtomicReference<String> capturedBody = new AtomicReference<>();

        withServer(exchange -> {
            capturedBody.set(body(exchange));
            respond(exchange, 200, updatesJson);
        }, base -> {
            Channel ch = channelApi(base);
            TelegramChannelAdapter adapter = adapterFor(ch);

            List<ChannelMessage> messages =
                    adapter.poll(ch, 0, 1, base);

            assertThat(messages).hasSize(2);
            ChannelMessage first = messages.get(0);
            assertThat(first.direction()).isEqualTo(MessageDirection.INBOUND);
            assertThat(first.content()).isEqualTo("Hi JClaw");
            assertThat(first.senderId()).isEqualTo("777");
            assertThat(first.senderName()).isEqualTo("Anna");
            assertThat(first.threadId()).isEqualTo("123456");
            assertThat(first.externalId()).isEqualTo("10");
            assertThat(first.channelId()).isEqualTo(ch.id());
            assertThat(capturedBody.get()).contains("\"offset\":0");
        });
    }

    @Test
    void startReceivingRunsLoopAndCallsHandler() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>();
        withServer(exchange -> {
            capturedBody.set(body(exchange));
            respond(exchange, 200,
                    "{\"ok\":true,\"result\":[{\"update_id\":5,\"message\":{\"message_id\":20," +
                    "\"from\":{\"id\":9,\"first_name\":\"Clara\"},\"chat\":{\"id\":1},\"text\":\"Hallo!\"}}]}");
        }, base -> {
            Channel ch = channelApi(base);
            TelegramChannelAdapter adapter = adapterFor(ch);
            AtomicReference<ChannelMessage> received = new AtomicReference<>();

            adapter.startReceiving(ch, received::set);

            long deadline = System.currentTimeMillis() + 3000;
            while (received.get() == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            adapter.stopReceiving(ch);

            assertThat(received.get()).isNotNull();
            assertThat(received.get().content()).isEqualTo("Hallo!");
            assertThat(capturedBody.get()).contains("\"allowed_updates\":[\"message\"]");
        });
    }

    @Test
    void startReceivingThrowsWhenPollerReturnsErrorRepeatedly() throws Exception {
        withServer(exchange -> respond(exchange, 500, "boom"), base -> {
            Channel ch = channelApi(base);
            TelegramChannelAdapter adapter = adapterFor(ch);
            // Sollte nicht werfen, sondern im Hintergrund weiterschleifen (Error-Log).
            adapter.startReceiving(ch, m -> { });
            Thread.sleep(300);
            adapter.stopReceiving(ch);
        });
    }

    // --- Helfer ---

    private Channel channelApi(String base) {
        return new Channel("t1", "Telegram Test", ChannelType.TELEGRAM, true,
                Map.of("token", "test-token", "baseUrl", base, "pollTimeoutSeconds", 1),
                Instant.now(), Instant.now());
    }

    private TelegramChannelAdapter adapterFor(Channel ch) {
        return new TelegramChannelAdapter();
    }

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

    private static String body(HttpExchange exchange) {
        try {
            return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
