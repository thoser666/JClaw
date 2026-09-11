package biz.brumm.infrastructure.adapter.out.channel;

import biz.brumm.domain.model.Channel;
import biz.brumm.domain.model.ChannelMessage;
import biz.brumm.domain.model.ChannelType;
import biz.brumm.domain.model.MessageDirection;
import biz.brumm.domain.port.out.ChannelAdapter;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * X-(Twitter)-DM-Channel-Adapter (P3-06).
 * <p>
 * Implementiert {@link ChannelAdapter} für **X (Twitter) Direct Messages** über die
 * X-API-v2- (Direct-Message-)Endpunkte:
 * <ul>
 *   <li><b>Senden:</b> {@code POST /2/dm_conversations/with/{participantId}/messages}
 *       mit {@code {"text": …}} und {@code Authorization: Bearer <token>}.</li>
 *   <li><b>Empfang:</b> Polling über {@code GET /2/dm_events} (DM-Events des authentifizierten
 *       Kontos, neueste zuerst) in einem Daemon-Thread; Duplikate werden über die Event-Ids
 *       gefiltert und Events in chronologischer Reihenfolge (aelteste zuerst) geliefert.</li>
 * </ul>
 * <p>
 * Erwartete Konfiguration im {@code Channel.config}:
 * <ul>
 *   <li>{@code token} – OAuth-2.0-User-Context-Token (Pflicht)</li>
 *   <li>{@code baseUrl} – X-API-Basis-URL (optional, Standard https://api.x.com/2)</li>
 *   <li>{@code pollIntervalSeconds} – Polling-Intervall (optional, Standard 30)</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(prefix = "jclaw.channels", name = "enabled", havingValue = "true")
public class XChannelAdapter implements ChannelAdapter {

    private static final Logger log = LoggerFactory.getLogger(XChannelAdapter.class);
    private static final String DEFAULT_BASE_URL = "https://api.x.com/2";
    private static final String CONFIG_TOKEN = "token";
    private static final String CONFIG_BASE_URL = "baseUrl";
    private static final String CONFIG_POLL_INTERVAL = "pollIntervalSeconds";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final java.util.Set<String> seenEventIds = ConcurrentHashMap.newKeySet();
    private volatile Thread workerThread;

    public XChannelAdapter() {
        this(HttpClient.newHttpClient(), new ObjectMapper());
    }

    XChannelAdapter(HttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public ChannelType channelType() {
        return ChannelType.X;
    }

    @Override
    public ChannelMessage send(Channel channel, ChannelMessage message) throws ChannelException {
        String token = requireToken(channel);
        if (message.content() == null || message.content().isBlank()) {
            throw new ChannelException("Nachrichteninhalt darf nicht leer sein.");
        }
        String participantId = resolveParticipantId(message);
        if (participantId == null || participantId.isBlank()) {
            throw new ChannelException("Kein Teilnehmer (threadId/senderId) fuer die Nachricht vorhanden.");
        }
        String payload;
        try {
            payload = objectMapper.writeValueAsString(Map.of("text", message.content()));
        } catch (JacksonException e) {
            throw new ChannelException("X-DM-Payload konnte nicht erzeugt werden.", e);
        }
        try {
            String url = baseUrl(channel) + "/dm_conversations/with/" + participantId + "/messages";
            HttpResponse<String> response = postJson(url, payload, token);
            if (response.statusCode() >= 400) {
                throw new ChannelException("X-API-Fehler: HTTP " + response.statusCode());
            }
            String externalId = null;
            try {
                String responseBody = response.body();
                if (responseBody != null && !responseBody.isBlank()) {
                    externalId = objectMapper.readTree(responseBody).path("data").path("id").asText(null);
                }
            } catch (JacksonException e) {
                // Antwort ohne parsbare data.id → externalId bleibt null
            }
            log.info("X-DM gesendet (externalId={}): {}", externalId,
                    message.content().length() > 50
                            ? message.content().substring(0, 50) + "..." : message.content());
            return new ChannelMessage(UUID.randomUUID().toString(), channel.id(), externalId,
                    MessageDirection.OUTBOUND, message.content(), message.senderId(),
                    message.senderName(), participantId, message.sessionId(), Instant.now());
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ChannelException("X-API-Aufruf fehlgeschlagen: " + e.getMessage(), e);
        }
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
        String token = configString(channel, CONFIG_TOKEN);
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("X-Token fehlt in der Channel-Konfiguration.");
        }
        Thread t = new Thread(() -> runPollingLoop(channel, handler), "x-poll-" + channel.id());
        t.setDaemon(true);
        workerThread = t;
        t.start();
        log.info("X-DM-Polling fuer Channel '{}' gestartet.", channel.name());
    }

    @Override
    public void stopReceiving(Channel channel) {
        Thread t = workerThread;
        workerThread = null;
        if (t != null && t.isAlive()) {
            t.interrupt();
            log.info("X-DM-Polling fuer Channel '{}' gestoppt.", channel.name());
        }
    }

    // --- Internes DM-Polling ---

    /**
     * Führt einen dm_events-Aufruf aus (für Tests: liefert die neuen Nachrichten).
     * Gibt gesehene Events nur beim ersten Mal zurück (Deduplizierung über Event-Ids).
     */
    List<ChannelMessage> poll(Channel channel, String baseUrl)
            throws IOException, InterruptedException, ChannelException {
        String token = requireToken(channel);
        HttpResponse<String> response = getJson(baseUrl + "/dm_events?max_results=100", token);
        if (response.statusCode() >= 400) {
            throw new ChannelException("X-API-Fehler: HTTP " + response.statusCode());
        }
        JsonNode body;
        try {
            body = objectMapper.readTree(response.body());
        } catch (JacksonException e) {
            throw new ChannelException("Ungueltige X-Antwort.", e);
        }
        List<ChannelMessage> messages = new ArrayList<>();
        for (JsonNode event : body.path("data")) {
            ChannelMessage m = toInboundMessage(channel, event);
            if (m != null) {
                messages.add(m);
            }
        }
        Collections.reverse(messages);
        List<ChannelMessage> unseen = new ArrayList<>();
        for (ChannelMessage m : messages) {
            String id = m.externalId();
            if (id != null && seenEventIds.contains(id)) {
                continue;
            }
            if (id != null) {
                seenEventIds.add(id);
            }
            unseen.add(m);
        }
        return unseen;
    }

    private void runPollingLoop(Channel channel, InboundMessageHandler handler) {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                for (ChannelMessage m : poll(channel, baseUrl(channel))) {
                    handler.onMessage(m);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("X-DM-Polling-Fehler auf '{}': {}", channel.name(), e.getMessage());
                sleepQuietly(Duration.ofSeconds(5));
            }
            sleepQuietly(Duration.ofSeconds(pollInterval(channel)));
        }
        log.info("X-DM-Polling fuer Channel '{}' beendet.", channel.name());
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

    private ChannelMessage toInboundMessage(Channel channel, JsonNode event) {
        if (event == null || event.isNull() || event.isMissingNode()) {
            return null;
        }
        if (!"MessageCreate".equals(event.path("event_type").asText(null))) {
            return null;
        }
        String text = event.path("text").asText(null);
        if (text == null || text.isBlank()) {
            return null;
        }
        String senderId = event.path("sender_id").asText(null);
        if (senderId == null || senderId.isBlank()) {
            senderId = "unbekannt";
        }
        String dmConversationId = event.path("dm_conversation_id").asText(null);
        String eventId = event.path("id").asText(null);
        return new ChannelMessage(UUID.randomUUID().toString(), channel.id(), eventId,
                MessageDirection.INBOUND, text, senderId, null, dmConversationId, null, Instant.now());
    }

    private String resolveParticipantId(ChannelMessage message) {
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
            throw new ChannelException("X-Token fehlt in der Channel-Konfiguration.");
        }
        return token;
    }

    private int pollInterval(Channel channel) {
        Object v = channel.config().get(CONFIG_POLL_INTERVAL);
        if (v instanceof Number n) {
            return Math.max(1, n.intValue());
        }
        return 30;
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

    private static void sleepQuietly(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}