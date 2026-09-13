package biz.brumm.infrastructure.adapter.out.channel;

import biz.brumm.domain.model.Channel;
import biz.brumm.domain.model.ChannelMessage;
import biz.brumm.domain.model.ChannelType;
import biz.brumm.domain.model.IngressCursor;
import biz.brumm.domain.model.MessageDirection;
import biz.brumm.domain.port.out.ChannelAdapter;
import biz.brumm.domain.port.out.IngressCursorStore;
import biz.brumm.domain.service.ChannelIngressMonitor;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClickClackChannelAdapterTest {

    private Channel channel(String base, String workspace, String defaultTo) {
        Map<String, Object> config = new HashMap<>();
        config.put("token", "test-token");
        config.put("baseUrl", base);
        config.put("workspace", workspace == null ? "default" : workspace);
        if (defaultTo != null) {
            config.put("defaultTo", defaultTo);
        }
        config.put("pollIntervalSeconds", 1);
        return new Channel("c1", "ClickClack Test", ChannelType.CLICKCLACK, true,
                config, Instant.now(), Instant.now());
    }

    private ClickClackChannelAdapter plainAdapter() {
        return new ClickClackChannelAdapter(HttpClient.newHttpClient(), new ObjectMapper());
    }

    private ClickClackChannelAdapter adapterWithMonitor(IngressCursorStore store) {
        return new ClickClackChannelAdapter(HttpClient.newHttpClient(), new ObjectMapper(),
                new ChannelIngressMonitor(store));
    }

    @Test
    void channelTypeIsClickClack() {
        assertThat(plainAdapter().channelType()).isEqualTo(ChannelType.CLICKCLACK);
    }

    @Test
    void availabilityRequiresEnabledTokenWorkspaceAndBaseUrl() {
        ClickClackChannelAdapter adapter = plainAdapter();
        Channel ok = channel("http://localhost:1", "default", null);
        Channel noToken = new Channel("c", "n", ChannelType.CLICKCLACK, true,
                Map.of("workspace", "default", "baseUrl", "http://localhost:1"),
                Instant.now(), Instant.now());
        Channel noWorkspace = new Channel("c", "n", ChannelType.CLICKCLACK, true,
                Map.of("token", "abc", "baseUrl", "http://localhost:1"), Instant.now(), Instant.now());
        Channel noBase = new Channel("c", "n", ChannelType.CLICKCLACK, true,
                Map.of("token", "abc", "workspace", "default"), Instant.now(), Instant.now());
        Channel disabled = new Channel("c", "n", ChannelType.CLICKCLACK, false,
                Map.of("token", "abc", "workspace", "default", "baseUrl", "http://localhost:1"),
                Instant.now(), Instant.now());

        assertThat(adapter.isAvailable(ok)).isTrue();
        assertThat(adapter.isAvailable(noToken)).isFalse();
        assertThat(adapter.isAvailable(noWorkspace)).isFalse();
        assertThat(adapter.isAvailable(noBase)).isFalse();
        assertThat(adapter.isAvailable(disabled)).isFalse();
        assertThat(adapter.isAvailable(null)).isFalse();
    }

    @Test
    void sendToChannelPostsMessageAndCapturesExternalId() throws Exception {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        withServer(exchange -> {
            method.set(exchange.getRequestMethod());
            path.set(exchange.getRequestURI().getRawPath());
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(readBody(exchange));
            respond(exchange, 200, "{\"message\":{\"id\":\"msg_42\"},\"event\":{\"id\":\"evt_1\"}}");
        }, base -> {
            Channel ch = channel(base, null, null);
            ClickClackChannelAdapter adapter = plainAdapter();

            ChannelMessage outbound = ChannelMessage.outbound(ch.id(), "Hallo", "channel:chn_abc", null);
            ChannelMessage sent = adapter.send(ch, outbound);

            assertThat(sent.externalId()).isEqualTo("msg_42");
            assertThat(sent.threadId()).isEqualTo("channel:chn_abc");
            assertThat(sent.direction()).isEqualTo(MessageDirection.OUTBOUND);
            assertThat(method.get()).isEqualTo("POST");
            assertThat(path.get()).isEqualTo("/api/channels/chn_abc/messages");
            assertThat(auth.get()).isEqualTo("Bearer test-token");
            assertThat(body.get()).contains("\"body\":\"Hallo\"");
        });
    }

    @Test
    void sendBareChannelNameResolvesChannelId() throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        withServer(exchange -> {
            String p = exchange.getRequestURI().getRawPath();
            if (exchange.getRequestMethod().equals("POST")) {
                path.set(p);
                respond(exchange, 200, "{\"message\":{\"id\":\"msg_5\"},\"event\":{}}");
            } else if (p.equals("/api/workspaces")) {
                respond(exchange, 200, "[{\"id\":\"wsp_1\",\"name\":\"default\",\"slug\":\"default\"}]");
            } else if (p.equals("/api/workspaces/wsp_1/channels")) {
                respond(exchange, 200,
                        "[{\"id\":\"chn_g1\",\"name\":\"general\",\"display_title\":\"General\"}]");
            } else {
                respond(exchange, 404, "{\"error\":\"not found\"}");
            }
        }, base -> {
            Channel ch = channel(base, "default", null);
            ClickClackChannelAdapter adapter = plainAdapter();

            adapter.send(ch, ChannelMessage.outbound(ch.id(), "Hallo", "general", null));

            assertThat(path.get()).isEqualTo("/api/channels/chn_g1/messages");
        });
    }

    @Test
    void sendToExistingDmReusesConversation() throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<Boolean> dmCreated = new AtomicReference<>(false);
        withServer(exchange -> {
            String p = exchange.getRequestURI().getRawPath();
            if (p.equals("/api/workspaces")) {
                respond(exchange, 200, "[{\"id\":\"wsp_1\",\"name\":\"default\"}]");
            } else if (p.equals("/api/dms") && exchange.getRequestMethod().equals("GET")) {
                respond(exchange, 200,
                        "[{\"id\":\"dm_5\",\"can_send\":true,\"members\":[{\"id\":\"usr_7\",\"name\":\"Alice\"}]}]");
            } else if (p.equals("/api/dms") && exchange.getRequestMethod().equals("POST")) {
                dmCreated.set(true);
                respond(exchange, 200, "{\"id\":\"dm_new\"}");
            } else if (p.equals("/api/dms/dm_5/messages")) {
                path.set(p);
                respond(exchange, 200, "{\"message\":{\"id\":\"msg_dm\"},\"event\":{}}");
            } else {
                respond(exchange, 404, "{\"error\":\"not found\"}");
            }
        }, base -> {
            Channel ch = channel(base, "default", null);
            ClickClackChannelAdapter adapter = plainAdapter();

            ChannelMessage sent = adapter.send(ch,
                    ChannelMessage.outbound(ch.id(), "Hallo", "dm:usr_7", null));

            assertThat(path.get()).isEqualTo("/api/dms/dm_5/messages");
            assertThat(dmCreated.get()).isFalse();
            assertThat(sent.externalId()).isEqualTo("msg_dm");
        });
    }

    @Test
    void sendToUnknownUserCreatesDmConversation() throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> createBody = new AtomicReference<>();
        withServer(exchange -> {
            String p = exchange.getRequestURI().getRawPath();
            if (p.equals("/api/workspaces")) {
                respond(exchange, 200, "[{\"id\":\"wsp_1\",\"name\":\"default\"}]");
            } else if (p.equals("/api/dms") && exchange.getRequestMethod().equals("GET")) {
                respond(exchange, 200, "[]");
            } else if (p.equals("/api/dms") && exchange.getRequestMethod().equals("POST")) {
                createBody.set(readBody(exchange));
                respond(exchange, 200, "{\"id\":\"dm_9\"}");
            } else if (p.equals("/api/dms/dm_9/messages")) {
                path.set(p);
                respond(exchange, 200, "{\"message\":{\"id\":\"msg_x\"},\"event\":{}}");
            } else {
                respond(exchange, 404, "{\"error\":\"not found\"}");
            }
        }, base -> {
            Channel ch = channel(base, "default", null);
            ClickClackChannelAdapter adapter = plainAdapter();

            adapter.send(ch, ChannelMessage.outbound(ch.id(), "Hallo", "dm:usr_9", null));

            assertThat(createBody.get()).contains("\"workspace_id\":\"wsp_1\"");
            assertThat(createBody.get()).contains("\"usr_9\"");
            assertThat(path.get()).isEqualTo("/api/dms/dm_9/messages");
        });
    }

    @Test
    void sendThreadReplyPostsToThread() throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        withServer(exchange -> {
            path.set(exchange.getRequestURI().getRawPath());
            respond(exchange, 200, "{\"message\":{\"id\":\"msg_r\"},\"event\":{}}");
        }, base -> {
            Channel ch = channel(base, null, null);
            ClickClackChannelAdapter adapter = plainAdapter();

            adapter.send(ch, ChannelMessage.outbound(ch.id(), "Antwort", "thread:msg_3", null));

            assertThat(path.get()).isEqualTo("/api/messages/msg_3/thread/replies");
        });
    }

    @Test
    void sendWithoutTargetUsesDefaultTo() throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        withServer(exchange -> {
            path.set(exchange.getRequestURI().getRawPath());
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, "{\"message\":{\"id\":\"msg_d\"},\"event\":{}}");
        }, base -> {
            Channel ch = channel(base, null, "channel:chn_9");
            ClickClackChannelAdapter adapter = plainAdapter();

            ChannelMessage sent = adapter.send(ch, ChannelMessage.outbound(ch.id(), "Hallo", null, null));

            assertThat(path.get()).isEqualTo("/api/channels/chn_9/messages");
            assertThat(auth.get()).isEqualTo("Bearer test-token");
            assertThat(sent.threadId()).isEqualTo("channel:chn_9");
        });
    }

    @Test
    void sendWithoutTargetOrDefaultThrows() throws Exception {
        Channel ch = channel("http://localhost:1", null, null);
        ClickClackChannelAdapter adapter = plainAdapter();

        assertThatThrownBy(() -> adapter.send(ch, ChannelMessage.outbound(ch.id(), "Hallo", null, null)))
                .isInstanceOf(ChannelAdapter.ChannelException.class)
                .hasMessageContaining("Ziel");
    }

    @Test
    void sendMissingTokenThrows() throws Exception {
        Channel ch = new Channel("c1", "n", ChannelType.CLICKCLACK, true,
                Map.of("workspace", "default", "baseUrl", "http://localhost:1"),
                Instant.now(), Instant.now());
        ClickClackChannelAdapter adapter = plainAdapter();

        assertThatThrownBy(() -> adapter.send(ch, ChannelMessage.outbound(ch.id(), "Hallo", "channel:chn_1", null)))
                .isInstanceOf(ChannelAdapter.ChannelException.class)
                .hasMessageContaining("Token");
    }

    @Test
    void sendReportsHttpError() throws Exception {
        withServer(exchange -> respond(exchange, 403, "{\"error\":\"forbidden\"}"), base -> {
            Channel ch = channel(base, null, null);
            ClickClackChannelAdapter adapter = plainAdapter();

            assertThatThrownBy(() -> adapter.send(ch,
                    ChannelMessage.outbound(ch.id(), "Hallo", "channel:chn_abc", null)))
                    .isInstanceOf(ChannelAdapter.ChannelException.class)
                    .hasMessageContaining("HTTP 403");
        });
    }

    // --- Empfang: Event-Polling über den Ingress-Monitor ---

    @Test
    void pollDeliversMessageEventsInOrder() throws Exception {
        withServer(flowHandler(
                "[{\"id\":\"wsp_1\",\"name\":\"default\"}]",
                "{\"id\":\"usr_bot\",\"name\":\"ClickClack-Bot\",\"handle\":\"clackbot\"}",
                () -> "{\"events\":[{\"id\":\"evt_skip\",\"cursor\":\"c0\",\"type\":\"channel.created\"}],"
                        + "\"tail_cursor\":\"c0\",\"include_tail\":true}",
                () -> "{\"events\":["
                        + "{\"id\":\"evt_1\",\"cursor\":\"c1\",\"type\":\"message.created\","
                        + "\"channel_id\":\"chn_9\",\"seq\":1,"
                        + "\"payload\":{\"message_id\":\"msg_1\",\"author_id\":\"usr_7\"}},"
                        + "{\"id\":\"evt_2\",\"cursor\":\"c2\",\"type\":\"thread.reply_created\","
                        + "\"channel_id\":\"chn_9\",\"seq\":2,"
                        + "\"payload\":{\"message_id\":\"msg_2\",\"author_id\":\"usr_8\"}}],"
                        + "\"tail_cursor\":\"c2\",\"include_tail\":true}",
                messageId -> messageId.endsWith("1")
                        ? "{\"id\":\"msg_1\",\"body\":\"Erste\",\"author\":{\"id\":\"usr_7\",\"name\":\"Alice\",\"handle\":\"alice\"},"
                                + "\"channel_id\":\"chn_9\",\"parent_message_id\":null}"
                        : "{\"id\":\"msg_2\",\"body\":\"Antwort\",\"author\":{\"id\":\"usr_8\",\"name\":\"Bob\",\"handle\":\"bob\"},"
                                + "\"channel_id\":\"chn_9\",\"parent_message_id\":\"msg_1\"}"), base -> {
            Channel ch = channel(base, "default", null);
            ClickClackChannelAdapter adapter = adapterWithMonitor(new MemStore());

            List<ChannelMessage> bootstrap = adapter.pollInbound(ch);
            List<ChannelMessage> messages = adapter.pollInbound(ch);

            assertThat(bootstrap).isEmpty();
            assertThat(messages).hasSize(2);
            ChannelMessage first = messages.get(0);
            assertThat(first.content()).isEqualTo("Erste");
            assertThat(first.senderId()).isEqualTo("usr_7");
            assertThat(first.senderName()).isEqualTo("Alice");
            assertThat(first.threadId()).isEqualTo("channel:chn_9");
            assertThat(first.externalId()).isEqualTo("evt_1");
            assertThat(first.direction()).isEqualTo(MessageDirection.INBOUND);
            assertThat(first.channelId()).isEqualTo(ch.id());
            ChannelMessage second = messages.get(1);
            assertThat(second.content()).isEqualTo("Antwort");
            assertThat(second.threadId()).isEqualTo("thread:msg_1");
            assertThat(second.externalId()).isEqualTo("evt_2");
        });
    }

    @Test
    void pollSkipsSelfMessages() throws Exception {
        List<String> messageFetches = new CopyOnWriteArrayList<>();
        withServer(flowHandler(
                "[{\"id\":\"wsp_1\",\"name\":\"default\"}]",
                "{\"id\":\"usr_bot\",\"name\":\"ClickClack-Bot\",\"handle\":\"clackbot\"}",
                () -> "{\"events\":[],\"tail_cursor\":\"c0\",\"include_tail\":true}",
                () -> "{\"events\":["
                        + "{\"id\":\"evt_self\",\"cursor\":\"c1\",\"type\":\"message.created\","
                        + "\"channel_id\":\"chn_9\",\"payload\":{\"message_id\":\"msg_self\",\"author_id\":\"usr_bot\"}}],"
                        + "\"tail_cursor\":\"c1\",\"include_tail\":true}",
                messageId -> {
                    messageFetches.add(messageId);
                    return null;
                }), base -> {
            Channel ch = channel(base, "default", null);
            ClickClackChannelAdapter adapter = adapterWithMonitor(new MemStore());

            adapter.pollInbound(ch);
            List<ChannelMessage> messages = adapter.pollInbound(ch);

            assertThat(messages).isEmpty();
            assertThat(messageFetches).isEmpty();
        });
    }

    @Test
    void pollIgnoresNonMessageEvents() throws Exception {
        withServer(flowHandler(
                "[{\"id\":\"wsp_1\",\"name\":\"default\"}]",
                "{\"id\":\"usr_bot\"}",
                () -> "{\"events\":[],\"tail_cursor\":\"c0\",\"include_tail\":true}",
                () -> "{\"events\":["
                        + "{\"id\":\"evt_r\",\"cursor\":\"c1\",\"type\":\"channel.read\",\"channel_id\":\"chn_1\"},"
                        + "{\"id\":\"evt_d\",\"cursor\":\"c2\",\"type\":\"dm.read\",\"channel_id\":\"chn_1\"}],"
                        + "\"tail_cursor\":\"c2\",\"include_tail\":true}",
                messageId -> null), base -> {
            Channel ch = channel(base, "default", null);
            ClickClackChannelAdapter adapter = adapterWithMonitor(new MemStore());

            adapter.pollInbound(ch);
            List<ChannelMessage> messages = adapter.pollInbound(ch);

            assertThat(messages).isEmpty();
        });
    }

    @Test
    void pollRetriesEventWhenMessageFetchFails() throws Exception {
        AtomicBoolean messageAvailable = new AtomicBoolean(false);
        withServer(flowHandler(
                "[{\"id\":\"wsp_1\",\"name\":\"default\"}]",
                "{\"id\":\"usr_bot\"}",
                () -> "{\"events\":[],\"tail_cursor\":\"c0\",\"include_tail\":true}",
                () -> "{\"events\":["
                        + "{\"id\":\"evt_1\",\"cursor\":\"c1\",\"type\":\"message.created\","
                        + "\"channel_id\":\"chn_9\",\"payload\":{\"message_id\":\"msg_1\",\"author_id\":\"usr_7\"}}],"
                        + "\"tail_cursor\":\"c1\",\"include_tail\":true}",
                messageId -> messageAvailable.get()
                        ? "{\"id\":\"msg_1\",\"body\":\"Spaet\",\"author\":{\"id\":\"usr_7\",\"name\":\"Alice\"},"
                                + "\"channel_id\":\"chn_9\",\"parent_message_id\":null}"
                        : null), base -> {
            Channel ch = channel(base, "default", null);
            ClickClackChannelAdapter adapter = adapterWithMonitor(new MemStore());

            adapter.pollInbound(ch);
            // Zuerst schlägt der Nachrichtenabruf fehl (404) → Cursor bleibt stehen.
            List<ChannelMessage> failed = adapter.pollInbound(ch);
            assertThat(failed).isEmpty();

            // ... danach liefert der Server die Nachricht → Retry liefert sie.
            messageAvailable.set(true);
            List<ChannelMessage> retried = adapter.pollInbound(ch);
            assertThat(retried).extracting(ChannelMessage::content).containsExactly("Spaet");
        });
    }

    @Test
    void pollInboundDoesNotRedeliverAfterAdapterRestart() throws Exception {
        MemStore store = new MemStore();
        withServer(flowHandler(
                "[{\"id\":\"wsp_1\",\"name\":\"default\"}]",
                "{\"id\":\"usr_bot\"}",
                () -> "{\"events\":[],\"tail_cursor\":\"c0\",\"include_tail\":true}",
                () -> "{\"events\":["
                        + "{\"id\":\"evt_1\",\"cursor\":\"c1\",\"type\":\"message.created\","
                        + "\"channel_id\":\"chn_9\",\"payload\":{\"message_id\":\"msg_1\",\"author_id\":\"usr_7\"}}],"
                        + "\"tail_cursor\":\"c1\",\"include_tail\":true}",
                messageId -> "{\"id\":\"msg_1\",\"body\":\"Hallo\",\"author\":{\"id\":\"usr_7\",\"name\":\"Alice\"},"
                        + "\"channel_id\":\"chn_9\",\"parent_message_id\":null}"), base -> {
            Channel ch = channel(base, "default", null);

            ClickClackChannelAdapter first = adapterWithMonitor(store);
            first.pollInbound(ch);
            List<ChannelMessage> delivered = first.pollInbound(ch);
            assertThat(delivered).hasSize(1);

            // Adapter-Neustart mit frischer Instanz auf demselben dauerhaften Store.
            ClickClackChannelAdapter second = adapterWithMonitor(store);
            second.pollInbound(ch);
            List<ChannelMessage> redelivered = second.pollInbound(ch);

            assertThat(redelivered).isEmpty();
            assertThat(store.countItems(ch.id())).isEqualTo(1);
        });
    }

    @Test
    void startReceivingRunsLoopAndCallsHandler() throws Exception {
        AtomicReference<ChannelMessage> received = new AtomicReference<>();
        withServer(flowHandler(
                "[{\"id\":\"wsp_1\",\"name\":\"default\"}]",
                "{\"id\":\"usr_bot\",\"name\":\"ClickClack-Bot\",\"handle\":\"clackbot\"}",
                () -> "{\"events\":[],\"tail_cursor\":\"c0\",\"include_tail\":true}",
                () -> "{\"events\":["
                        + "{\"id\":\"evt_1\",\"cursor\":\"c1\",\"type\":\"message.created\","
                        + "\"channel_id\":\"chn_9\",\"payload\":{\"message_id\":\"msg_1\",\"author_id\":\"usr_7\"}}],"
                        + "\"tail_cursor\":\"c1\",\"include_tail\":true}",
                messageId -> "{\"id\":\"msg_1\",\"body\":\"Hallo!\",\"author\":{\"id\":\"usr_7\",\"name\":\"Alice\"},"
                        + "\"channel_id\":\"chn_9\",\"parent_message_id\":null}"), base -> {
            Channel ch = channel(base, "default", null);
            ClickClackChannelAdapter adapter = adapterWithMonitor(new MemStore());

            adapter.startReceiving(ch, received::set);

            long deadline = System.currentTimeMillis() + 4000;
            while (received.get() == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            adapter.stopReceiving(ch);

            assertThat(received.get()).isNotNull();
            assertThat(received.get().content()).isEqualTo("Hallo!");
            assertThat(received.get().senderId()).isEqualTo("usr_7");
        });
    }

    @Test
    void startReceivingKeepsLoopingOnError() throws Exception {
        withServer(exchange -> {
            String p = exchange.getRequestURI().getRawPath();
            if (p.equals("/api/workspaces")) {
                respond(exchange, 200, "[{\"id\":\"wsp_1\",\"name\":\"default\"}]");
            } else if (p.equals("/api/me")) {
                respond(exchange, 200, "{\"id\":\"usr_bot\"}");
            } else {
                respond(exchange, 500, "boom");
            }
        }, base -> {
            Channel ch = channel(base, "default", null);
            ClickClackChannelAdapter adapter = adapterWithMonitor(new MemStore());

            // Sollte nicht werfen, sondern im Hintergrund weiterschleifen (Error-Log).
            adapter.startReceiving(ch, m -> {
            });
            Thread.sleep(400);
            adapter.stopReceiving(ch);
        });
    }

    // --- Helfer ---

    private static Consumer<HttpExchange> flowHandler(
            String workspacesJson, String meJson,
            Supplier<String> bootstrapEvents, Supplier<String> followingEvents,
            Function<String, String> messageSupplier) {
        return exchange -> {
            String path = exchange.getRequestURI().getRawPath();
            String query = exchange.getRequestURI().getRawQuery() == null
                    ? "" : exchange.getRequestURI().getRawQuery();
            String response;
            int status = 200;
            if (path.equals("/api/workspaces")) {
                response = workspacesJson;
            } else if (path.equals("/api/me")) {
                response = meJson;
            } else if (path.equals("/api/realtime/events")) {
                response = query.contains("after_cursor=")
                        ? followingEvents.get() : bootstrapEvents.get();
            } else if (path.startsWith("/api/messages/")) {
                response = messageSupplier.apply(path.substring("/api/messages/".length()));
                if (response == null) {
                    status = 404;
                    response = "{\"error\":\"message not found\"}";
                }
            } else {
                status = 404;
                response = "{\"error\":\"not found\"}";
            }
            respond(exchange, status, response);
        };
    }

    private static final class MemStore implements IngressCursorStore {
        private final Map<String, IngressCursor> cursors = new ConcurrentHashMap<>();
        private final Set<String> admitted = ConcurrentHashMap.newKeySet();

        @Override
        public Optional<IngressCursor> loadCursor(String channelId) {
            return Optional.ofNullable(cursors.get(channelId));
        }

        @Override
        public void saveCursor(IngressCursor cursor) {
            cursors.put(cursor.channelId(), cursor);
        }

        @Override
        public boolean admitItem(String channelId, String itemId, Instant seenAt) {
            return admitted.add(channelId + "|" + itemId);
        }

        @Override
        public void pruneItemsOlderThan(String channelId, Instant olderThan) {
        }

        @Override
        public int countItems(String channelId) {
            return (int) admitted.stream().filter(s -> s.startsWith(channelId + "|")).count();
        }
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

    private static String readBody(HttpExchange exchange) {
        try {
            return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}