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
 * Slack-Channel-Adapter (P3-03) über Socket Mode.
 * <p>
 * Implementiert {@link ChannelAdapter} für Slack:
 * <ul>
 *   <li><b>Senden:</b> {@code chat.postMessage} (HTTPS-API)</li>
 *   <li><b>Empfang:</b> Socket Mode – {@code apps.connections.open} liefert eine
 *       WebSocket-URL, über die Events (Typ {@code events_api}) empfangen werden.
 *       Jedes Envelope wird mit einer Ack-Nachricht bestätigt.</li>
 * </ul>
 * <p>
 * Erwartete Konfiguration im {@code Channel.config}:
 * <ul>
 *   <li>{@code token} – Slack Bot-Token (Pflicht, z. B. {@code xoxb-…})</li>
 *   <li>{@code baseUrl} – Slack-API-Basis-URL (optional, Standard https://slack.com/api)</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(prefix = "jclaw.channels", name = "enabled", havingValue = "true")
public class SlackChannelAdapter implements ChannelAdapter {

    private static final Logger log = LoggerFactory.getLogger(SlackChannelAdapter.class);
    private static final String DEFAULT_BASE_URL = "https://slack.com/api";
    private static final String CONFIG_TOKEN = "token";
    private static final String CONFIG_BASE_URL = "baseUrl";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final WebSocketConnector webSocketConnector;
    private final AtomicReference<SessionHandle> activeSocket = new AtomicReference<>();

    public SlackChannelAdapter() {
        this(HttpClient.newHttpClient(), new ObjectMapper(), SlackChannelAdapter::connectSocket);
    }

    SlackChannelAdapter(HttpClient httpClient, ObjectMapper objectMapper,
                        WebSocketConnector webSocketConnector) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.webSocketConnector = webSocketConnector;
    }

    @Override
    public ChannelType channelType() {
        return ChannelType.SLACK;
    }

    @Override
    public ChannelMessage send(Channel channel, ChannelMessage message) throws ChannelException {
        String token = requireToken(channel);
        if (message.content() == null || message.content().isBlank()) {
            throw new ChannelException("Nachrichteninhalt darf nicht leer sein.");
        }
        String channelId = resolveChannelId(message);
        if (channelId == null || channelId.isBlank()) {
            throw new ChannelException("Kein Slack-channel (threadId/senderId) fuer die Nachricht vorhanden.");
        }

        String url = baseUrl(channel) + "/chat.postMessage";
        String payload = "{\"channel\":" + jsonQuote(channelId)
                + ",\"text\":" + jsonQuote(message.content()) + "}";
        JsonNode json;
        try {
            json = postAuthorizedJson(url, payload, token);
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ChannelException("Slack-API-Aufruf fehlgeschlagen: " + e.getMessage(), e);
        }
        if (!json.path("ok").asBoolean(false)) {
            throw new ChannelException("Slack chat.postMessage fehlgeschlagen: "
                    + json.path("error").asText("unbekannter Fehler"));
        }
        String ts = json.path("ts").asText(null);
        return ChannelMessage.outbound(channel.id(), message.content(),
                channelId, message.sessionId()).withExternalId(ts);
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
            String wsUrl = openSocketConnection(channel, token);
            SocketClient client = new SocketClient(channel, handler);
            SessionHandle handle = webSocketConnector.connect(wsUrl, client);
            client.attach(handle);
            activeSocket.set(handle);
            log.info("Slack-Socket-Mode fuer Channel '{}' verbunden.", channel.name());
        } catch (Exception e) {
            log.warn("Slack-Socket-Mode fuer Channel '{}' fehlgeschlagen: {}", channel.name(), e.getMessage());
            throw new IllegalArgumentException("Slack-Verbindung fehlgeschlagen: " + e.getMessage(), e);
        }
    }

    @Override
    public void stopReceiving(Channel channel) {
        SessionHandle h = activeSocket.getAndSet(null);
        if (h != null) {
            try {
                h.close();
            } catch (Exception e) {
                log.warn("Slack-Socket konnte nicht geschlossen werden: {}", e.getMessage());
            }
            log.info("Slack-Socket-Mode fuer Channel '{}' gestoppt.", channel.name());
        }
    }

    // --- Socket Mode: Verbindung öffnen ---

    String openSocketConnection(Channel channel, String token)
            throws IOException, ChannelException, InterruptedException {
        String url = baseUrl(channel) + "/apps.connections.open";
        JsonNode json = postAuthorizedJson(url, "{}", token);
        if (!json.path("ok").asBoolean(false)) {
            throw new ChannelException("Slack apps.connections.open fehlgeschlagen: "
                    + json.path("error").asText("unbekannter Fehler"));
        }
        String wsUrl = json.path("url").asText(null);
        if (wsUrl == null || wsUrl.isBlank()) {
            throw new ChannelException("Slack-Socket-URL fehlt in der apps.connections.open-Antwort.");
        }
        return wsUrl;
    }

    // --- WebSocket-Kommunikation ---

    void handleSocketMessage(Channel channel, InboundMessageHandler handler,
                             String rawMessage, SocketClient ackTarget) {
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(rawMessage);
        } catch (JacksonException e) {
            log.warn("Ungueltiges Slack-Socket-Envelope: {}", e.getMessage());
            return;
        }
        String type = envelope.path("type").asText("");
        if ("events_api".equals(type)) {
            if (ackTarget != null) {
                ackTarget.sendAck(envelope.path("envelope_id").asText(null));
            }
            JsonNode payload = envelope.path("payload");
            if ("event_callback".equals(payload.path("type").asText(""))) {
                ChannelMessage msg = toInboundMessage(channel, payload);
                if (msg != null) {
                    handler.onMessage(msg);
                }
            }
        }
    }

    private ChannelMessage toInboundMessage(Channel channel, JsonNode payload) {
        String eventType = payload.path("event").path("type").asText("");
        String text = payload.path("event").path("text").asText(null);
        if (!"message".equals(eventType) || text == null || text.isBlank()) {
            return null;
        }
        JsonNode event = payload.path("event");
        String chan = event.path("channel").asText(null);
        String ts = event.path("ts").asText(null);
        String user = event.path("user").asText(null);
        String eventId = payload.path("event_id").asText(null);
        // Nur normale Text-Nachrichten (keine Bot-/Bild-Nachrichten ohne text)
        String subtype = event.path("subtype").asText("");
        if (!subtype.isEmpty() && !"bot_message".equals(subtype)) {
            return null;
        }
        String senderId = subtype.isEmpty() ? user : event.path("bot_id").asText(user);
        return new ChannelMessage(UUID.randomUUID().toString(), channel.id(),
                eventId != null ? eventId : ts, MessageDirection.INBOUND,
                text, senderId, null, chan, null, Instant.now());
    }

    // --- HTTPS-Helfer ---

    private JsonNode postAuthorizedJson(String url, String payload, String token)
            throws IOException, ChannelException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new ChannelException("Slack-API-Fehler: HTTP " + response.statusCode());
        }
        try {
            return objectMapper.readTree(response.body());
        } catch (JacksonException e) {
            throw new ChannelException("Ungueltige Slack-Antwort.", e);
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
            throw new ChannelException("Slack-Token fehlt im Channel-Konfiguration.");
        }
        return token;
    }

    private String requireTokenSafe(Channel channel) {
        String token = configString(channel, CONFIG_TOKEN);
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Slack-Token fehlt im Channel-Konfiguration.");
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

    /**
     * Abstrakter Socket-Handle, der vom Connector geliefert und vom Adapter geschlossen wird.
     */
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

    /**
     * Kapselt den echten {@link SessionHandle} und den Empfangs-Thread für den Adapter.
     */
    private final class SocketClient implements SocketMessageHandler {
        private final Channel channel;
        private final InboundMessageHandler handler;
        private volatile SessionHandle handle;

        SocketClient(Channel channel, InboundMessageHandler handler) {
            this.channel = channel;
            this.handler = handler;
        }

        void attach(SessionHandle handle) {
            this.handle = handle;
        }

        @Override
        public void onMessage(String rawMessage) {
            handleSocketMessage(channel, handler, rawMessage, this);
        }

        @Override
        public void onClose() {
            log.info("Slack-Socket fuer Channel '{}' geschlossen.", channel.name());
        }

        void sendAck(String envelopeId) {
            if (handle != null) {
                String ack = "{\"envelope_id\":" + jsonQuote(envelopeId) + "}";
                try {
                    handle.send(ack);
                } catch (Exception e) {
                    log.warn("Slack-Ack konnte nicht gesendet werden: {}", e.getMessage());
                }
            }
        }
    }
}
