package biz.brumm.infrastructure.adapter.out.channel;

import biz.brumm.domain.model.Channel;
import biz.brumm.domain.model.ChannelMessage;
import biz.brumm.domain.model.ChannelType;
import biz.brumm.domain.model.MessageDirection;
import biz.brumm.domain.port.out.ChannelAdapter;
import jakarta.websocket.ClientEndpoint;
import jakarta.websocket.CloseReason;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.WebSocketContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Discord-Channel-Adapter (P3-04) über den Gateway-WebSocket und die REST-API.
 * <p>
 * Implementiert {@link ChannelAdapter} für Discord:
 * <ul>
 *   <li><b>Senden:</b> {@code POST /channels/{channelId}/messages} (REST-API)</li>
 *   <li><b>Empfang:</b> Gateway-WebSocket – {@code GET /gateway} liefert die Gateway-URL;
 *       nach dem Connect wird ein {@code Identify}-Frame (op 2) mit dem Bot-Token gesendet
 *       und auf {@code MESSAGE_CREATE}-Dispatches (op 0) gehört.</li>
 * </ul>
 * <p>
 * Erwartete Konfiguration im {@code Channel.config}:
 * <ul>
 *   <li>{@code token} – Discord-Bot-Token (Pflicht)</li>
 *   <li>{@code baseUrl} – REST-API-Basis-URL (optional, Standard https://discord.com/api/v10)</li>
 *   <li>{@code intents} – Gateway-Intents (optional, Standard 4609 = GUILDS | GUILD_MESSAGES | DIRECT_MESSAGES)</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(prefix = "jclaw.channels", name = "enabled", havingValue = "true")
public class DiscordChannelAdapter implements ChannelAdapter {

    private static final Logger log = LoggerFactory.getLogger(DiscordChannelAdapter.class);
    private static final String DEFAULT_BASE_URL = "https://discord.com/api/v10";
    private static final String DEFAULT_INTENTS = "4609";
    private static final String CONFIG_TOKEN = "token";
    private static final String CONFIG_BASE_URL = "baseUrl";
    private static final String CONFIG_INTENTS = "intents";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final WebSocketConnector webSocketConnector;
    private final AtomicReference<SessionHandle> activeSocket = new AtomicReference<>();

    public DiscordChannelAdapter() {
        this(HttpClient.newHttpClient(), new ObjectMapper(), DiscordChannelAdapter::connectSocket);
    }

    DiscordChannelAdapter(HttpClient httpClient, ObjectMapper objectMapper,
                          WebSocketConnector webSocketConnector) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.webSocketConnector = webSocketConnector;
    }

    @Override
    public ChannelType channelType() {
        return ChannelType.DISCORD;
    }

    @Override
    public ChannelMessage send(Channel channel, ChannelMessage message) throws ChannelException {
        String token = requireToken(channel);
        if (message.content() == null || message.content().isBlank()) {
            throw new ChannelException("Nachrichteninhalt darf nicht leer sein.");
        }
        String channelId = resolveChannelId(message);
        if (channelId == null || channelId.isBlank()) {
            throw new ChannelException("Kein Discord-channel (threadId/senderId) fuer die Nachricht vorhanden.");
        }

        String url = baseUrl(channel) + "/channels/" + channelId + "/messages";
        String payload = "{\"content\":" + jsonQuote(message.content()) + "}";
        JsonNode json;
        try {
            json = postAuthorizedJson(url, payload, token);
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ChannelException("Discord-API-Aufruf fehlgeschlagen: " + e.getMessage(), e);
        }
        if (json.path("message").isObject()) {
            throw new ChannelException("Discord sendMessage fehlgeschlagen: "
                    + json.path("message").path("message").asText(json.path("message").asText("unbekannter Fehler")));
        }
        String id = json.path("id").asText(null);
        return ChannelMessage.outbound(channel.id(), message.content(),
                channelId, message.sessionId()).withExternalId(id);
    }

    @Override
    public boolean isAvailable(Channel channel) {
        if (channel == null || !channel.enabled()) {
            return false;
        }
        String token = configString(channel, CONFIG_TOKEN);
        return token != null && !token.isBlank();
    }

    @Override
    public synchronized void startReceiving(Channel channel, InboundMessageHandler handler) {
        if (handler == null) {
            throw new IllegalArgumentException("InboundMessageHandler darf nicht null sein.");
        }
        stopReceiving(channel);
        String token = requireTokenSafe(channel);
        try {
            String gatewayUrl = openGatewayConnection(channel);
            SocketClient client = new SocketClient(channel, handler, token,
                    intents(channel));
            SessionHandle handle = webSocketConnector.connect(gatewayUrl, client);
            client.attach(handle);
            activeSocket.set(handle);
            log.info("Discord-Gateway fuer Channel '{}' verbunden.", channel.name());
        } catch (Exception e) {
            log.warn("Discord-Gateway fuer Channel '{}' fehlgeschlagen: {}", channel.name(), e.getMessage());
            throw new IllegalArgumentException("Discord-Verbindung fehlgeschlagen: " + e.getMessage(), e);
        }
    }

    @Override
    public void stopReceiving(Channel channel) {
        SessionHandle h = activeSocket.getAndSet(null);
        if (h != null) {
            try {
                h.close();
            } catch (Exception e) {
                log.warn("Discord-Gateway konnte nicht geschlossen werden: {}", e.getMessage());
            }
            log.info("Discord-Gateway fuer Channel '{}' gestoppt.", channel.name());
        }
    }

    // --- Gateway: Verbindung öffnen ---

    String openGatewayConnection(Channel channel)
            throws IOException, ChannelException, InterruptedException {
        String url = baseUrl(channel) + "/gateway";
        JsonNode json = getJson(url);
        String gatewayUrl = json.path("url").asText(null);
        if (gatewayUrl == null || gatewayUrl.isBlank()) {
            throw new ChannelException("Discord-Gateway-URL fehlt in der /gateway-Antwort.");
        }
        return gatewayUrl;
    }

    // --- WebSocket-Kommunikation ---

    void handleSocketMessage(Channel channel, InboundMessageHandler handler,
                             SocketClient ackTarget, String rawMessage) {
        JsonNode frame;
        try {
            frame = objectMapper.readTree(rawMessage);
        } catch (JacksonException e) {
            log.warn("Ungueltiges Discord-Gateway-Frame: {}", e.getMessage());
            return;
        }
        int op = frame.path("op").asInt(-1);
        if (op == 1) {
            if (ackTarget != null) {
                ackTarget.heartbeat();
            }
            return;
        }
        if (op == 0 && "MESSAGE_CREATE".equals(frame.path("t").asText(""))) {
            ChannelMessage msg = toInboundMessage(channel, frame.path("d"));
            if (msg != null) {
                handler.onMessage(msg);
            }
        }
    }

    private ChannelMessage toInboundMessage(Channel channel, JsonNode d) {
        String content = d.path("content").asText(null);
        String authorId = d.path("author").path("id").asText(null);
        String channelId = d.path("channel_id").asText(null);
        String id = d.path("id").asText(null);
        if (content == null || content.isBlank()) {
            return null;
        }
        String authorName = d.path("author").path("username").asText(null);
        // Eigene Bot-Nachrichten ignorieren
        return new ChannelMessage(UUID.randomUUID().toString(), channel.id(),
                id, MessageDirection.INBOUND, content, authorId, authorName,
                channelId, null, Instant.now());
    }

    // --- HTTPS-Helfer ---

    private JsonNode postAuthorizedJson(String url, String payload, String token)
            throws IOException, ChannelException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bot " + token)
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        return sendJson(request);
    }

    private JsonNode getJson(String url)
            throws IOException, ChannelException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        return sendJson(request);
    }

    private JsonNode sendJson(HttpRequest request)
            throws IOException, ChannelException, InterruptedException {
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new ChannelException("Discord-API-Fehler: HTTP " + response.statusCode());
        }
        try {
            return objectMapper.readTree(response.body());
        } catch (JacksonException e) {
            throw new ChannelException("Ungueltige Discord-Antwort.", e);
        }
    }

    private String resolveChannelId(ChannelMessage message) {
        if (message.threadId() != null && !message.threadId().isBlank()) {
            return message.threadId();
        }
        if (message.senderId() != null && !message.senderId().isBlank()) {
            return message.senderId();
        }
        return null;
    }

    private String requireToken(Channel channel) throws ChannelException {
        String token = configString(channel, CONFIG_TOKEN);
        if (token == null || token.isBlank()) {
            throw new ChannelException("Discord-Token fehlt im Channel-Konfiguration.");
        }
        return token;
    }

    private String requireTokenSafe(Channel channel) {
        String token = configString(channel, CONFIG_TOKEN);
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Discord-Token fehlt im Channel-Konfiguration.");
        }
        return token;
    }

    private String baseUrl(Channel channel) {
        String url = configString(channel, CONFIG_BASE_URL);
        if (url == null || url.isBlank()) {
            return DEFAULT_BASE_URL;
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private String intents(Channel channel) {
        String v = configString(channel, CONFIG_INTENTS);
        return v == null || v.isBlank() ? DEFAULT_INTENTS : v;
    }

    private String configString(Channel channel, String key) {
        Object v = channel.config().get(key);
        return v == null ? null : String.valueOf(v);
    }

    private String jsonQuote(String value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException e) {
            return "\"\"";
        }
    }

    // --- WebSocket: Abstraktion für Testbarkeit ---

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
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        Session session = container.connectToServer(new ClientEndpointImpl(handler), URI.create(webSocketUrl));
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

    private final class SocketClient implements SocketMessageHandler {
        private final Channel channel;
        private final InboundMessageHandler handler;
        private final String token;
        private final String intents;
        private volatile SessionHandle handle;
        private volatile boolean identified;

        SocketClient(Channel channel, InboundMessageHandler handler, String token, String intents) {
            this.channel = channel;
            this.handler = handler;
            this.token = token;
            this.intents = intents;
        }

        void attach(SessionHandle handle) {
            this.handle = handle;
        }

        @Override
        public void onMessage(String rawMessage) {
            if (!identified && rawMessage.contains("\"op\":10")) {
                identified = true;
                identify();
            }
            handleSocketMessage(channel, handler, this, rawMessage);
        }

        @Override
        public void onClose() {
            log.info("Discord-Gateway fuer Channel '{}' geschlossen.", channel.name());
        }

        private void identify() {
            if (handle == null) {
                return;
            }
            String properties = "{\"os\":\"jclaw\",\"browser\":\"jclaw\",\"device\":\"jclaw\"}";
            String identity = "{\"op\":2,\"d\":{\"token\":" + jsonQuote(token)
                    + ",\"intents\":" + intents
                    + ",\"properties\":" + properties + "}}";
            try {
                handle.send(identity);
            } catch (Exception e) {
                log.warn("Discord-Identify konnte nicht gesendet werden: {}", e.getMessage());
            }
        }

        void heartbeat() {
            if (handle == null) {
                return;
            }
            try {
                handle.send("{\"op\":1,\"d\":null}");
            } catch (Exception e) {
                log.warn("Discord-Heartbeat konnte nicht gesendet werden: {}", e.getMessage());
            }
        }
    }
}
