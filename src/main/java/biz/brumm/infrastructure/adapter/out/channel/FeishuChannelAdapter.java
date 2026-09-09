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
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Feishu-Channel-Adapter (P3-06).
 * <p>
 * Implementiert {@link ChannelAdapter} für <b>Feishu</b> (Lark) über Webhooks:
 * <ul>
 *   <li><b>Senden:</b> {@code POST} des JSON-Payloads {@code {"msg_type":"text","content":{"text":…}}}
 *       an den konfigurierten Incoming Webhook.</li>
 *   <li><b>Empfang:</b> push-basiert über die Feishu Event Subscription
 *       ({@code event_callback}) — der Adapter verifiziert den {@code token}
 *       und parst die Nachricht ({@code inboundFromWebhook}).</li>
 * </ul>
 * <p>
 * Erwartete Konfiguration im {@code Channel.config}:
 * <ul>
 *   <li>{@code webhookUrl} – Incoming-Webhook-URL (Pflicht zum Senden)</li>
 *   <li>{@code verifyToken} – Token für die Empfangs-Verifikation (optional; wenn nicht gesetzt,
 *       werden Event-Pushes akzeptiert)</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(prefix = "jclaw.channels", name = "enabled", havingValue = "true")
public class FeishuChannelAdapter implements ChannelAdapter {

    private static final Logger log = LoggerFactory.getLogger(FeishuChannelAdapter.class);
    private static final String CONFIG_WEBHOOK_URL = "webhookUrl";
    private static final String CONFIG_VERIFY_TOKEN = "verifyToken";
    private static final String CONTENT_TYPE = "application/json; charset=utf-8";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public FeishuChannelAdapter() {
        this(HttpClient.newHttpClient(), new ObjectMapper());
    }

    FeishuChannelAdapter(HttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public ChannelType channelType() {
        return ChannelType.FEISHU;
    }

    @Override
    public ChannelMessage send(Channel channel, ChannelMessage message) throws ChannelException {
        String webhookUrl = requireWebhookUrl(channel);
        if (message.content() == null || message.content().isBlank()) {
            throw new ChannelException("Nachrichteninhalt darf nicht leer sein.");
        }
        String payload;
        try {
            ObjectNode contentNode = objectMapper.createObjectNode();
            contentNode.put("text", message.content());
            ObjectNode root = objectMapper.createObjectNode();
            root.put("msg_type", "text");
            root.set("content", contentNode);
            payload = objectMapper.writeValueAsString(root);
        } catch (JacksonException e) {
            throw new ChannelException("Feishu-Payload konnte nicht erzeugt werden.", e);
        }

        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(webhookUrl))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", CONTENT_TYPE)
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new ChannelException("Feishu-Webhook-Fehler: HTTP " + response.statusCode());
            }
            String externalId = extractMessageId(response.body());
            String target = message.threadId() != null && !message.threadId().isBlank()
                    ? message.threadId() : message.senderId();
            log.info("Feishu-Nachricht gesendet (externalId={}): {}", externalId,
                    message.content().length() > 50
                            ? message.content().substring(0, 50) + "..." : message.content());
            return new ChannelMessage(UUID.randomUUID().toString(), channel.id(), externalId,
                    MessageDirection.OUTBOUND, message.content(), message.senderId(),
                    message.senderName(), target, message.sessionId(), Instant.now());
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ChannelException("Feishu-Webhook-Aufruf fehlgeschlagen: " + e.getMessage(), e);
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
     * Verifiziert den Token eines Event-Callback-Pushs gegen die Channel-Konfiguration.
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
     * Parst ein Feishu-Event-Callback-Payload in eine {@link ChannelMessage} (inbound).
     * Liefert {@code null}, wenn kein Text-Ereignis mit gueltigem Inhalt vorliegt.
     */
    public ChannelMessage inboundFromWebhook(Channel channel, String rawPayload) {
        JsonNode body;
        try {
            body = objectMapper.readTree(rawPayload);
        } catch (JacksonException e) {
            log.warn("Ungueltiger Feishu-Payload: {}", e.getMessage());
            return null;
        }
        if (!"event_callback".equals(body.path("type").asText(null))) {
            return null;
        }
        JsonNode event = body.path("event");
        if (event.isMissingNode() || event.isNull()) {
            return null;
        }
        JsonNode messageNode = event.path("message");
        if (messageNode.isMissingNode() || messageNode.isNull()) {
            return null;
        }
        String messageType = messageNode.path("message_type").asText(null);
        if (!"text".equals(messageType)) {
            return null;
        }
        String contentJson = messageNode.path("content").asText(null);
        if (contentJson == null || contentJson.isBlank()) {
            return null;
        }
        String text;
        try {
            text = objectMapper.readTree(contentJson).path("text").asText(null);
        } catch (JacksonException e) {
            return null;
        }
        if (text == null || text.isBlank()) {
            return null;
        }
        JsonNode senderIdNode = event.path("sender").path("sender_id");
        String senderId = senderIdNode.path("user_id").asText(null);
        if (senderId == null || senderId.isBlank()) {
            senderId = senderIdNode.path("open_id").asText(null);
        }
        if (senderId == null || senderId.isBlank()) {
            senderId = "unbekannt";
        }
        String chatId = messageNode.path("chat_id").asText(null);
        String messageId = messageNode.path("message_id").asText(null);
        return new ChannelMessage(UUID.randomUUID().toString(), channel.id(),
                messageId, MessageDirection.INBOUND, text, senderId, null, chatId, null, Instant.now());
    }

    private String extractMessageId(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return null;
        }
        try {
            JsonNode body = objectMapper.readTree(responseBody);
            if (body.path("code").asInt(-1) != 0) {
                return null;
            }
            String id = body.path("data").path("message_id").asText(null);
            return id != null && !id.isBlank() ? id : null;
        } catch (JacksonException e) {
            return null;
        }
    }

    private String requireWebhookUrl(Channel channel) throws ChannelException {
        String url = configString(channel, CONFIG_WEBHOOK_URL);
        if (url == null || url.isBlank()) {
            throw new ChannelException("Feishu-webhookUrl fehlt in der Channel-Konfiguration.");
        }
        return url;
    }

    private String configString(Channel channel, String key) {
        Object v = channel.config().get(key);
        return v == null ? null : String.valueOf(v);
    }
}