package biz.brumm.infrastructure.adapter.out.channel;

import biz.brumm.domain.model.Channel;
import biz.brumm.domain.model.ChannelMessage;
import biz.brumm.domain.model.ChannelType;
import biz.brumm.domain.model.MessageDirection;
import biz.brumm.domain.port.out.ChannelAdapter;
import biz.brumm.domain.service.ChannelIngressMonitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ClickClack-Channel-Adapter (P3-06).
 * <p>
 * Implementiert {@link ChannelAdapter} für **ClickClack** (Community-Chat-Plattform)
 * über die ClickClack-REST-API mit einem Bot-Token ({@code ccb_…}):
 * <ul>
 *   <li><b>Senden:</b> über Ziel-Spezifikationen aus {@code threadId}/{@code senderId}
 *       (oder config-{@code defaultTo}) im OpenClaw-Format:
 *       {@code channel:<name-or-id>} (auch ohne Präfix), {@code dm:<user_id>}
 *       oder {@code thread:<message_id>}. Channel-Namen werden über die
 *       Workspace-Channelliste aufgelöst; DMs über die eigene DM-Liste gefunden
 *       oder angelegt.</li>
 *   <li><b>Empfang:</b> Polling des Realtime-Event-Logs
 *       ({@code GET /api/realtime/events}) im Daemon-Loop; {@code message.created}-/
 *       {@code thread.reply_created}-Events werden über
 *       {@code GET /api/messages/{id}} hydriert (Message-IDs inkl. Author, Body,
 *       Channel/DM/Thread-Context). Eigen-Nachrichten werden anhand des Bot-Users
 *       gefiltert.</li>
 * </ul>
 * <p>
 * Der Empfangs-Lifecycle nutzt die **Ingress-Monitor-Abstraktion (P3-08)**:
 * Lauf 1 etabliert nur den Event-Cursor am Aktuell-Endpunkt (kein History-Replay),
 * danach werden neue Events übernommen; die {@code IngressCursorStore}-Admission
 * des Monitors stellt At-most-once über Neustarts hinweg sicher.
 * <p>
 * Erwartete Konfiguration im {@code Channel.config}:
 * <ul>
 *   <li>{@code token} – ClickClack-Bot-Token, Standard {@code ccb_…} (Pflicht)</li>
 *   <li>{@code workspace} – Workspace-Id, -Slug oder -Name (Pflicht)</li>
 *   <li>{@code baseUrl} – ClickClack-URL (Pflicht); optional {@code apiBaseUrl}
 *       (Server-zu-Server-Endpunkt, sonst {@code baseUrl})</li>
 *   <li>{@code pollIntervalSeconds} – Polling-Intervall (optional, Standard 30)</li>
 *   <li>{@code defaultTo} – Ziel-Spezifikation, wenn threadId/senderId fehlen
 *       (optional, Standard: Fehler)</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(prefix = "jclaw.channels", name = "enabled", havingValue = "true")
public class ClickClackChannelAdapter implements ChannelAdapter {

    private static final Logger log = LoggerFactory.getLogger(ClickClackChannelAdapter.class);

    private static final String CONFIG_BASE_URL = "baseUrl";
    private static final String CONFIG_API_BASE_URL = "apiBaseUrl";
    private static final String CONFIG_TOKEN = "token";
    private static final String CONFIG_WORKSPACE = "workspace";
    private static final String CONFIG_DEFAULT_TO = "defaultTo";
    private static final String CONFIG_POLL_INTERVAL = "pollIntervalSeconds";

    private static final Set<String> MESSAGE_EVENT_TYPES = Set.of("message.created", "thread.reply_created");

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ChannelIngressMonitor ingressMonitor;

    private final Map<String, String> workspaceIds = new ConcurrentHashMap<>();
    private final Map<String, String> botUserIds = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> channelIdsByChannel = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> dmIdsByChannel = new ConcurrentHashMap<>();
    private final Map<String, String> eventCursorsByChannel = new ConcurrentHashMap<>();
    private final Map<String, ChannelIngressMonitor.IngressSession> sessions = new ConcurrentHashMap<>();

    @Autowired
    public ClickClackChannelAdapter(ChannelIngressMonitor ingressMonitor) {
        this(HttpClient.newHttpClient(), new ObjectMapper(), ingressMonitor);
    }

    ClickClackChannelAdapter(HttpClient httpClient, ObjectMapper objectMapper,
                             ChannelIngressMonitor ingressMonitor) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.ingressMonitor = ingressMonitor;
    }

    ClickClackChannelAdapter(HttpClient httpClient, ObjectMapper objectMapper) {
        this(httpClient, objectMapper, null);
    }

    @Override
    public ChannelType channelType() {
        return ChannelType.CLICKCLACK;
    }

    @Override
    public ChannelMessage send(Channel channel, ChannelMessage message) throws ChannelException {
        String token = requireToken(channel);
        if (message.content() == null || message.content().isBlank()) {
            throw new ChannelException("Nachrichteninhalt darf nicht leer sein.");
        }
        String target = resolveTarget(message, channel);
        String payload;
        try {
            payload = objectMapper.writeValueAsString(Map.of("body", message.content()));
        } catch (JacksonException e) {
            throw new ChannelException("ClickClack-Payload konnte nicht erzeugt werden.", e);
        }
        try {
            String url = sendUrl(channel, target, token);
            HttpResponse<String> response = postJson(url, payload, token);
            if (response.statusCode() >= 400) {
                throw new ChannelException("ClickClack-API-Fehler: HTTP " + response.statusCode());
            }
            String externalId = parseExternalId(response.body());
            log.info("ClickClack gesendet ({}): {}", externalId,
                    message.content().length() > 50
                            ? message.content().substring(0, 50) + "..." : message.content());
            return new ChannelMessage(UUID.randomUUID().toString(), channel.id(), externalId,
                    MessageDirection.OUTBOUND, message.content(), message.senderId(),
                    message.senderName(), normalizeTarget(target), message.sessionId(), Instant.now());
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ChannelException("ClickClack-API-Aufruf fehlgeschlagen: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean isAvailable(Channel channel) {
        if (channel == null || !channel.enabled()) {
            return false;
        }
        return hasText(configString(channel, CONFIG_TOKEN)) && hasText(configString(channel, CONFIG_WORKSPACE))
                && hasText(apiBase(channel));
    }

    @Override
    public synchronized void startReceiving(Channel channel, InboundMessageHandler handler) {
        if (handler == null) {
            throw new IllegalArgumentException("InboundMessageHandler darf nicht null sein.");
        }
        if (ingressMonitor == null) {
            throw new IllegalStateException("Kein ChannelIngressMonitor konfiguriert.");
        }
        stopReceiving(channel);
        if (!hasText(requireTokenOrNull(channel))) {
            throw new IllegalArgumentException("ClickClack-Token fehlt in der Channel-Konfiguration.");
        }
        ChannelIngressMonitor.IngressSession session = ingressMonitor.start(
                channel, pollInterval(channel), new ClickClackIngressPoller(channel), handler);
        sessions.put(channel.id(), session);
        log.info("ClickClack-Empfang fuer Channel '{}' gestartet.", channel.name());
    }

    @Override
    public void stopReceiving(Channel channel) {
        ChannelIngressMonitor.IngressSession session = sessions.remove(channel.id());
        if (session != null) {
            session.stop();
            log.info("ClickClack-Empfang fuer Channel '{}' gestoppt.", channel.name());
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
        ingressMonitor.pollOnce(channel, new ClickClackIngressPoller(channel), delivered::add,
                null, Instant.now());
        return delivered;
    }

    // --- Sende-Ziel-Aufloesung ---

    private String sendUrl(Channel channel, String target, String token)
            throws IOException, InterruptedException, ChannelException {
        String normalized = normalizeTarget(target);
        if (normalized.startsWith("dm:")) {
            String userId = normalized.substring("dm:".length());
            String dmId = resolveDmId(channel, userId, token);
            return apiBase(channel) + "/api/dms/" + dmId + "/messages";
        }
        if (normalized.startsWith("thread:")) {
            String messageId = normalized.substring("thread:".length());
            if (messageId.isBlank()) {
                throw new ChannelException("Leere thread:-Zielangabe.");
            }
            return apiBase(channel) + "/api/messages/" + messageId + "/thread/replies";
        }
        String channelRef = normalized.startsWith("channel:")
                ? normalized.substring("channel:".length()) : normalized;
        String channelId = resolveChannelId(channel, channelRef, token);
        return apiBase(channel) + "/api/channels/" + channelId + "/messages";
    }

    /**
     * Erste threadId/senderId als Ziel-Spezifikation, sonst config-{@code defaultTo}.
     */
    private String resolveTarget(ChannelMessage message, Channel channel) throws ChannelException {
        if (hasText(message.threadId())) {
            return message.threadId();
        }
        if (hasText(message.senderId())) {
            return message.senderId();
        }
        String defaultTo = configString(channel, CONFIG_DEFAULT_TO);
        if (hasText(defaultTo)) {
            return defaultTo;
        }
        throw new ChannelException("Kein Ziel (threadId/senderId/defaultTo) fuer die Nachricht vorhanden.");
    }

    /**
     * Normalisiert ein Ziel: entfernt optionale "clickclack:"/"cc:"-Provider-Präfixe
     * und ergänzt bei unbekanntem Präfix das implizite {@code channel:}.
     */
    private static String normalizeTarget(String target) {
        String t = target.trim();
        if (t.startsWith("clickclack:") || t.startsWith("cc:")) {
            t = t.substring(t.indexOf(':') + 1).trim();
        }
        if (t.startsWith("dm:") || t.startsWith("thread:") || t.startsWith("channel:")) {
            return t;
        }
        return "channel:" + t;
    }

    private String resolveChannelId(Channel channel, String channelRef, String token)
            throws IOException, InterruptedException, ChannelException {
        if (channelRef.startsWith("chn_")) {
            return channelRef;
        }
        Map<String, String> cache = channelIdsByChannel.computeIfAbsent(channel.id(), k -> new ConcurrentHashMap<>());
        String cached = cache.get(channelRef);
        if (cached != null) {
            return cached;
        }
        String workspaceId = resolveWorkspaceId(channel, token);
        HttpResponse<String> response = getJson(
                apiBase(channel) + "/api/workspaces/" + workspaceId + "/channels", token);
        if (response.statusCode() >= 400) {
            throw new ChannelException("ClickClack-API-Fehler: HTTP " + response.statusCode());
        }
        JsonNode channels = parseArray(response.body(), "Channelliste");
        for (JsonNode c : channels) {
            String id = c.path("id").asText(null);
            Map<String, String> names = Map.of(
                    "name", c.path("name").asText(""),
                    "slug", c.path("slug").asText(c.path("name").asText("")),
                    "display_title", c.path("display_title").asText(""));
            if (id != null) {
                names.forEach((k, v) -> {
                    if (!v.isBlank()) {
                        cache.put(v, id);
                    }
                });
            }
        }
        String id = cache.get(channelRef);
        if (id != null) {
            return id;
        }
        throw new ChannelException("ClickClack-Channel nicht gefunden: " + channelRef);
    }

    private String resolveWorkspaceId(Channel channel, String token)
            throws IOException, InterruptedException, ChannelException {
        String cached = workspaceIds.get(channel.id());
        if (cached != null) {
            return cached;
        }
        String configured = configString(channel, CONFIG_WORKSPACE);
        if (!hasText(configured)) {
            throw new ChannelException("ClickClack-Workspace fehlt in der Channel-Konfiguration.");
        }
        HttpResponse<String> response = getJson(apiBase(channel) + "/api/workspaces", token);
        if (response.statusCode() >= 400) {
            throw new ChannelException("ClickClack-API-Fehler: HTTP " + response.statusCode());
        }
        JsonNode workspaces = parseArray(response.body(), "Workspaceliste");
        for (JsonNode w : workspaces) {
            String id = w.path("id").asText(null);
            if (id == null) {
                continue;
            }
            if (configured.equals(id) || configured.equals(w.path("slug").asText(null))
                    || configured.equals(w.path("name").asText(null))) {
                workspaceIds.put(channel.id(), id);
                return id;
            }
        }
        throw new ChannelException("ClickClack-Workspace nicht gefunden: " + configured);
    }

    private String resolveDmId(Channel channel, String userId, String token)
            throws IOException, InterruptedException, ChannelException {
        Map<String, String> cache = dmIdsByChannel.computeIfAbsent(channel.id(), k -> new ConcurrentHashMap<>());
        String cached = cache.get(userId);
        if (cached != null) {
            return cached;
        }
        String workspaceId = resolveWorkspaceId(channel, token);
        HttpResponse<String> list = getJson(
                apiBase(channel) + "/api/dms?workspace_id=" + encode(workspaceId), token);
        if (list.statusCode() >= 400) {
            throw new ChannelException("ClickClack-API-Fehler: HTTP " + list.statusCode());
        }
        JsonNode dms = parseArray(list.body(), "DM-Liste");
        for (JsonNode dm : dms) {
            if (!dm.path("can_send").asBoolean(true)) {
                continue;
            }
            for (JsonNode member : dm.path("members")) {
                if (userId.equals(member.path("id").asText(null))) {
                    String id = dm.path("id").asText(null);
                    if (id != null) {
                        cache.put(userId, id);
                        return id;
                    }
                }
            }
        }
        String createPayload;
        try {
            createPayload = objectMapper.writeValueAsString(
                    Map.of("workspace_id", workspaceId, "member_ids", List.of(userId)));
        } catch (JacksonException e) {
            throw new ChannelException("ClickClack-DM-Payload konnte nicht erzeugt werden.", e);
        }
        HttpResponse<String> created = postJson(apiBase(channel) + "/api/dms", createPayload, token);
        if (created.statusCode() >= 400) {
            throw new ChannelException("ClickClack-API-Fehler: HTTP " + created.statusCode());
        }
        JsonNode root = parseObject(created.body(), "DM-Anlage");
        String id = root.path("id").asText(null);
        if (id == null) {
            throw new ChannelException("ClickClack-DM-Anlage lieferte keine Konversations-Id.");
        }
        cache.put(userId, id);
        return id;
    }

    private String parseExternalId(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            String id = root.path("message").path("id").asText(null);
            if (id == null) {
                id = root.path("id").asText(null);
            }
            if (id == null) {
                id = root.path("event").path("payload").path("message_id").asText(null);
            }
            return id;
        } catch (JacksonException e) {
            return null;
        }
    }

    // --- Ingress-Polling (P3-08 Monitor-Poller) ---

    private final class ClickClackIngressPoller implements ChannelIngressMonitor.IngressPoller {

        private final Channel channel;

        private ClickClackIngressPoller(Channel channel) {
            this.channel = channel;
        }

        @Override
        public List<ChannelMessage> poll(String sincePosition) throws Exception {
            String token = requireToken(channel);
            String workspaceId = resolveWorkspaceId(channel, token);
            String botUserId = resolveBotUserId(channel, token);
            String cursor = eventCursorsByChannel.get(channel.id());
            if (cursor == null) {
                // Bootstrap: Cursor am Aktuell-Endpunkt etablieren (kein History-Replay).
                JsonNode body = fetchEvents(workspaceId, null, token);
                String tail = body.path("tail_cursor").asText(null);
                eventCursorsByChannel.put(channel.id(), tail == null ? "" : tail);
                return List.of();
            }
            JsonNode body = fetchEvents(workspaceId, cursor, token);
            JsonNode events = body.path("events");
            if (!events.isArray()) {
                return List.of();
            }
            List<ChannelMessage> result = new ArrayList<>();
            String lastCursor = cursor;
            for (JsonNode event : events) {
                String eventCursor = event.path("cursor").asText(null);
                String type = event.path("type").asText(null);
                if (!MESSAGE_EVENT_TYPES.contains(type)) {
                    if (eventCursor != null) {
                        lastCursor = eventCursor;
                    }
                    continue;
                }
                String authorId = event.path("payload").path("author_id").asText(null);
                if (botUserId != null && botUserId.equals(authorId)) {
                    if (eventCursor != null) {
                        lastCursor = eventCursor;
                    }
                    continue;
                }
                String messageId = event.path("payload").path("message_id").asText(null);
                if (!hasText(messageId)) {
                    if (eventCursor != null) {
                        lastCursor = eventCursor;
                    }
                    continue;
                }
                JsonNode message = fetchMessage(messageId, token);
                if (message == null || message.isMissingNode()) {
                    // Fetch fehlgeschlagen → Cursor nicht ueberspringen (Retry im naechsten Poll).
                    break;
                }
                ChannelMessage m = toInboundMessage(channel, event, message, botUserId);
                if (m != null) {
                    result.add(m);
                }
                if (eventCursor != null) {
                    lastCursor = eventCursor;
                }
            }
            eventCursorsByChannel.put(channel.id(), lastCursor);
            return result;
        }

        private JsonNode fetchEvents(String workspaceId, String afterCursor, String token)
                throws IOException, InterruptedException, ChannelException {
            StringBuilder url = new StringBuilder(apiBase(channel))
                    .append("/api/realtime/events?workspace_id=").append(encode(workspaceId))
                    .append("&limit=100&include_tail=true");
            if (afterCursor != null) {
                url.append("&after_cursor=").append(encode(afterCursor));
            }
            HttpResponse<String> response = getJson(url.toString(), token);
            if (response.statusCode() >= 400) {
                throw new ChannelException("ClickClack-API-Fehler: HTTP " + response.statusCode());
            }
            return parseObject(response.body(), "Event-Log");
        }

        private JsonNode fetchMessage(String messageId, String token)
                throws IOException, InterruptedException {
            HttpResponse<String> response = getJson(
                    apiBase(channel) + "/api/messages/" + messageId, token);
            if (response.statusCode() >= 400) {
                log.warn("ClickClack-Nachrichtenabruf fehlgeschlagen ({}): HTTP {}",
                        messageId, response.statusCode());
                return null;
            }
            try {
                return objectMapper.readTree(response.body());
            } catch (JacksonException e) {
                log.warn("ClickClack-Nachrichtenabruf lieferte ungueltiges JSON.", e);
                return null;
            }
        }
    }

    private ChannelMessage toInboundMessage(Channel channel, JsonNode event, JsonNode message,
                                            String botUserId) {
        String messageAuthor = message.path("author").path("id").asText(null);
        if (botUserId != null && botUserId.equals(messageAuthor)) {
            return null;
        }
        String body = message.path("body").asText(null);
        if (body == null || body.isBlank()) {
            return null;
        }
        String senderId = hasText(messageAuthor) ? messageAuthor : "unbekannt";
        String senderName = firstNonBlank(
                message.path("author").path("name").asText(null),
                message.path("author").path("handle").asText(null));
        String directConversationId = message.path("direct_conversation_id").asText(null);
        String parentMessageId = message.path("parent_message_id").asText(null);
        String channelId = message.path("channel_id").asText(null);
        String threadId;
        if (hasText(directConversationId)) {
            threadId = "dm:" + directConversationId;
        } else if (hasText(parentMessageId)) {
            threadId = "thread:" + parentMessageId;
        } else if (hasText(channelId)) {
            threadId = "channel:" + channelId;
        } else {
            threadId = null;
        }
        String externalId = event.path("id").asText(null);
        if (externalId == null) {
            externalId = message.path("id").asText(null);
        }
        return ChannelMessage.inbound(channel.id(), externalId, body, senderId, senderName,
                threadId, null);
    }

    // --- Infrastruktur ---

    private String resolveBotUserId(Channel channel, String token) {
        String cached = botUserIds.get(channel.id());
        if (cached != null) {
            return cached.isEmpty() ? null : cached;
        }
        try {
            HttpResponse<String> response = getJson(apiBase(channel) + "/api/me", token);
            if (response.statusCode() < 400) {
                JsonNode root = parseObject(response.body(), "Bot-Identität");
                String id = root.path("id").asText(null);
                botUserIds.put(channel.id(), id == null ? "" : id);
                return id;
            }
        } catch (Exception e) {
            log.warn("ClickClack-Identitaetsabruf fehlgeschlagen – Selbst-Filter deaktiviert.", e);
        }
        botUserIds.put(channel.id(), "");
        return null;
    }

    private HttpResponse<String> getJson(String url, String token) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .GET().build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postJson(String url, String payload, String token)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode parseArray(String body, String what) throws ChannelException {
        try {
            JsonNode node = objectMapper.readTree(body);
            if (node.isArray()) {
                return node;
            }
            JsonNode array = node.path(what.toLowerCase().replaceAll("[^a-zA-ZäöüÄÖÜ0-9]", ""));
            return array.isArray() ? array : node.path("data").isArray()
                    ? node.path("data") : node.path("items");
        } catch (JacksonException e) {
            throw new ChannelException("Ungueltige ClickClack-Antwort (" + what + ").", e);
        }
    }

    private JsonNode parseObject(String body, String what) throws ChannelException {
        try {
            return objectMapper.readTree(body);
        } catch (JacksonException e) {
            throw new ChannelException("Ungueltige ClickClack-Antwort (" + what + ").", e);
        }
    }

    private String apiBase(Channel channel) {
        String url = configString(channel, CONFIG_API_BASE_URL);
        if (!hasText(url)) {
            url = configString(channel, CONFIG_BASE_URL);
        }
        if (url == null) {
            return "";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private String requireToken(Channel channel) throws ChannelException {
        String token = configString(channel, CONFIG_TOKEN);
        if (!hasText(token)) {
            throw new ChannelException("ClickClack-Token fehlt in der Channel-Konfiguration.");
        }
        if (!hasText(apiBase(channel))) {
            throw new ChannelException("ClickClack-Base-URL fehlt in der Channel-Konfiguration.");
        }
        return token;
    }

    private String requireTokenOrNull(Channel channel) {
        String token = configString(channel, CONFIG_TOKEN);
        return hasText(token) ? token : null;
    }

    private int pollInterval(Channel channel) {
        Object v = channel.config().get(CONFIG_POLL_INTERVAL);
        if (v instanceof Number n) {
            return Math.max(1, n.intValue());
        }
        return 30;
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

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}