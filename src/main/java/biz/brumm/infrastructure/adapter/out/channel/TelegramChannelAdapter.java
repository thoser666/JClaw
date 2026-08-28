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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Telegram-Channel-Adapter (P3-02).
 * <p>
 * Implementiert {@link ChannelAdapter} für die Telegram Bot API über Long-Polling
 * ({@code getUpdates}) und {@code sendMessage}.
 * <p>
 * Erwartete Konfiguration im {@code Channel.config}:
 * <ul>
 *   <li>{@code token} – Bot-Token (Pflicht)</li>
 *   <li>{@code pollTimeoutSeconds} – Long-Polling-Timeout (optional, Standard 30)</li>
 *   <li>{@code baseUrl} – Telegram-API-Basis-URL (optional, Standard https://api.telegram.org)</li>
 * </ul>
 * Empfang: Gestartet über {@link #startReceiving(Channel, InboundMessageHandler)} als Long-Polling
 * in einem Daemon-Thread; Nachrichten werden an den Handler delegiert.
 */
@Component
@ConditionalOnProperty(prefix = "jclaw.channels", name = "enabled", havingValue = "true")
public class TelegramChannelAdapter implements ChannelAdapter {

    private static final Logger log = LoggerFactory.getLogger(TelegramChannelAdapter.class);
    private static final String DEFAULT_BASE_URL = "https://api.telegram.org";
    private static final String CONFIG_TOKEN = "token";
    private static final String CONFIG_POLL_TIMEOUT = "pollTimeoutSeconds";
    private static final String CONFIG_BASE_URL = "baseUrl";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private volatile Thread workerThread;

    public TelegramChannelAdapter() {
        this(HttpClient.newHttpClient(), new ObjectMapper());
    }

    TelegramChannelAdapter(HttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public ChannelType channelType() {
        return ChannelType.TELEGRAM;
    }

    @Override
    public ChannelMessage send(Channel channel, ChannelMessage message) throws ChannelException {
        String token = requireToken(channel);
        if (message.content() == null || message.content().isBlank()) {
            throw new ChannelException("Nachrichteninhalt darf nicht leer sein.");
        }
        String chatId = resolveChatId(message);
        if (chatId == null || chatId.isBlank()) {
            throw new ChannelException("Kein chatId (threadId/senderId) fuer die Nachricht vorhanden.");
        }

        String url = baseUrl(channel) + "/bot" + token + "/sendMessage";
        JsonNode json;
        try {
            json = postJson(url, buildSendPayload(chatId, message.content()), channel);
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ChannelException("Telegram-API-Aufruf fehlgeschlagen: " + e.getMessage(), e);
        }
        if (!json.path("ok").asBoolean(false)) {
            throw new ChannelException("Telegram sendMessage fehlgeschlagen: "
                    + json.path("description").asText(json.toString()));
        }
        JsonNode result = json.path("result");
        String externalId = result.path("message_id").asText(null);
        return ChannelMessage.outbound(channel.id(), message.content(),
                chatId, message.sessionId()).withExternalId(externalId);
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
            throw new IllegalArgumentException("Telegram-Token fehlt im Channel-Konfiguration.");
        }
        Thread t = new Thread(() -> runPollingLoop(channel, handler),
                "telegram-poll-" + channel.id());
        t.setDaemon(true);
        workerThread = t;
        t.start();
        log.info("Telegram-Long-Polling fuer Channel '{}' gestartet.", channel.name());
    }

    @Override
    public void stopReceiving(Channel channel) {
        Thread t = workerThread;
        workerThread = null;
        if (t != null && t.isAlive()) {
            t.interrupt();
            log.info("Telegram-Long-Polling fuer Channel '{}' gestoppt.", channel.name());
        }
    }

    // --- Internes Long-Polling ---

    private void runPollingLoop(Channel channel, InboundMessageHandler handler) {
        int offset = 0;
        while (!Thread.currentThread().isInterrupted()) {
            try {
                OffsetUpdate result =
                        pollRaw(channel, offset, pollTimeout(channel), baseUrl(channel));
                if (result.maxUpdateId > offset) {
                    offset = result.maxUpdateId + 1;
                }
                for (ChannelMessage m : result.messages) {
                    handler.onMessage(m);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("Telegram-Polling-Fehler auf '{}': {}", channel.name(), e.getMessage());
                sleepQuietly(Duration.ofSeconds(5));
            }
        }
        log.info("Telegram-Long-Polling fuer Channel '{}' beendet.", channel.name());
    }

    /**
     * Führt einen getUpdates-Aufruf aus (für Tests: liefert die Nachrichten).
     */
    List<ChannelMessage> poll(Channel channel, int offset, int pollTimeoutSeconds, String baseUrl)
            throws IOException, InterruptedException, ChannelException {
        return pollRaw(channel, offset, pollTimeoutSeconds, baseUrl).messages();
    }

    private OffsetUpdate pollRaw(Channel channel, int offset, int pollTimeoutSeconds, String baseUrl)
            throws IOException, InterruptedException, ChannelException {
        String token = requireToken(channel);
        String url = baseUrl + "/bot" + token + "/getUpdates";
        String payload = "{\"offset\":" + offset + ",\"timeout\":" + pollTimeoutSeconds
                + ",\"allowed_updates\":[\"message\"]}";
        JsonNode json = postJson(url, payload, channel);
        if (!json.path("ok").asBoolean(false)) {
            throw new ChannelException("Telegram getUpdates fehlgeschlagen: "
                    + json.path("description").asText(json.toString()));
        }
        List<ChannelMessage> messages = new ArrayList<>();
        int maxUpdateId = 0;
        for (JsonNode update : json.path("result")) {
            int updateId = update.path("update_id").asInt(0);
            if (updateId > maxUpdateId) {
                maxUpdateId = updateId;
            }
            ChannelMessage m = toInboundMessage(channel, update);
            if (m != null) {
                messages.add(m);
            }
        }
        return new OffsetUpdate(messages, maxUpdateId);
    }

    private record OffsetUpdate(List<ChannelMessage> messages, int maxUpdateId) { }

    private JsonNode postJson(String url, String payload, Channel channel)
            throws IOException, ChannelException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(pollTimeout(channel) + 10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new ChannelException("Telegram-API-Fehler: HTTP " + response.statusCode());
        }
        try {
            return objectMapper.readTree(response.body());
        } catch (JacksonException e) {
            throw new ChannelException("Ungueltige Telegram-Antwort.", e);
        }
    }

    private ChannelMessage toInboundMessage(Channel channel, JsonNode update) {
        JsonNode msg = update.path("message");
        if (msg.isMissingNode() || msg.isNull()) {
            return null;
        }
        String text = msg.path("text").asText(null);
        if (text == null || text.isBlank()) {
            return null;
        }
        String chatId = msg.path("chat").path("id").asText(null);
        String messageId = msg.path("message_id").asText(null);
        String senderId = msg.path("from").path("id").asText(null);
        String senderName = configString(msg.path("from").path("first_name"), null);
        if (senderName == null) {
            senderName = configString(msg.path("from").path("username"), null);
        }
        return new ChannelMessage(UUID.randomUUID().toString(), channel.id(),
                messageId, MessageDirection.INBOUND, text, senderId, senderName,
                chatId, null, Instant.now());
    }

    private String buildSendPayload(String chatId, String content) {
        return "{\"chat_id\":" + jsonQuote(chatId) + ",\"text\":" + jsonQuote(content) + "}";
    }

    private String jsonQuote(String value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException e) {
            return "\"\"";
        }
    }

    private String resolveChatId(ChannelMessage message) {
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
            throw new ChannelException("Telegram-Token fehlt im Channel-Konfiguration.");
        }
        return token;
    }

    private int pollTimeout(Channel channel) {
        Object v = channel.config().get(CONFIG_POLL_TIMEOUT);
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

    private static String configString(JsonNode node, String fallback) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return fallback;
        }
        return node.isValueNode() ? node.asText(fallback) : fallback;
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
