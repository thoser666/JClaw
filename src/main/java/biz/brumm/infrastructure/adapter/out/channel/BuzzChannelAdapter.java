package biz.brumm.infrastructure.adapter.out.channel;

import biz.brumm.domain.model.Channel;
import biz.brumm.domain.model.ChannelMessage;
import biz.brumm.domain.model.ChannelType;
import biz.brumm.domain.model.IngressCursor;
import biz.brumm.domain.model.MessageDirection;
import biz.brumm.domain.port.out.ChannelAdapter;
import biz.brumm.domain.port.out.IngressCursorStore;
import biz.brumm.domain.service.ChannelIngressMonitor;
import jakarta.websocket.ClientEndpoint;
import jakarta.websocket.CloseReason;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnMessage;
import jakarta.websocket.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Buzz-Channel-Adapter (P3-06).
 * <p>
 * Implementiert {@link ChannelAdapter} für **Buzz** (Nostr-Relay mit NIP-29).
 * Der Adapter verbindet sich über ein NIP-01-WebSocket direkt mit dem Buzz-Relay:
 * <ul>
 *   <li><b>Senden:</b> veröffentlicht ein NIP-01-Event (kind 9) mit {@code h}-Tag
 *       (Raum-UUID) – optional als NIP-10-Thread-Antwort über ein {@code e}-Tag
 *       mit {@code reply}-Marker. Identität &amp; Signatur: BIP-340-Schnorr über
 *       die NIP-01-Event-Id (Kurve secp256k1).</li>
 *   <li><b>Empfang:</b> Polling einer NIP-01-Subscription ({@code REQ} mit
 *       {@code {"kinds":[9],"#h":[raum],"since":…}}); der Daemon-Loop läuft über
 *       die **Ingress-Monitor-Abstraktion (P3-08)**. Lauf 1 ist Bootstrap
 *       (legt nur den Zeit-Cursor an, kein History-Replay); danach werden neue
 *       Events chronologisch übernommen, eigene Nachrichten gefiltert
 *       (Selbst-Filter über die eigene Pubkey) und per
 *       {@link IngressCursorStore}-Admission at-most-once über Neustarts hinweg
 *       dedupliziert. Cursor ist die zusammengesetzte externalId
 *       {@code <created_at>:<event-id>}, damit {@code since} im Req-Filter den
 *       chronologischen Standpunkt wiederfindet.</li>
 *   <li><b>NIP-42:</b> Wird vom Relay eine {@code AUTH}-Challenge gefordert,
 *       signiert der Adapter automatisch ein kind-22242-Event und wiederholt die
 *       Operation.</li>
 * </ul>
 * <p>
 * Erwartete Konfiguration im {@code Channel.config}:
 * <ul>
 *   <li>{@code relay} – Relay-WebSocket-URL (Pflicht, z.&nbsp;B. {@code wss://…})</li>
 *   <li>{@code privateKey} – privater Schlüssel des Bots, 64 Hex-Zeichen (Pflicht;
 *       der öffentliche Schlüssel wird daraus abgeleitet und zeitgleich für
 *       Beglaubigung und Selbst-Filter genutzt)</li>
 *   <li>{@code defaultTo} – Raum-UUID (nötig für den Empfang; ohne Ziele im Senden
 *       optional)</li>
 *   <li>{@code pollIntervalSeconds} – Polling-Intervall (optional, Standard 30)</li>
 *   <li>{@code relayTimeoutSeconds} – Timeout für Relay-Antworten (optional, Standard 10)</li>
 * </ul>
 * <p>
 * Ziel-Spezifikationen (in {@code threadId} bzw. {@code defaultTo}):
 * {@code buzz:<raum-uuid>} (auch ohne {@code buzz:}-Präfix), {@code room:<uuid>}
 * oder {@code buzz:thread:<raum-uuid>:<root-event-id>} für Thread-Antworten.
 * Kanonische {@code threadId} ausgehend: {@code buzz:room:<raum-uuid>} bzw.
 * {@code buzz:thread:<raum-uuid>:<root-event-id>}.
 * {@code senderId} wird für Buzz nicht als Ziel genutzt (kein Direct Messaging).
 * <p>
 * Der WebSocket-Client ist über {@link WebSocketConnector}/{@link SessionHandle}
 * austauschbar (Tests injizieren einen Fake), die Krypto über
 * {@link NostrCrypto}. Neue Dependency: BouncyCastle (secp256k1) – die übrigen
 * Channel-Adapter bleiben dependency-frei.
 */
@Component
@ConditionalOnProperty(prefix = "jclaw.channels", name = "enabled", havingValue = "true")
public class BuzzChannelAdapter implements ChannelAdapter {

    private static final Logger log = LoggerFactory.getLogger(BuzzChannelAdapter.class);

    private static final String CONFIG_RELAY = "relay";
    private static final String CONFIG_PRIVATE_KEY = "privateKey";
    private static final String CONFIG_DEFAULT_TO = "defaultTo";
    private static final String CONFIG_POLL_INTERVAL = "pollIntervalSeconds";
    private static final String CONFIG_RELAY_TIMEOUT = "relayTimeoutSeconds";

    private static final int KIND_GROUP_MESSAGE = 9;
    private static final int KIND_AUTH = 22242;
    private static final String CLOSE_SENTINEL = "\u0000jclaw-close";

    private final ObjectMapper objectMapper;
    private final WebSocketConnector webSocketConnector;
    private final ChannelIngressMonitor ingressMonitor;
    private final IngressCursorStore cursorStore;

    private final Map<String, ChannelIngressMonitor.IngressSession> sessions = new ConcurrentHashMap<>();

    @Autowired
    public BuzzChannelAdapter(ChannelIngressMonitor ingressMonitor, IngressCursorStore cursorStore) {
        this(new ObjectMapper(), BuzzChannelAdapter::connectSocket, ingressMonitor, cursorStore);
    }

    BuzzChannelAdapter(ObjectMapper objectMapper, WebSocketConnector webSocketConnector) {
        this(objectMapper, webSocketConnector, null, null);
    }

    BuzzChannelAdapter(ObjectMapper objectMapper, WebSocketConnector webSocketConnector,
                       ChannelIngressMonitor ingressMonitor, IngressCursorStore cursorStore) {
        this.objectMapper = objectMapper;
        this.webSocketConnector = webSocketConnector;
        this.ingressMonitor = ingressMonitor;
        this.cursorStore = cursorStore;
    }

    @Override
    public ChannelType channelType() {
        return ChannelType.BUZZ;
    }

    @Override
    public ChannelMessage send(Channel channel, ChannelMessage message) throws ChannelException {
        if (message.content() == null || message.content().isBlank()) {
            throw new ChannelException("Nachrichteninhalt darf nicht leer sein.");
        }
        String privateKey = requirePrivateKey(channel);
        String pubkey = pubkey(channel);
        Target target = resolveTarget(channel, message);
        List<List<String>> tags = new ArrayList<>();
        tags.add(List.of("h", target.room()));
        if (target.rootEventId() != null) {
            tags.add(List.of("e", target.rootEventId(), "", "reply"));
        }
        Map<String, Object> event = craftEvent(channel, pubkey, privateKey,
                KIND_GROUP_MESSAGE, tags, message.content());
        String eventId = publish(channel, event);
        String targetLabel = target.rootEventId() == null
                ? "buzz:room:" + target.room()
                : "buzz:thread:" + target.room() + ":" + target.rootEventId();
        log.info("Buzz gesendet ({}): {}", eventId,
                message.content().length() > 50
                        ? message.content().substring(0, 50) + "..." : message.content());
        return new ChannelMessage(UUID.randomUUID().toString(), channel.id(), eventId,
                MessageDirection.OUTBOUND, message.content(), message.senderId(),
                message.senderName(), targetLabel, message.sessionId(), Instant.now());
    }

    @Override
    public boolean isAvailable(Channel channel) {
        if (channel == null || !channel.enabled()) {
            return false;
        }
        if (!hasText(configString(channel, CONFIG_RELAY))
                || !hasText(configString(channel, CONFIG_PRIVATE_KEY))) {
            return false;
        }
        try {
            pubkey(channel);
            return true;
        } catch (ChannelException e) {
            return false;
        }
    }

    @Override
    public synchronized void startReceiving(Channel channel, InboundMessageHandler handler) {
        if (handler == null) {
            throw new IllegalArgumentException("InboundMessageHandler darf nicht null sein.");
        }
        if (ingressMonitor == null || cursorStore == null) {
            throw new IllegalStateException("Kein ChannelIngressMonitor/IngressCursorStore konfiguriert.");
        }
        stopReceiving(channel);
        if (!hasText(requirePrivateKeyOrNull(channel))) {
            throw new IllegalArgumentException("Buzz-privater Schluessel fehlt in der Channel-Konfiguration.");
        }
        if (!hasText(configString(channel, CONFIG_RELAY))) {
            throw new IllegalArgumentException("Buzz-Relay-URL fehlt in der Channel-Konfiguration.");
        }
        try {
            pubkey(channel);
        } catch (ChannelException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
        try {
            requireRoom(channel);
        } catch (ChannelException e) {
            throw new IllegalArgumentException("Buzz-Ziel (defaultTo-Raum-UUID) fehlt in der Channel-Konfiguration.");
        }
        ChannelIngressMonitor.IngressSession session = ingressMonitor.start(
                channel, pollInterval(channel), new BuzzIngressPoller(channel), handler);
        sessions.put(channel.id(), session);
        log.info("Buzz-Empfang fuer Channel '{}' gestartet.", channel.name());
    }

    @Override
    public void stopReceiving(Channel channel) {
        ChannelIngressMonitor.IngressSession session = sessions.remove(channel.id());
        if (session != null) {
            session.stop();
            log.info("Buzz-Empfang fuer Channel '{}' gestoppt.", channel.name());
        }
    }

    /**
     * Führt genau einen Ingress-Poll-Zyklus aus (Bootstrap-Lauf liefert leer)
     * und gibt die übernommenen Nachrichten zurück – für Tests und manuelle
     * Einzelschritte (Monitor-basiert, inkl. durable admission).
     */
    List<ChannelMessage> pollInbound(Channel channel) throws Exception {
        if (ingressMonitor == null) {
            throw new IllegalStateException("Kein ChannelIngressMonitor konfiguriert.");
        }
        List<ChannelMessage> delivered = new ArrayList<>();
        ingressMonitor.pollOnce(channel, new BuzzIngressPoller(channel), delivered::add,
                null, Instant.now());
        return delivered;
    }

    // --- Sende-Ziel-Aufloesung ---

    private record Target(String room, String rootEventId) {
    }

    /**
     * Ziel aus {@code threadId} mit Fallback auf config-{@code defaultTo}.
     * Formate: {@code buzz:<raum>}, {@code room:<raum>}, {@code <raum>} oder
     * {@code buzz:thread:<raum>:<root-event-id>} (Thread-Antwort).
     */
    private Target resolveTarget(Channel channel, ChannelMessage message) throws ChannelException {
        String raw = firstNonBlank(message.threadId(), configString(channel, CONFIG_DEFAULT_TO));
        if (!hasText(raw)) {
            throw new ChannelException("Kein Ziel (threadId/defaultTo) fuer die Nachricht vorhanden.");
        }
        return parseTarget(raw);
    }

    /**
     * Raum (für die Subscription) aus der config-{@code defaultTo}-Zielangabe.
     */
    private String requireRoom(Channel channel) throws ChannelException {
        String raw = configString(channel, CONFIG_DEFAULT_TO);
        if (!hasText(raw)) {
            throw new ChannelException("Buzz-defaultTo (Raum-UUID) fehlt in der Channel-Konfiguration.");
        }
        return parseTarget(raw).room();
    }

    private static Target parseTarget(String raw) throws ChannelException {
        String t = raw.trim();
        if (t.startsWith("buzz:")) {
            t = t.substring("buzz:".length()).trim();
        }
        if (t.startsWith("thread:")) {
            String rest = t.substring("thread:".length()).trim();
            int idx = rest.indexOf(':');
            if (idx <= 0 || idx == rest.length() - 1) {
                throw new ChannelException("Ungueltige thread:-Zielangabe (erwartet thread:<raum>:<root-event-id>).");
            }
            String room = rest.substring(0, idx).trim();
            String root = rest.substring(idx + 1).trim();
            if (!hasText(room) || !hasText(root)) {
                throw new ChannelException("Ungueltige thread:-Zielangabe (erwartet thread:<raum>:<root-event-id>).");
            }
            return new Target(room, root);
        }
        if (t.startsWith("room:")) {
            t = t.substring("room:".length()).trim();
        }
        if (!hasText(t)) {
            throw new ChannelException("Leere Zielangabe.");
        }
        return new Target(t, null);
    }

    // --- Nostr-Event & Relay-Kommunikation ---

    private Map<String, Object> craftEvent(Channel channel, String pubkey, String privateKey,
                                           int kind, List<List<String>> tags, String content)
            throws ChannelException {
        long createdAt = Instant.now().getEpochSecond();
        String id = NostrCrypto.computeEventId(pubkey, createdAt, kind, tags, content);
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("id", id);
        event.put("pubkey", pubkey);
        event.put("created_at", createdAt);
        event.put("kind", kind);
        event.put("tags", tags);
        event.put("content", content);
        event.put("sig", NostrCrypto.hex(NostrCrypto.sign(privateKey, NostrCrypto.unhex(id))));
        return event;
    }

    /**
     * Veröffentlicht ein Event am Relay und wartet auf das {@code OK} (NIP-01).
     * Beantwortet eine etwaige NIP-42-{@code AUTH}-Challenge automatisch.
     */
    private String publish(Channel channel, Map<String, Object> event) throws ChannelException {
        RelayPipe pipe = new RelayPipe();
        try {
            pipe.connect(requireRelay(channel));
            long deadline = System.currentTimeMillis() + relayTimeoutMillis(channel);
            String expectedId = String.valueOf(event.get("id"));
            boolean authenticated = false;
            while (System.currentTimeMillis() < deadline) {
                pipe.send(toJson(List.of("EVENT", event)));
                long innerDeadline = System.currentTimeMillis() + relayTimeoutMillis(channel);
                while (System.currentTimeMillis() < innerDeadline) {
                    String frame = pipe.nextFrame(relayTimeoutMillis(channel));
                    if (frame == null) {
                        break;
                    }
                    JsonNode arr = objectMapper.readTree(frame);
                    String type = arr.get(0).asText();
                    if ("AUTH".equals(type)) {
                        if (authenticated) {
                            log.warn("Buzz-Relay fordert mehrfach AUTH – Senden wird abgebrochen.");
                            throw new ChannelException("Buzz-Relay lehnte das Event ab (AUTH-Schleife).");
                        }
                        authenticated = true;
                        sendAuth(pipe, channel, arr.get(1).asText(""));
                        break;
                    }
                    if ("OK".equals(type)) {
                        String id = arr.get(1).asText(null);
                        if (!expectedId.equals(id)) {
                            continue;
                        }
                        if (arr.get(2).asBoolean(false)) {
                            return expectedId;
                        }
                        throw new ChannelException("Buzz-Relay lehnte Event ab: "
                                + (arr.size() > 3 ? arr.get(3).asText("unbekannt") : "unbekannt"));
                    }
                }
            }
            throw new ChannelException("Buzz: Timeout beim Veröffentlichen des Events am Relay.");
        } catch (ChannelException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ChannelException("Buzz-Senden unterbrochen.", e);
        } catch (Exception e) {
            throw new ChannelException("Buzz-Relay-Fehler beim Senden: " + e.getMessage(), e);
        } finally {
            pipe.close();
        }
    }

    /**
     * Subscription-Daten (kind 9, h-Tag = Raum, since, limit) abfragen und die
     * gelieferten {@code EVENT}-Frames bis {@code EOSE} (bzw. Timeout) sammeln.
     */
    private List<JsonNode> reqAndCollect(RelayPipe pipe, Channel channel, String room, long since)
            throws Exception {
        String sub = newSubId();
        pipe.send(toJson(List.of("REQ", sub, reqFilter(room, since))));
        boolean authenticated = false;
        List<JsonNode> events = new ArrayList<>();
        long deadline = System.currentTimeMillis() + relayTimeoutMillis(channel);
        while (System.currentTimeMillis() < deadline) {
            String frame = pipe.nextFrame(relayTimeoutMillis(channel));
            if (frame == null) {
                break;
            }
            JsonNode arr = objectMapper.readTree(frame);
            String type = arr.get(0).asText();
            if ("AUTH".equals(type)) {
                if (!authenticated) {
                    authenticated = true;
                    sendAuth(pipe, channel, arr.get(1).asText(""));
                    pipe.send(toJson(List.of("CLOSE", sub)));
                    sub = newSubId();
                    pipe.send(toJson(List.of("REQ", sub, reqFilter(room, since))));
                }
                continue;
            }
            if ("EVENT".equals(type)) {
                events.add(arr.get(1));
                continue;
            }
            if ("EOSE".equals(type)) {
                return events;
            }
            if ("NOTICE".equals(type)) {
                log.warn("Buzz-Relay-Notice: {}", arr.size() > 1 ? arr.get(1).asText("") : "");
            }
        }
        return events;
    }

    private void sendAuth(RelayPipe pipe, Channel channel, String challenge) throws Exception {
        if (!hasText(challenge)) {
            pipe.send(toJson(List.of("AUTH", List.of())));
            return;
        }
        String pubkey = pubkey(channel);
        String privateKey = requirePrivateKey(channel);
        List<List<String>> tags = List.of(
                List.of("relay", requireRelay(channel)),
                List.of("challenge", challenge));
        Map<String, Object> event = craftEvent(channel, pubkey, privateKey,
                KIND_AUTH, tags, "");
        pipe.send(toJson(List.of("AUTH", event)));
    }

    private Map<String, Object> reqFilter(String room, long since) {
        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put("kinds", List.of(KIND_GROUP_MESSAGE));
        filter.put("#h", List.of(room));
        filter.put("since", since);
        filter.put("limit", 200);
        return filter;
    }

    private String newSubId() {
        return "jclaw-" + Long.toHexString(System.nanoTime());
    }

    // --- WebSocket (austauschbar für Tests) ---

    @FunctionalInterface
    interface WebSocketConnector {
        SessionHandle connect(String webSocketUrl, SocketMessageHandler handler) throws Exception;
    }

    interface SocketMessageHandler {
        void onMessage(String rawMessage);

        void onClose();
    }

    interface SessionHandle {
        void send(String rawMessage) throws Exception;

        void close() throws Exception;
    }

    private static SessionHandle connectSocket(String webSocketUrl, SocketMessageHandler handler)
            throws Exception {
        Session session = ContainerProvider.getWebSocketContainer()
                .connectToServer(new ClientEndpointImpl(handler), URI.create(webSocketUrl));
        return new SessionHandle() {
            @Override
            public void send(String rawMessage) throws Exception {
                session.getBasicRemote().sendText(rawMessage);
            }

            @Override
            public void close() throws Exception {
                session.close();
            }
        };
    }

    @ClientEndpoint
    static class ClientEndpointImpl {
        private final SocketMessageHandler handler;

        ClientEndpointImpl(SocketMessageHandler handler) {
            this.handler = handler;
        }

        @OnMessage
        public void onMessage(String message) {
            handler.onMessage(message);
        }

        @OnClose
        public void onClose(Session session, CloseReason reason) {
            handler.onClose();
        }
    }

    /**
     * Feingranularer Frame-Kanal zu einem Relay: {@code onMessage}-Frames landen
     * in einer {@link BlockingQueue}; {@code nextFrame} wartet bis zum Timeout.
     */
    private final class RelayPipe implements SocketMessageHandler {
        private final BlockingQueue<String> frames = new LinkedBlockingQueue<>();
        private SessionHandle handle;

        void connect(String url) throws Exception {
            handle = webSocketConnector.connect(url, this);
        }

        String nextFrame(long timeoutMillis) throws InterruptedException {
            String frame = frames.poll(timeoutMillis, TimeUnit.MILLISECONDS);
            if (frame == null || CLOSE_SENTINEL.equals(frame)) {
                return null;
            }
            return frame;
        }

        void send(String frame) throws Exception {
            handle.send(frame);
        }

        void close() {
            SessionHandle h = handle;
            handle = null;
            if (h != null) {
                try {
                    h.close();
                } catch (Exception e) {
                    log.debug("Buzz-WebSocket-Schliessen fehlgeschlagen: {}", e.getMessage());
                }
            }
        }

        @Override
        public void onMessage(String rawMessage) {
            frames.offer(rawMessage);
        }

        @Override
        public void onClose() {
            frames.offer(CLOSE_SENTINEL);
        }
    }

    // --- Ingress-Poller (P3-08) ---

    /**
     * Poller für den {@link ChannelIngressMonitor}: Lauf 1 etabliert nur den
     * Zeit-Cursor (Bootstrap), danach werden kind-9-Events des Raums abgefragt,
     * chronologisch übernommen und eigene Events gefiltert.
     */
    private class BuzzIngressPoller implements ChannelIngressMonitor.IngressPoller {
        private final Channel channel;

        BuzzIngressPoller(Channel channel) {
            this.channel = channel;
        }

        @Override
        public List<ChannelMessage> poll(String sincePosition) throws Exception {
            if (cursorStore == null) {
                throw new IllegalStateException("Kein IngressCursorStore konfiguriert.");
            }
            String room = requireRoom(channel);
            String pubkey = pubkey(channel);
            if (!hasText(sincePosition)) {
                long now = Instant.now().getEpochSecond();
                cursorStore.saveCursor(new IngressCursor(channel.id(), now + ":bootstrap", Instant.now()));
                log.info("Buzz: Bootstrap fuer Channel '{}' (Raum {}) – Cursor {}.", channel.name(), room, now);
                return List.of();
            }
            long since = parseSince(sincePosition);
            RelayPipe pipe = new RelayPipe();
            List<ChannelMessage> result = new ArrayList<>();
            try {
                pipe.connect(requireRelay(channel));
                List<JsonNode> events = reqAndCollect(pipe, channel, room, since);
                events.sort(Comparator.comparingLong((JsonNode e) -> e.path("created_at").asLong(0))
                        .thenComparing(e -> e.path("id").asText("")));
                for (JsonNode event : events) {
                    long createdAt = event.path("created_at").asLong(0);
                    if (createdAt < since) {
                        continue;
                    }
                    String author = event.path("pubkey").asText("");
                    if (pubkey.equals(author)) {
                        continue;
                    }
                    String id = event.path("id").asText("");
                    if (!hasText(id)) {
                        continue;
                    }
                    String content = event.path("content").asText("");
                    result.add(ChannelMessage.inbound(channel.id(), createdAt + ":" + id,
                            content, author, null, threadIdFor(room, event), null));
                }
            } finally {
                pipe.close();
            }
            return result;
        }

        private String threadIdFor(String room, JsonNode event) {
            JsonNode tags = event.path("tags");
            if (tags.isArray()) {
                for (JsonNode tag : tags) {
                    if (tag.isArray() && tag.size() >= 2 && "e".equals(tag.get(0).asText())) {
                        String marker = tag.size() >= 4 ? tag.get(3).asText("") : "";
                        if ("root".equals(marker) || "reply".equals(marker) || marker.isEmpty()) {
                            String root = tag.get(1).asText("");
                            if (hasText(root)) {
                                return "buzz:thread:" + room + ":" + root;
                            }
                        }
                    }
                }
            }
            return "buzz:room:" + room;
        }
    }

    // --- Hilfe ---

    private static long parseSince(String cursor) {
        int idx = cursor.indexOf(':');
        String part = idx < 0 ? cursor : cursor.substring(0, idx);
        try {
            return Long.parseLong(part.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private String requireRelay(Channel channel) throws ChannelException {
        String relay = configString(channel, CONFIG_RELAY);
        if (!hasText(relay)) {
            throw new ChannelException("Buzz-Relay-URL fehlt in der Channel-Konfiguration.");
        }
        return relay;
    }

    private String requirePrivateKey(Channel channel) throws ChannelException {
        String privateKey = configString(channel, CONFIG_PRIVATE_KEY);
        if (!hasText(privateKey)) {
            throw new ChannelException("Buzz-privater Schluessel fehlt in der Channel-Konfiguration.");
        }
        return privateKey;
    }

    private String requirePrivateKeyOrNull(Channel channel) {
        String privateKey = configString(channel, CONFIG_PRIVATE_KEY);
        return hasText(privateKey) ? privateKey : null;
    }

    private String pubkey(Channel channel) throws ChannelException {
        String privateKey = requirePrivateKey(channel);
        try {
            return NostrCrypto.derivePublicKeyHex(privateKey);
        } catch (IllegalArgumentException e) {
            throw new ChannelException("Ungueltiger Buzz-privater Schluessel: " + e.getMessage(), e);
        }
    }

    private int pollInterval(Channel channel) {
        Object v = channel.config().get(CONFIG_POLL_INTERVAL);
        if (v instanceof Number n) {
            return Math.max(1, n.intValue());
        }
        return 30;
    }

    private int relayTimeoutMillis(Channel channel) {
        Object v = channel.config().get(CONFIG_RELAY_TIMEOUT);
        if (v instanceof Number n) {
            return Math.max(1, n.intValue()) * 1000;
        }
        return 10 * 1000;
    }

    private String toJson(Object value) throws ChannelException {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new ChannelException("Buzz-Frame konnte nicht erzeugt werden.", e);
        }
    }

    private String configString(Channel channel, String key) {
        Object v = channel.config().get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static String firstNonBlank(String first, String second) {
        if (hasText(first)) {
            return first;
        }
        return hasText(second) ? second : null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}