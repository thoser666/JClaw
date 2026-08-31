package biz.brumm.infrastructure.adapter.out.channel;

import biz.brumm.domain.model.Channel;
import biz.brumm.domain.model.ChannelMessage;
import biz.brumm.domain.model.ChannelType;
import biz.brumm.domain.model.MessageDirection;
import biz.brumm.domain.port.out.ChannelAdapter;
import biz.brumm.infrastructure.adapter.out.channel.DiscordChannelAdapter.SessionHandle;
import biz.brumm.infrastructure.adapter.out.channel.DiscordChannelAdapter.WebSocketConnector;
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

class DiscordChannelAdapterTest {

    private Channel channel(String base) {
        return new Channel("d1", "Discord Test", ChannelType.DISCORD, true,
                Map.of("token", "discord-token", "baseUrl", base),
                Instant.now(), Instant.now());
    }

    @Test
    void channelTypeIsDiscord() {
        assertThat(new DiscordChannelAdapter().channelType()).isEqualTo(ChannelType.DISCORD);
    }

    @Test
    void availabilityRequiresEnabledAndToken() {
        DiscordChannelAdapter adapter = new DiscordChannelAdapter();
        Channel ok = new Channel("c", "n", ChannelType.DISCORD, true, Map.of("token", "t"),
                Instant.now(), Instant.now());
        Channel noToken = new Channel("c", "n", ChannelType.DISCORD, true, Map.of(),
                Instant.now(), Instant.now());
        Channel disabled = new Channel("c", "n", ChannelType.DISCORD, false, Map.of("token", "t"),
                Instant.now(), Instant.now());

        assertThat(adapter.isAvailable(ok)).isTrue();
        assertThat(adapter.isAvailable(noToken)).isFalse();
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
                    "{\"id\":\"123456789\",\"channel_id\":\"100200\",\"content\":\"Hallo Discord\"}");
        }, base -> {
            Channel ch = channel(base);
            DiscordChannelAdapter adapter = new DiscordChannelAdapter();
            ChannelMessage outbound = ChannelMessage.outbound(ch.id(), "Hallo Discord", "100200", null);

            ChannelMessage sent = adapter.send(ch, outbound);

            assertThat(sent.externalId()).isEqualTo("123456789");
            assertThat(sent.direction()).isEqualTo(MessageDirection.OUTBOUND);
            assertThat(auth.get()).isEqualTo("Bot discord-token");
            assertThat(path.get()).isEqualTo("/channels/100200/messages");
            assertThat(body.get()).contains("\"content\":\"Hallo Discord\"");
        });
    }

    @Test
    void sendWithoutChannelThrows() throws Exception {
        Channel ch = channel("http://localhost:1");
        DiscordChannelAdapter adapter = new DiscordChannelAdapter();
        ChannelMessage outbound = ChannelMessage.outbound(ch.id(), "Hi", null, null);

        assertThatThrownBy(() -> adapter.send(ch, outbound))
                .isInstanceOf(ChannelAdapter.ChannelException.class)
                .hasMessageContaining("Discord-channel");
    }

    @Test
    void sendMissingTokenThrows() throws Exception {
        Channel ch = new Channel("d1", "x", ChannelType.DISCORD, true,
                Map.of("baseUrl", "http://localhost:1"), Instant.now(), Instant.now());
        DiscordChannelAdapter adapter = new DiscordChannelAdapter();
        ChannelMessage outbound = ChannelMessage.outbound(ch.id(), "Hi", "100", null);

        assertThatThrownBy(() -> adapter.send(ch, outbound))
                .isInstanceOf(ChannelAdapter.ChannelException.class)
                .hasMessageContaining("Token");
    }

    @Test
    void sendReportsDiscordError() throws Exception {
        withServer(exchange -> respond(exchange, 400,
                "{\"message\":\"401: Unauthorized\",\"code\":0}"),
                base -> {
                    Channel ch = channel(base);
                    DiscordChannelAdapter adapter = new DiscordChannelAdapter();
                    ChannelMessage outbound = ChannelMessage.outbound(ch.id(), "Hi", "100", null);

                    assertThatThrownBy(() -> adapter.send(ch, outbound))
                            .isInstanceOf(ChannelAdapter.ChannelException.class)
                            .hasMessageContaining("HTTP 400");
                });
    }

    @Test
    void openGatewayConnectionReturnsUrl() throws Exception {
        withServer(exchange -> respond(exchange, 200,
                "{\"url\":\"wss://gateway.discord.gg\"}"),
                base -> {
                    Channel ch = channel(base);
                    DiscordChannelAdapter adapter = new DiscordChannelAdapter();
                    assertThat(adapter.openGatewayConnection(ch))
                            .isEqualTo("wss://gateway.discord.gg");
                });
    }

    @Test
    void openGatewayConnectionFailsOnNoUrl() throws Exception {
        withServer(exchange -> respond(exchange, 200, "{}"),
                base -> {
                    Channel ch = channel(base);
                    DiscordChannelAdapter adapter = new DiscordChannelAdapter();
                    assertThatThrownBy(() -> adapter.openGatewayConnection(ch))
                            .isInstanceOf(ChannelAdapter.ChannelException.class)
                            .hasMessageContaining("Gateway-URL");
                });
    }

    @Test
    void dispatchesMessageCreateAndSendsIdentify() throws Exception {
        String hello = "{\"op\":10,\"d\":{\"heartbeat_interval\":41250}}";
        String create =
                "{\"op\":0,\"t\":\"MESSAGE_CREATE\"," +
                "\"d\":{\"id\":\"msg-1\",\"channel_id\":\"100200\",\"type\":0,\"content\":\"Hallo von Discord\"," +
                "\"author\":{\"id\":\"U1\",\"username\":\"max\"}}}";

        withServer(exchange -> respond(exchange, 200, "{\"url\":\"wss://gateway.discord.gg\"}"),
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
                        new Thread(() -> {
                            handler.onMessage(hello);
                            handler.onMessage(create);
                        }).start();
                        return fakeHandle;
                    };
                    DiscordChannelAdapter adapter =
                            new DiscordChannelAdapter(HttpClient.newHttpClient(), new ObjectMapper(), connector);

                    adapter.startReceiving(ch, received::set);

                    long deadline = System.currentTimeMillis() + 3000;
                    while (received.get() == null && System.currentTimeMillis() < deadline) {
                        Thread.sleep(20);
                    }
                    adapter.stopReceiving(ch);

                    assertThat(received.get().content()).isEqualTo("Hallo von Discord");
                    assertThat(received.get().senderId()).isEqualTo("U1");
                    assertThat(received.get().senderName()).isEqualTo("max");
                    assertThat(received.get().threadId()).isEqualTo("100200");
                    assertThat(received.get().externalId()).isEqualTo("msg-1");
                    assertThat(received.get().direction()).isEqualTo(MessageDirection.INBOUND);
                    assertThat(sent.stream().anyMatch(m -> m.contains("\"op\":2"))).isTrue();
                    assertThat(sent.stream().anyMatch(m -> m.contains("\"intents\":4609"))).isTrue();
                });
    }

    @Test
    void heartbeatRequestIsAnswered() throws Exception {
        String hello = "{\"op\":10,\"d\":{\"heartbeat_interval\":41250}}";
        String heartbeat = "{\"op\":1,\"d\":null}";

        withServer(exchange -> respond(exchange, 200, "{\"url\":\"wss://gateway.discord.gg\"}"),
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
                        new Thread(() -> {
                            handler.onMessage(hello);
                            handler.onMessage(heartbeat);
                        }).start();
                        return fakeHandle;
                    };
                    DiscordChannelAdapter adapter =
                            new DiscordChannelAdapter(HttpClient.newHttpClient(), new ObjectMapper(), connector);

                    adapter.startReceiving(ch, received::set);

                    long deadline = System.currentTimeMillis() + 3000;
                    while (sent.stream().noneMatch(m -> m.contains("\"op\":1,\"d\":null"))
                            && System.currentTimeMillis() < deadline) {
                        Thread.sleep(20);
                    }
                    adapter.stopReceiving(ch);

                    assertThat(sent.stream().anyMatch(m -> m.equals("{\"op\":1,\"d\":null}"))).isTrue();
                });
    }

    @Test
    void ignorableFramesAreSkipped() throws Exception {
        String hello = "{\"op\":10,\"d\":{\"heartbeat_interval\":41250}}";

        withServer(exchange -> respond(exchange, 200, "{\"url\":\"wss://gateway.discord.gg\"}"),
                base -> {
                    Channel ch = channel(base);
                    AtomicReference<ChannelMessage> received = new AtomicReference<>();

                    WebSocketConnector connector = (url, handler) -> {
                        handler.onMessage(hello);
                        handler.onMessage("{\"op\":0,\"t\":\"READY\",\"d\":{}}");
                        handler.onMessage("{\"op\":0,\"t\":\"TYPING_START\",\"d\":{}}");
                        return new SessionHandle() {
                            @Override
                            public void send(String rawMessage) throws Exception {
                            }

                            @Override
                            public void close() throws Exception {
                            }
                        };
                    };
                    DiscordChannelAdapter adapter =
                            new DiscordChannelAdapter(HttpClient.newHttpClient(), new ObjectMapper(), connector);

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
