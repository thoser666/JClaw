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
import java.util.UUID;

/**
 * Google-Chat-Channel-Adapter (P3-06).
 * <p>
 * Implementiert {@link ChannelAdapter} für **Google Chat** über Webhooks:
 * <ul>
 *   <li><b>Senden:</b> {@code POST} des JSON-Payloads {@code {"text": …}} an den Incoming-Webhook
 *       (Webhook-URL der Form {@code https://chat.googleapis.com/v1/spaces/…/messages?key=…&token=…}).</li>
 *   <li><b>Empfang:</b> push-basiert über die Google-Chat-Ereignis-Webhooks (Nachrichten, die eine
 *       Erwähnung des Bots enthalten, werden als {@code POST} an JClaw geliefert) — der Adapter
 *       verifiziert den optionalen Token ({@code verifyWebhook}) und parst die Nachricht
 *       ({@code inboundFromWebhook}).</li>
 * </ul>
 * <p>
 * Erwartete Konfiguration im {@code Channel.config}:
 * <ul>
 *   <li>{@code webhookUrl} – Incoming-Webhook-URL (Pflicht zum Senden)</li>
 *   <li>{@code verifyToken} – Token für die Empfangs-Verifikation (optional; wenn nicht gesetzt,
 *       werden Ereignis-Pushes akzeptiert)</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(prefix = "jclaw.channels", name = "enabled", havingValue = "true")
public class GoogleChatChannelAdapter implements ChannelAdapter {

    private static final Logger log = LoggerFactory.getLogger(GoogleChatChannelAdapter.class);
    private static final String CONFIG_WEBHOOK_URL = "webhookUrl";
    private static final String CONFIG_VERIFY_TOKEN = "verifyToken";
    private static final java.util.regex.Pattern MENTION_PREFIX =
            java.util.regex.Pattern.compile("^\\s*@\\S+\\s*");

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public GoogleChatChannelAdapter() {
        this(HttpClient.newHttpClient(), new ObjectMapper());
    }

    GoogleChatChannelAdapter(HttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public ChannelType channelType() {
        return ChannelType.GOOGLE_CHAT;
    }

    @Override
    public ChannelMessage send(Channel channel, ChannelMessage message) throws ChannelException {
        String webhookUrl = requireWebhookUrl(channel);
        if (message.content() == null || message.content().isBlank()) {
            throw new ChannelException("Nachrichteninhalt darf nicht leer sein.");
        }
        String payload;
        try {
            payload = objectMapper.writeValueAsString(
                    java.util.Map.of("text", message.content()));
        } catch (JacksonException e) {
            throw new ChannelException("Google-Chat-Payload konnte nicht erzeugt werden.", e);
        }

        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(webhookUrl))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new ChannelException("Google-Chat-Webhook-Fehler: HTTP " + response.statusCode());
            }
            String externalId = extractMessageName(response.body());
            String target = message.threadId() != null && !message.threadId().isBlank()
                    ? message.threadId() : message.senderId();
            log.info("Google-Chat-Nachricht gesendet (externalId={}): {}", externalId,
                    message.content().length() > 50
                            ? message.content().substring(0, 50) + "..." : message.content());
            return new ChannelMessage(UUID.randomUUID().toString(), channel.id(), externalId,
                    MessageDirection.OUTBOUND, message.content(), message.senderId(),
                    message.senderName(), target, message.sessionId(), Instant.now());
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ChannelException("Google-Chat-Webhook-Aufruf fehlgeschlagen: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean isAvailable(Channel channel) {
        if (channel == null || !channel.enabled()) {
            return false;
        }
        String url = configString(channel, CONFIG_WEBHOOK_URL);
        return url != null && !url.isBlank();
    }

    /**
     * Verifiziert den Token eines Ereignis-Pushs gegen die Channel-Konfiguration.
     *
     * @return {@code true}, wenn der Token passt – oder keine Verifikation konfiguriert ist.
     */
    public boolean verifyWebhook(Channel channel, String token) {
        String expected = configString(channel, CONFIG_VERIFY_TOKEN);
        if (expected == null || expected.isBlank()) {
            return true;
        }
        return expected.equals(token);
    }

    /**
     * Parst ein Google-Chat-Ereignis (Nachricht) in eine {@link ChannelMessage} (inbound).
     * Liefert {@code null}, wenn kein Nachrichten-Ereignis mit Text vorliegt.
     */
    public ChannelMessage inboundFromWebhook(Channel channel, String rawPayload) {
        JsonNode body;
        try {
            body = objectMapper.readTree(rawPayload);
        } catch (JacksonException e) {
            log.warn("Ungueltiger Google-Chat-Payload: {}", e.getMessage());
            return null;
        }
        JsonNode message = body.path("message");
        if (message.isMissingNode() || message.isNull()) {
            return null;
        }
        String text = message.path("argumentText").asText(null);
        if (text == null || text.isBlank()) {
            String fullText = message.path("text").asText(null);
            if (fullText == null || fullText.isBlank()) {
                return null;
            }
            text = MENTION_PREFIX.matcher(fullText).replaceFirst("").strip();
        }
        if (text.isBlank()) {
            return null;
        }
        String senderId = message.path("sender").path("name").asText(null);
        if (senderId == null || senderId.isBlank()) {
            senderId = "unbekannt";
        }
        String senderName = message.path("sender").path("displayName").asText(null);
        if (senderName == null || senderName.isBlank()) {
            senderName = senderId;
        }
        String spaceName = message.path("space").path("name").asText(null);
        String messageName = message.path("name").asText(null);
        return new ChannelMessage(UUID.randomUUID().toString(), channel.id(),
                messageName, MessageDirection.INBOUND, text, senderId, senderName, spaceName, null, Instant.now());
    }

    private String extractMessageName(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return null;
        }
        try {
            JsonNode body = objectMapper.readTree(responseBody);
            String name = body.path("name").asText(null);
            return name != null && !name.isBlank() ? name : null;
        } catch (JacksonException e) {
            return null;
        }
    }

    private String requireWebhookUrl(Channel channel) throws ChannelException {
        String url = configString(channel, CONFIG_WEBHOOK_URL);
        if (url == null || url.isBlank()) {
            throw new ChannelException("Google-Chat-webhookUrl fehlt in der Channel-Konfiguration.");
        }
        return url;
    }

    private String configString(Channel channel, String key) {
        Object v = channel.config().get(key);
        return v == null ? null : String.valueOf(v);
    }
}