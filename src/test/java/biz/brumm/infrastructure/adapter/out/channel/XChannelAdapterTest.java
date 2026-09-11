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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class XChannelAdapterTest {

    private Channel channelApi(String base) {
        return new Channel("x1", "X Test", ChannelType.X, true,
                Map.of("token", "test-token", "baseUrl", base, "pollIntervalSeconds", 1),
                Instant.now(), Instant.now());
    }

    @Test
    void channelTypeIsX() {
        assertThat(new XChannelAdapter().channelType()).isEqualTo(ChannelType.X);
    }

    @Test
    void availabilityRequiresEnabledAndToken() {
        XChannelAdapter adapter = new XChannelAdapter();
        Channel ok = new Channel("c", "n", ChannelType.X, true,
                Map.of("token", "abc"), Instant.now(), Instant.now());
        Channel noToken = new Channel("c", "n", ChannelType.X, true,
                Map.of(), Instant.now(), Instant.now());
        Channel disabled = new Channel("c", "n", ChannelType.X, false,
                Map.of("token", "abc"), Instant.now(), Instant.now());

        assertThat(adapter.isAvailable(ok)).isTrue();
        assertThat(adapter.isAvailable(noToken)).isFalse();
        assertThat(adapter.isAvailable(disabled)).isFalse();
        assertThat(adapter.isAvailable(null)).isFalse();
    }

    @Test
    void sendPostsMessageAndCapturesExternalId() throws Exception {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        withServer(exchange -> {
            method.set(exchange.getRequestMethod());
            path.set(exchange.getRequestURI().getRawPath());
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(readBody(exchange));
            respond(exchange, 200, "{\"data\":{\"id\":\"dm_msg_123\",\"dm_conversation_id\":\"conv1\"}}");
        }, base -> {
            Channel ch = channelApi(base);
            XChannelAdapter adapter = new XChannelAdapter();

            ChannelMessage outbound = ChannelMessage.outbound(ch.id(), "Hallo", "123456", null);
            ChannelMessage sent = adapter.send(ch, outbound);

            assertThat(sent.externalId()).isEqualTo("dm_msg_123");
            assertThat(sent.threadId()).isEqualTo("123456");
            assertThat(sent.direction()).isEqualTo(MessageDirection.OUTBOUND);
            assertThat(method.get()).isEqualTo("POST");
            assertThat(path.get()).isEqualTo("/dm_conversations/with/123456/messages");
            assertThat(auth.get()).isEqualTo("Bearer test-token");
            assertThat(body.get()).contains("\"text\":\"Hallo\"");
        });
    }

    @Test
    void sendWithoutParticipantThrows() throws Exception {
        Channel ch = channelApi("http://localhost:1");
        XChannelAdapter adapter = new XChannelAdapter();
        ChannelMessage outbound = ChannelMessage.outbound(ch.id(), "Hallo", null, null);

        assertThatThrownBy(() -> adapter.send(ch, outbound))
                .isInstanceOf(ChannelAdapter.ChannelException.class)
                .hasMessageContaining("Teilnehmer");
    }

    @Test
    void sendMissingTokenThrows() throws Exception {
        Channel ch = new Channel("x1", "x", ChannelType.X, true,
                Map.of("baseUrl", "http://localhost:1"), Instant.now(), Instant.now());
        XChannelAdapter adapter = new XChannelAdapter();
        ChannelMessage outbound = ChannelMessage.outbound(ch.id(), "Hallo", "1", null);

        assertThatThrownBy(() -> adapter.send(ch, outbound))
                .isInstanceOf(ChannelAdapter.ChannelException.class)
                .hasMessageContaining("Token");
    }

    @Test
    void sendReportsHttpError() throws Exception {
        withServer(exchange -> respond(exchange, 401, "{\"errors\":[{\"message\":\"Unauthorized\"}]}"),
                base -> {
                    Channel ch = channelApi(base);
                    XChannelAdapter adapter = new XChannelAdapter();

                    assertThatThrownBy(() -> adapter.send(ch,
                            ChannelMessage.outbound(ch.id(), "Hallo", "1", null)))
                            .isInstanceOf(ChannelAdapter.ChannelException.class)
                            .hasMessageContaining("HTTP 401");
                });
    }

    @Test
    void pollDeliversInboundDmEventsOldestFirst() throws Exception {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> pathWithQuery = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        String dmEventsJson = "{\"data\":["
                + "{\"id\":\"e2\",\"event_type\":\"MessageCreate\",\"text\":\"Zweite\","
                + "\"sender_id\":\"888\",\"dm_conversation_id\":\"conv1\"},"
                + "{\"id\":\"e1\",\"event_type\":\"MessageCreate\",\"text\":\"Erste\","
                + "\"sender_id\":\"777\",\"dm_conversation_id\":\"conv2\"}],"
                + "\"meta\":{\"result_count\":2}}";
        withServer(exchange -> {
            method.set(exchange.getRequestMethod());
            pathWithQuery.set(exchange.getRequestURI().toString());
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, dmEventsJson);
        }, base -> {
            Channel ch = channelApi(base);
            XChannelAdapter adapter = new XChannelAdapter();

            List<ChannelMessage> messages = adapter.poll(ch, base);

            assertThat(method.get()).isEqualTo("GET");
            assertThat(pathWithQuery.get()).contains("/dm_events");
            assertThat(pathWithQuery.get()).contains("max_results=100");
            assertThat(auth.get()).isEqualTo("Bearer test-token");
            assertThat(messages).hasSize(2);
            ChannelMessage first = messages.get(0);
            assertThat(first.content()).isEqualTo("Erste");
            assertThat(first.senderId()).isEqualTo("777");
            assertThat(first.threadId()).isEqualTo("conv2");
            assertThat(first.externalId()).isEqualTo("e1");
            assertThat(first.direction()).isEqualTo(MessageDirection.INBOUND);
            assertThat(first.channelId()).isEqualTo(ch.id());
            assertThat(messages.get(1).content()).isEqualTo("Zweite");
        });
    }

    @Test
    void pollDeduplicatesAcrossCalls() throws Exception {
        String dmEventsJson = "{\"data\":[{\"id\":\"e1\",\"event_type\":\"MessageCreate\","
                + "\"text\":\"Erste\",\"sender_id\":\"777\",\"dm_conversation_id\":\"conv1\"}]}";
        withServer(exchange -> respond(exchange, 200, dmEventsJson), base -> {
            Channel ch = channelApi(base);
            XChannelAdapter adapter = new XChannelAdapter();

            List<ChannelMessage> first = adapter.poll(ch, base);
            List<ChannelMessage> second = adapter.poll(ch, base);

            assertThat(first).hasSize(1);
            assertThat(second).isEmpty();
        });
    }

    @Test
    void pollIgnoresNonMessageEventsAndBlankText() throws Exception {
        String dmEventsJson = "{\"data\":["
                + "{\"id\":\"x1\",\"event_type\":\"ParticipantsJoin\",\"text\":\"-\",\"sender_id\":\"1\",\"dm_conversation_id\":\"c\"},"
                + "{\"id\":\"x2\",\"event_type\":\"MessageCreate\",\"text\":\"   \",\"sender_id\":\"2\",\"dm_conversation_id\":\"c\"},"
                + "{\"id\":\"x3\",\"event_type\":\"MessageCreate\",\"text\":\"Hallo\",\"sender_id\":\"3\",\"dm_conversation_id\":\"c\"}]}";
        withServer(exchange -> respond(exchange, 200, dmEventsJson), base -> {
            Channel ch = channelApi(base);
            XChannelAdapter adapter = new XChannelAdapter();

            List<ChannelMessage> messages = adapter.poll(ch, base);

            assertThat(messages).hasSize(1);
            assertThat(messages.get(0).content()).isEqualTo("Hallo");
            assertThat(messages.get(0).threadId()).isEqualTo("c");
        });
    }

    @Test
    void startReceivingRunsLoopAndCallsHandler() throws Exception {
        String dmEventsJson = "{\"data\":[{\"id\":\"e1\",\"event_type\":\"MessageCreate\","
                + "\"text\":\"Hallo!\",\"sender_id\":\"9\",\"dm_conversation_id\":\"conv1\"}]}";
        withServer(exchange -> respond(exchange, 200, dmEventsJson), base -> {
            Channel ch = channelApi(base);
            XChannelAdapter adapter = new XChannelAdapter();
            AtomicReference<ChannelMessage> received = new AtomicReference<>();

            adapter.startReceiving(ch, received::set);

            long deadline = System.currentTimeMillis() + 3000;
            while (received.get() == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            adapter.stopReceiving(ch);

            assertThat(received.get()).isNotNull();
            assertThat(received.get().content()).isEqualTo("Hallo!");
            assertThat(received.get().senderId()).isEqualTo("9");
        });
    }

    @Test
    void startReceivingKeepsLoopingOnError() throws Exception {
        withServer(exchange -> respond(exchange, 500, "boom"), base -> {
            Channel ch = channelApi(base);
            XChannelAdapter adapter = new XChannelAdapter();
            // Sollte nicht werfen, sondern im Hintergrund weiterschleifen (Error-Log).
            adapter.startReceiving(ch, m -> { });
            Thread.sleep(300);
            adapter.stopReceiving(ch);
        });
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