package biz.brumm.infrastructure.adapter.out.channel;

import biz.brumm.domain.model.Channel;
import biz.brumm.domain.model.ChannelMessage;
import biz.brumm.domain.model.ChannelType;
import biz.brumm.domain.model.MessageDirection;
import biz.brumm.domain.port.out.ChannelAdapter;
import biz.brumm.infrastructure.adapter.out.channel.SlackChannelAdapter.SessionHandle;
import biz.brumm.infrastructure.adapter.out.channel.SlackChannelAdapter.SocketMessageHandler;
import biz.brumm.infrastructure.adapter.out.channel.SlackChannelAdapter.WebSocketConnector;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SlackChannelAdapterTest {

    private Channel channel(String base) {
        return new Channel("s1", "Slack Test", ChannelType.SLACK, true,
                Map.of("token", "xoxb-test", "baseUrl", base),
                Instant.now(), Instant.now());
    }

    @Test
    void channelTypeIsSlack() {
        assertThat(new SlackChannelAdapter().channelType()).isEqualTo(ChannelType.SLACK);
    }

    @Test
    void availabilityRequiresEnabledAndToken() {
        SlackChannelAdapter adapter = new SlackChannelAdapter();
        Channel ok = new Channel("c", "n", ChannelType.SLACK, true, Map.of("token", "t"),
                Instant.now(), Instant.now());
        Channel noToken = new Channel("c", "n", ChannelType.SLACK, true, Map.of(),
                Instant.now(), Instant.now());
        Channel disabled = new Channel("c", "n", ChannelType.SLACK, false, Map.of("token", "t"),
                Instant.now(), Instant.now());

        assertThat(adapter.isAvailable(ok)).isTrue();
        assertThat(adapter.isAvailable(noToken)).isFalse();
        assertThat(adapter.isAvailable(disabled)).isFalse();
        assertThat(adapter.isAvailable(null)).isFalse();
    }

    @Test
    void sendPostsMessageAndCapturesTs() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        withServer(exchange -> {
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(readBody(exchange));
            respond(exchange, 200, "{\"ok\":true,\"ts\":\"1700000000.000100\",\"channel\":\"C123\"}");
        }, base -> {
            Channel ch = channel(base);
            SlackChannelAdapter adapter = new SlackChannelAdapter();
            ChannelMessage outbound = ChannelMessage.outbound(ch.id(), "Hallo Slack", "C123", null);

            ChannelMessage sent = adapter.send(ch, outbound);

            assertThat(sent.externalId()).isEqualTo("1700000000.000100");
            assertThat(sent.direction()).isEqualTo(MessageDirection.OUTBOUND);
            assertThat(auth.get()).isEqualTo("Bearer xoxb-test");
            assertThat(body.get()).contains("\"channel\":\"C123\"").contains("\"text\":\"Hallo Slack\"");
        });
    }

    @Test
    void sendWithoutChannelThrows() throws Exception {
        Channel ch = channel("http://localhost:1");
        SlackChannelAdapter adapter = new SlackChannelAdapter();
        ChannelMessage outbound = ChannelMessage.outbound(ch.id(), "Hi", null, null);

        assertThatThrownBy(() -> adapter.send(ch, outbound))
                .isInstanceOf(ChannelAdapter.ChannelException.class)
                .hasMessageContaining("Slack-channel");
    }

    @Test
    void sendMissingTokenThrows() throws Exception {
        Channel ch = new Channel("s1", "x", ChannelType.SLACK, true,
                Map.of("baseUrl", "http://localhost:1"), Instant.now(), Instant.now());
        SlackChannelAdapter adapter = new SlackChannelAdapter();
        ChannelMessage outbound = ChannelMessage.outbound(ch.id(), "Hi", "C1", null);

        assertThatThrownBy(() -> adapter.send(ch, outbound))
                .isInstanceOf(ChannelAdapter.ChannelException.class)
                .hasMessageContaining("Token");
    }

    @Test
    void sendReportsSlackError() throws Exception {
        withServer(exchange -> respond(exchange, 200, "{\"ok\":false,\"error\":\"invalid_auth\"}"),
                base -> {
                    Channel ch = channel(base);
                    SlackChannelAdapter adapter = new SlackChannelAdapter();
                    ChannelMessage outbound = ChannelMessage.outbound(ch.id(), "Hi", "C1", null);

                    assertThatThrownBy(() -> adapter.send(ch, outbound))
                            .isInstanceOf(ChannelAdapter.ChannelException.class)
                            .hasMessageContaining("invalid_auth");
                });
    }

    @Test
    void openSocketConnectionReturnsUrl() throws Exception {
        withServer(exchange -> respond(exchange, 200,
                "{\"ok\":true,\"url\":\"wss://wss-primary.slack.com/link/xyz\"}"),
                base -> {
                    Channel ch = channel(base);
                    SlackChannelAdapter adapter = new SlackChannelAdapter();
                    assertThat(adapter.openSocketConnection(ch, "xoxb-test"))
                            .isEqualTo("wss://wss-primary.slack.com/link/xyz");
                });
    }

    @Test
    void openSocketConnectionFailsOnNoUrl() throws Exception {
        withServer(exchange -> respond(exchange, 200, "{\"ok\":true}"),
                base -> {
                    Channel ch = channel(base);
                    SlackChannelAdapter adapter = new SlackChannelAdapter();
                    assertThatThrownBy(() -> adapter.openSocketConnection(ch, "xoxb-test"))
                            .isInstanceOf(ChannelAdapter.ChannelException.class)
                            .hasMessageContaining("Socket-URL");
                });
    }

    @Test
    void handleEventEnvelopeDispatchesInboundMessageAndAcks() throws Exception {
        String envelope =
                "{\"envelope_id\":\"env-1\",\"type\":\"events_api\"," +
                "\"payload\":{\"type\":\"event_callback\",\"event_id\":\"evt-1\"," +
                "\"event\":{\"type\":\"message\",\"user\":\"U123\",\"channel\":\"C456\"," +
                "\"text\":\"Hallo von Slack\",\"ts\":\"1700000000.000100\"}}}";

        withServer(exchange -> respond(exchange, 200,
                "{\"ok\":true,\"url\":\"wss://wss-primary.slack.com/link/xyz\"}"),
                base -> {
                    Channel ch = channel(base);
                    AtomicReference<ChannelMessage> received = new AtomicReference<>();
                    List<String> sent = new ArrayList<>();

                    WebSocketConnector connector = (url, handler) -> {
                        SessionHandle fakeHandle = new SessionHandle() {
                            @Override
                            public void send(String rawMessage) throws Exception {
                                sent.add(rawMessage);
                            }

                            @Override
                            public void close() throws Exception {
                            }
                        };
                        new Thread(() -> handler.onMessage(envelope)).start();
                        return fakeHandle;
                    };
                    SlackChannelAdapter adapter =
                            new SlackChannelAdapter(HttpClient.newHttpClient(), new ObjectMapper(), connector);

                    adapter.startReceiving(ch, received::set);

                    long deadline = System.currentTimeMillis() + 3000;
                    while ((received.get() == null || sent.isEmpty())
                            && System.currentTimeMillis() < deadline) {
                        Thread.sleep(20);
                    }
                    adapter.stopReceiving(ch);

                    assertThat(received.get()).isNotNull();
                    assertThat(received.get().content()).isEqualTo("Hallo von Slack");
                    assertThat(received.get().senderId()).isEqualTo("U123");
                    assertThat(received.get().threadId()).isEqualTo("C456");
                    assertThat(received.get().externalId()).isEqualTo("evt-1");
                    assertThat(received.get().direction()).isEqualTo(MessageDirection.INBOUND);
                    assertThat(sent).containsExactly("{\"envelope_id\":\"env-1\"}");
                });
    }

    @Test
    void ignorableEnvelopeTypesAreSkipped() throws Exception {
        withServer(exchange -> respond(exchange, 200,
                "{\"ok\":true,\"url\":\"wss://wss-primary.slack.com/link/xyz\"}"),
                base -> {
                    Channel ch = channel(base);
                    AtomicReference<ChannelMessage> received = new AtomicReference<>();

                    WebSocketConnector connector = (url, handler) -> {
                        handler.onMessage("{\"type\":\"hello\"}");
                        handler.onMessage("{\"type\":\"disconnect\"}");
                        return new SessionHandle() {
                            @Override
                            public void send(String rawMessage) {
                            }

                            @Override
                            public void close() {
                            }
                        };
                    };
                    SlackChannelAdapter adapter =
                            new SlackChannelAdapter(HttpClient.newHttpClient(), new ObjectMapper(), connector);

                    adapter.startReceiving(ch, received::set);
                    adapter.stopReceiving(ch);

                    assertThat(received.get()).isNull();
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
