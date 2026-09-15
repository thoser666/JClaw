package biz.brumm.infrastructure.adapter.out.channel;

import biz.brumm.domain.model.Channel;
import biz.brumm.domain.model.ChannelMessage;
import biz.brumm.domain.model.ChannelType;
import biz.brumm.domain.model.IngressCursor;
import biz.brumm.domain.port.out.IngressCursorStore;
import biz.brumm.domain.service.ChannelIngressMonitor;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests für {@link BuzzChannelAdapter} (Nostr/NIP-29) gegen einen Fake-WebSocket-
 * Connector; Empfangs-Lifecycle über die Ingress-Monitor-Abstraktion (P3-08).
 */
class BuzzChannelAdapterTest {

    private static final String PRIV = "00000000000000000000000000000000"
            + "00000000000000000000000000000003";
    private static final String PUB = "F9308A019258C31049344F85F89D5229B531C845836F99B08601F113BCE036F9";

    private final ObjectMapper mapper = new ObjectMapper();

    // --- Helfen ---

    private Channel channel(Map<String, Object> config) {
        return new Channel("ch-1", "Buzz", ChannelType.BUZZ, true,
                config, Instant.now(), Instant.now());
    }

    private Map<String, Object> config(Object... kv) {
        Map<String, Object> map = new ConcurrentHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }

    private Channel defaultChannel() {
        return channel(config("relay", "wss://relay.local", "privateKey", PRIV,
                "defaultTo", "room-1", "relayTimeoutSeconds", 1));
    }

    private static final class Sink {
        final List<String> sent = new CopyOnWriteArrayList<>();
        final AtomicBoolean closed = new AtomicBoolean(false);
    }

    private record Fake(Consumer<BuzzChannelAdapter.SocketMessageHandler> onConnect,
                        BiConsumer<String, BuzzChannelAdapter.SocketMessageHandler> onSend,
                        Sink sink) {

        BuzzChannelAdapter.WebSocketConnector connector() {
            return (url, handler) -> {
                if (onConnect() != null) {
                    onConnect().accept(handler);
                }
                return new BuzzChannelAdapter.SessionHandle() {
                    @Override
                    public void send(String rawMessage) throws Exception {
                        sink().sent.add(rawMessage);
                        if (onSend() != null) {
                            onSend().accept(rawMessage, handler);
                        }
                    }

                    @Override
                    public void close() {
                        sink().closed.set(true);
                    }
                };
            };
        }
    }

    private static BuzzChannelAdapter plainAdapter(BuzzChannelAdapter.WebSocketConnector connector) {
        return new BuzzChannelAdapter(new ObjectMapper(), connector);
    }

    private static BuzzChannelAdapter monitorAdapter(BuzzChannelAdapter.WebSocketConnector connector,
                                                     IngressCursorStore store) {
        return new BuzzChannelAdapter(new ObjectMapper(), connector,
                new ChannelIngressMonitor(store), store);
    }

    /** Antwortet auf jedes EVENT mit OK (id aus dem Frame); fliegt auf REQ. */
    private static void ackOk(BiConsumer<String, BuzzChannelAdapter.SocketMessageHandler> out, String raw,
                              BuzzChannelAdapter.SocketMessageHandler handler, ObjectMapper mapper) {
        if (raw.startsWith("[\"EVENT\"")) {
            try {
                JsonNode arr = mapper.readTree(raw);
                String id = arr.get(1).get("id").asText();
                handler.onMessage("[\"OK\",\"" + id + "\",true,\"\"]");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        } else if (out != null) {
            out.accept(raw, handler);
        }
    }

    /** Antwortet auf REQ mit den Event-Frames plus EOSE. */
    private static void respondToReq(String raw, BuzzChannelAdapter.SocketMessageHandler handler,
                                     String... eventFrames) {
        if (raw.startsWith("[\"REQ\"")) {
            for (String frame : eventFrames) {
                handler.onMessage(frame);
            }
            handler.onMessage("[\"EOSE\",\"sub\"]");
        }
    }

    private static String eventFrame(String id, String pubkey, long createdAt,
                                     String content, String extraTagsJson) {
        return "[\"EVENT\",{\"id\":\"" + id + "\",\"pubkey\":\"" + pubkey.toLowerCase()
                + "\",\"created_at\":" + createdAt + ",\"kind\":9,\"tags\":"
                + extraTagsJson + ",\"content\":\"" + content
                + "\",\"sig\":\"" + "0".repeat(128) + "\"}]";
    }

    private static String eventFrame(String id, String pubkey, long createdAt, String content) {
        return eventFrame(id, pubkey, createdAt, content, "[[\"h\",\"room-1\"]]");
    }

    private static JsonNode eventNode(String sentFrame) throws Exception {
        return new ObjectMapper().readTree(sentFrame).get(1);
    }

    private static List<String> framesStartingWith(List<String> sent, String prefix) {
        return sent.stream().filter(f -> f.startsWith(prefix)).toList();
    }

    // --- Senden ---

    @Test
    void sendPublishesToRoomAndReturnsExternalId() throws Exception {
        Fake fake = new Fake(null, (raw, h) -> ackOk(null, raw, h, mapper), new Sink());
        BuzzChannelAdapter adapter = plainAdapter(fake.connector());

        ChannelMessage result = adapter.send(defaultChannel(),
                ChannelMessage.outbound("ch-1", "Hallo Nostr!", "buzz:room:room-1", null));

        assertThat(fake.sink.sent).hasSize(1);
        JsonNode event = eventNode(fake.sink.sent.get(0));
        assertThat(event.get("kind").asInt()).isEqualTo(9);
        assertThat(event.get("pubkey").asText()).isEqualTo(PUB.toLowerCase());
        assertThat(event.get("content").asText()).isEqualTo("Hallo Nostr!");
        assertThat(event.get("tags").get(0).get(0).asText()).isEqualTo("h");
        assertThat(event.get("tags").get(0).get(1).asText()).isEqualTo("room-1");
        String id = event.get("id").asText();
        assertThat(id).hasSize(64);
        assertThat(event.get("sig").asText()).hasSize(128);
        // Signatur selbst verifizieren (Signatur über die Event-Id)
        assertThat(NostrCrypto.verify(NostrCrypto.unhex(id), NostrCrypto.unhex(PUB),
                NostrCrypto.unhex(event.get("sig").asText()))).isTrue();
        assertThat(result.externalId()).isEqualTo(id);
        assertThat(result.threadId()).isEqualTo("buzz:room:room-1");
        assertThat(result.direction()).isEqualTo(biz.brumm.domain.model.MessageDirection.OUTBOUND);
    }

    @Test
    void sendBareRoomUuidTargetWorks() throws Exception {
        Fake fake = new Fake(null, (raw, h) -> ackOk(null, raw, h, mapper), new Sink());
        BuzzChannelAdapter adapter = plainAdapter(fake.connector());
        adapter.send(defaultChannel(), ChannelMessage.outbound("ch-1", "Hi", "room-1", null));
        JsonNode event = eventNode(fake.sink.sent.get(0));
        assertThat(event.get("tags").get(0).get(1).asText()).isEqualTo("room-1");
    }

    @Test
    void sendRoomPrefixedTargetWorks() throws Exception {
        Fake fake = new Fake(null, (raw, h) -> ackOk(null, raw, h, mapper), new Sink());
        BuzzChannelAdapter adapter = plainAdapter(fake.connector());
        adapter.send(defaultChannel(), ChannelMessage.outbound("ch-1", "Hi", "room:room-1", null));
        JsonNode event = eventNode(fake.sink.sent.get(0));
        assertThat(event.get("tags").get(0).get(1).asText()).isEqualTo("room-1");
    }

    @Test
    void sendThreadReplyIncludesReplyTag() throws Exception {
        Fake fake = new Fake(null, (raw, h) -> ackOk(null, raw, h, mapper), new Sink());
        BuzzChannelAdapter adapter = plainAdapter(fake.connector());

        ChannelMessage result = adapter.send(defaultChannel(),
                ChannelMessage.outbound("ch-1", "Antwort", "buzz:thread:room-1:ROOTEVT", null));

        JsonNode event = eventNode(fake.sink.sent.get(0));
        JsonNode tags = event.get("tags");
        assertThat(tags.get(0).get(1).asText()).isEqualTo("room-1");
        assertThat(tags.get(1).get(0).asText()).isEqualTo("e");
        assertThat(tags.get(1).get(1).asText()).isEqualTo("ROOTEVT");
        assertThat(tags.get(1).get(3).asText()).isEqualTo("reply");
        assertThat(result.threadId()).isEqualTo("buzz:thread:room-1:ROOTEVT");
    }

    @Test
    void sendFallsBackToDefaultTo() throws Exception {
        Fake fake = new Fake(null, (raw, h) -> ackOk(null, raw, h, mapper), new Sink());
        BuzzChannelAdapter adapter = plainAdapter(fake.connector());
        adapter.send(defaultChannel(),
                ChannelMessage.outbound("ch-1", "Hi", null, null));
        JsonNode event = eventNode(fake.sink.sent.get(0));
        assertThat(event.get("tags").get(0).get(1).asText()).isEqualTo("room-1");
    }

    @Test
    void sendThrowsWhenNoTargetConfigured() {
        Fake fake = new Fake(null, null, new Sink());
        BuzzChannelAdapter adapter = plainAdapter(fake.connector());
        Channel noTarget = channel(config("relay", "wss://relay.local", "privateKey", PRIV));
        assertThatThrownBy(() -> adapter.send(noTarget,
                ChannelMessage.outbound("ch-1", "Hi", null, null)))
                .isInstanceOf(BuzzChannelAdapter.ChannelException.class)
                .hasMessageContaining("Kein Ziel");
    }

    @Test
    void sendThrowsOnBlankContent() {
        Fake fake = new Fake(null, null, new Sink());
        BuzzChannelAdapter adapter = plainAdapter(fake.connector());
        assertThatThrownBy(() -> adapter.send(defaultChannel(),
                ChannelMessage.outbound("ch-1", " ", "room-1", null)))
                .isInstanceOf(BuzzChannelAdapter.ChannelException.class)
                .hasMessageContaining("leer");
    }

    @Test
    void sendThrowsWithoutPrivateKey() {
        Fake fake = new Fake(null, null, new Sink());
        BuzzChannelAdapter adapter = plainAdapter(fake.connector());
        Channel noKey = channel(config("relay", "wss://relay.local", "defaultTo", "room-1"));
        assertThatThrownBy(() -> adapter.send(noKey,
                ChannelMessage.outbound("ch-1", "Hi", "room-1", null)))
                .isInstanceOf(BuzzChannelAdapter.ChannelException.class)
                .hasMessageContaining("privater Schluessel");
    }

    @Test
    void sendRejectedByRelayThrows() throws Exception {
        Fake fake = new Fake(null, (raw, h) -> {
            if (raw.startsWith("[\"EVENT\"")) {
                JsonNode arr = mapper.readTree(raw);
                String id = arr.get(1).get("id").asText();
                h.onMessage("[\"OK\",\"" + id + "\",false,\"blocked: not a member\"]");
            }
        }, new Sink());
        BuzzChannelAdapter adapter = plainAdapter(fake.connector());
        assertThatThrownBy(() -> adapter.send(defaultChannel(),
                ChannelMessage.outbound("ch-1", "Hi", "room-1", null)))
                .isInstanceOf(BuzzChannelAdapter.ChannelException.class)
                .hasMessageContaining("blocked: not a member");
    }

    @Test
    void sendThrowsOnRelayTimeout() {
        Fake fake = new Fake(null, null, new Sink());
        BuzzChannelAdapter adapter = plainAdapter(fake.connector());
        assertThatThrownBy(() -> adapter.send(defaultChannel(),
                ChannelMessage.outbound("ch-1", "Hi", "room-1", null)))
                .isInstanceOf(BuzzChannelAdapter.ChannelException.class)
                .hasMessageContaining("Timeout");
    }

    @Test
    void sendAnswersNip42AuthChallengeThenSucceeds() throws Exception {
        Fake fake = new Fake(h -> h.onMessage("[\"AUTH\",\"ch-1\"]"),
                (raw, h) -> ackOk(null, raw, h, mapper), new Sink());
        BuzzChannelAdapter adapter = plainAdapter(fake.connector());

        adapter.send(defaultChannel(), ChannelMessage.outbound("ch-1", "Hi", "room-1", null));

        List<String> authFrames = framesStartingWith(fake.sink.sent, "[\"AUTH\"");
        assertThat(authFrames).hasSize(1);
        JsonNode authEvent = new ObjectMapper().readTree(authFrames.get(0)).get(1);
        assertThat(authEvent.get("kind").asInt()).isEqualTo(22242);
        assertThat(authEvent.get("tags").get(0).get(1).asText()).isEqualTo("wss://relay.local");
        assertThat(authEvent.get("tags").get(1).get(1).asText()).isEqualTo("ch-1");
        // EVENT wurde initial und nach der AUTH erneut gesendet
        assertThat(framesStartingWith(fake.sink.sent, "[\"EVENT\""))
                .hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void sendThrowsWhenRelayKeepsChallenging() throws Exception {
        Fake fake = new Fake(h -> h.onMessage("[\"AUTH\",\"ch-1\"]"),
                (raw, h) -> {
                    if (raw.startsWith("[\"EVENT\"")) {
                        h.onMessage("[\"AUTH\",\"ch-2\"]");
                    }
                }, new Sink());
        BuzzChannelAdapter adapter = plainAdapter(fake.connector());
        assertThatThrownBy(() -> adapter.send(defaultChannel(),
                ChannelMessage.outbound("ch-1", "Hi", "room-1", null)))
                .isInstanceOf(BuzzChannelAdapter.ChannelException.class)
                .hasMessageContaining("AUTH");
    }

    // --- Empfang (Ingress-Monitor) ---

    private static Map<String, IngressCursor> sharedCursors() {
        return new ConcurrentHashMap<>();
    }

    @Test
    void pollBootstrapEstablishesCursorWithoutRequest() throws Exception {
        MemStore store = new MemStore();
        Sink sink = new Sink();
        Fake fake = new Fake(null, (raw, h) -> respondToReq(raw, h), sink);
        BuzzChannelAdapter adapter = monitorAdapter(fake.connector(), store);

        List<ChannelMessage> result = adapter.pollInbound(defaultChannel());

        assertThat(result).isEmpty();
        assertThat(sink.sent).noneMatch(f -> f.startsWith("[\"REQ\""));
        assertThat(store.loadCursor("ch-1")).isPresent();
        assertThat(store.loadCursor("ch-1").get().lastItemId()).endsWith(":bootstrap");
    }

    @Test
    void pollDeliversNewMessagesInOrder() throws Exception {
        MemStore store = new MemStore();
        Sink sink = new Sink();
        long now = Instant.now().getEpochSecond();
        Fake fake = new Fake(null, (raw, h) -> respondToReq(raw, h,
                eventFrame("evt-1", "usrA", now, "Erste"),
                eventFrame("evt-2", "usrB", now + 1, "Zweite")), sink);
        BuzzChannelAdapter adapter = monitorAdapter(fake.connector(), store);

        assertThat(adapter.pollInbound(defaultChannel())).isEmpty(); // Bootstrap
        List<ChannelMessage> messages = adapter.pollInbound(defaultChannel());

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).content()).isEqualTo("Erste");
        assertThat(messages.get(0).senderId()).isEqualTo("usra");
        assertThat(messages.get(0).threadId()).isEqualTo("buzz:room:room-1");
        assertThat(messages.get(0).externalId()).isEqualTo(now + ":evt-1");
        assertThat(messages.get(1).content()).isEqualTo("Zweite");
        assertThat(store.loadCursor("ch-1").get().lastItemId()).isEqualTo((now + 1) + ":evt-2");
    }

    @Test
    void pollSkipsSelfMessages() throws Exception {
        MemStore store = new MemStore();
        long now = Instant.now().getEpochSecond();
        Fake fake = new Fake(null, (raw, h) -> respondToReq(raw, h,
                eventFrame("evt-self", PUB, now, "Von mir"),
                eventFrame("evt-other", "usrB", now + 1, "Von Alice")), new Sink());
        BuzzChannelAdapter adapter = monitorAdapter(fake.connector(), store);

        adapter.pollInbound(defaultChannel());
        List<ChannelMessage> messages = adapter.pollInbound(defaultChannel());

        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).content()).isEqualTo("Von Alice");
    }

    @Test
    void pollDeduplicatesAcrossRestartViaSharedStore() throws Exception {
        MemStore store = new MemStore();
        long now = Instant.now().getEpochSecond();
        Fake fake1 = new Fake(null, (raw, h) -> respondToReq(raw, h,
                eventFrame("evt-a", "usrA", now, "Alt")), new Sink());
        BuzzChannelAdapter adapter1 = monitorAdapter(fake1.connector(), store);
        adapter1.pollInbound(defaultChannel());
        assertThat(adapter1.pollInbound(defaultChannel())).hasSize(1);

        // "Neustart": zweite Adapter-Instanz, gleicher Store
        Fake fake2 = new Fake(null, (raw, h) -> respondToReq(raw, h,
                eventFrame("evt-a", "usrA", now, "Alt"),
                eventFrame("evt-b", "usrB", now + 1, "Neu")), new Sink());
        BuzzChannelAdapter adapter2 = monitorAdapter(fake2.connector(), store);

        List<ChannelMessage> messages = adapter2.pollInbound(defaultChannel());
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).content()).isEqualTo("Neu");
    }

    @Test
    void pollRejectsEventsOlderThanCursor() throws Exception {
        MemStore store = new MemStore();
        long now = Instant.now().getEpochSecond();
        Fake fake = new Fake(null, (raw, h) -> respondToReq(raw, h,
                eventFrame("evt-a", "usrA", now, "Alt")), new Sink());
        BuzzChannelAdapter adapter = monitorAdapter(fake.connector(), store);
        adapter.pollInbound(defaultChannel());
        adapter.pollInbound(defaultChannel());

        Fake fake2 = new Fake(null, (raw, h) -> respondToReq(raw, h,
                eventFrame("evt-old", "usrC", now - 10, "Zu alt"),
                eventFrame("evt-new", "usrD", now + 1, "Neu")), new Sink());
        BuzzChannelAdapter adapter2 = monitorAdapter(fake2.connector(), store);

        List<ChannelMessage> messages = adapter2.pollInbound(defaultChannel());
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).content()).isEqualTo("Neu");
    }

    @Test
    void pollMapsThreadReplyIntoThreadTarget() throws Exception {
        MemStore store = new MemStore();
        long now = Instant.now().getEpochSecond();
        Fake fake = new Fake(null, (raw, h) -> respondToReq(raw, h,
                eventFrame("evt-reply", "usrA", now, "Antwort im Thread",
                        "[[\"h\",\"room-1\"],[\"e\",\"ROOTEVT\",\"\",\"reply\"]]")),
                new Sink());
        BuzzChannelAdapter adapter = monitorAdapter(fake.connector(), store);

        adapter.pollInbound(defaultChannel());
        List<ChannelMessage> messages = adapter.pollInbound(defaultChannel());

        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).threadId()).isEqualTo("buzz:thread:room-1:ROOTEVT");
    }

    @Test
    void pollAnswersNip42AuthAndResubscribes() throws Exception {
        MemStore store = new MemStore();
        long now = Instant.now().getEpochSecond();
        Sink sink = new Sink();
        Fake fake = new Fake(h -> h.onMessage("[\"AUTH\",\"ch-1\"]"),
                (raw, h) -> respondToReq(raw, h,
                        eventFrame("evt-1", "usrA", now, "Erste"),
                        eventFrame("evt-2", "usrB", now + 1, "Zweite")), sink);
        BuzzChannelAdapter adapter = monitorAdapter(fake.connector(), store);

        adapter.pollInbound(defaultChannel());
        List<ChannelMessage> messages = adapter.pollInbound(defaultChannel());

        assertThat(messages).hasSize(2);
        // AUTH beantwortet und per frischer REQ erneut abonniert
        assertThat(framesStartingWith(sink.sent, "[\"AUTH\"")).hasSize(1);
        assertThat(framesStartingWith(sink.sent, "[\"REQ\"")).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void pollInboundWithoutMonitorThrows() {
        Fake fake = new Fake(null, null, new Sink());
        BuzzChannelAdapter adapter = plainAdapter(fake.connector());
        assertThatThrownBy(() -> adapter.pollInbound(defaultChannel()))
                .isInstanceOf(IllegalStateException.class);
    }

    // --- Empfangs-Loop ---

    @Test
    void startReceivingRequiresDefaultTo() {
        Fake fake = new Fake(null, null, new Sink());
        BuzzChannelAdapter adapter = monitorAdapter(fake.connector(), new MemStore());
        Channel noTarget = channel(config("relay", "wss://relay.local", "privateKey", PRIV));
        assertThatThrownBy(() -> adapter.startReceiving(noTarget, m -> {
        })).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void startReceivingRequiresPrivateKey() {
        Fake fake = new Fake(null, null, new Sink());
        BuzzChannelAdapter adapter = monitorAdapter(fake.connector(), new MemStore());
        Channel noKey = channel(config("relay", "wss://relay.local", "defaultTo", "room-1"));
        assertThatThrownBy(() -> adapter.startReceiving(noKey, m -> {
        })).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void startReceivingDeliversViaMonitorLoop() throws Exception {
        MemStore store = new MemStore();
        long now = Instant.now().getEpochSecond();
        CountDownLatch delivered = new CountDownLatch(1);
        List<String> contents = new ArrayList<>();
        Sink sink = new Sink();
        Fake fake = new Fake(null, (raw, h) -> respondToReq(raw, h,
                eventFrame("evt-loop", "usrA", now, "Loop-Nachricht")), sink);
        BuzzChannelAdapter adapter = monitorAdapter(fake.connector(), store);
        Channel loop = channel(config("relay", "wss://relay.local", "privateKey", PRIV,
                "defaultTo", "room-1", "pollIntervalSeconds", 1, "relayTimeoutSeconds", 5));

        adapter.startReceiving(loop, m -> {
            contents.add(m.content());
            delivered.countDown();
        });

        assertThat(delivered.await(8, TimeUnit.SECONDS)).isTrue();
        assertThat(contents).containsExactly("Loop-Nachricht");
        adapter.stopReceiving(loop);
        assertThat(sink.closed).isTrue();
    }

    // --- Verfügbarkeit ---

    @Test
    void isAvailableReflectsConfiguration() {
        Fake fake = new Fake(null, null, new Sink());
        BuzzChannelAdapter adapter = plainAdapter(fake.connector());
        assertThat(adapter.isAvailable(defaultChannel())).isTrue();
        assertThat(adapter.isAvailable(channel(config("relay", "wss://relay.local", "privateKey", PRIV,
                "defaultTo", "room-1")))).isTrue();

        assertThat(adapter.isAvailable(channel(config("relay", "wss://relay.local",
                "privateKey", PRIV, "defaultTo", "room-1")).withEnabled(false))).isFalse();
        assertThat(adapter.isAvailable(channel(config("relay", "wss://relay.local",
                "defaultTo", "room-1")))).isFalse();
        assertThat(adapter.isAvailable(channel(config("privateKey", PRIV,
                "defaultTo", "room-1")))).isFalse();
        assertThat(adapter.isAvailable(channel(config("relay", "wss://relay.local",
                "privateKey", "xxyy", "defaultTo", "room-1")))).isFalse();
        assertThat(adapter.isAvailable(null)).isFalse();
    }

    // --- Store-Double ---

    private static final class MemStore implements IngressCursorStore {
        private final Map<String, IngressCursor> cursors = new ConcurrentHashMap<>();
        private final java.util.Set<String> admitted = ConcurrentHashMap.newKeySet();

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
}