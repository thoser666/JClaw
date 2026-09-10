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
import java.util.UUID;

/**
 * Synology-Chat-Channel-Adapter (P3-06).
 * <p>
 * Implementiert {@link ChannelAdapter} für **Synology Chat** über Webhooks:
 * <ul>
 *   <li><b>Senden:</b> {@code POST} des JSON-Payloads {@code {"text":…,"channel_name":…}}
 *       an den konfigurierten <b>Incoming Webhook</b> (URL der Form
 *       {@code https://<synology>:<port>/webhook/<hookId>}).</li>
 *   <li><b>Empfang:</b> push-basiert über den <b>Outgoing Webhook</b> — Synology Chat liefert
 *       Nachrichten per {@code POST} an JClaw; der Adapter verifiziert den im Payload
 *       enthaltenen {@code token} ({@code verifyWebhook}) und parst die Nachricht
 *       ({@code inboundFromWebhook}).</li>
 * </ul>
 * <p>
 * Erwartete Konfiguration im {@code Channel.config}:
 * <ul>
 *   <li>{@code incomingWebhookUrl} – Incoming-Webhook-URL (Pflicht zum Senden)</li>
 *   <li>{@code outgoingWebhookToken} – Token des Outgoing-Webhooks (optional, fuer die
 *       Empfangs-Verifikation; wenn nicht gesetzt, werden Pushes akzeptiert)</li>
 *   <li>{@code channel} – Standard-Channel-Override (optional, z. B. {@code #general})</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(prefix = "jclaw.channels", name = "enabled", havingValue = "true")
public class SynologyChatChannelAdapter implements ChannelAdapter {

    private static final Logger log = LoggerFactory.getLogger(SynologyChatChannelAdapter.class);
    private static final String CONFIG_INCOMING_WEBHOOK_URL = "incomingWebhookUrl";
    private static final String CONFIG_OUTGOING_WEBHOOK_TOKEN = "outgoingWebhookToken";
    private static final String CONFIG_CHANNEL = "channel";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public SynologyChatChannelAdapter() {
        this(HttpClient.newHttpClient(), new ObjectMapper());
    }

    SynologyChatChannelAdapter(HttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public ChannelType channelType() {
        return ChannelType.SYNOSOLOGY_CHAT;
    }

    @Override
    public ChannelMessage send(Channel channel, ChannelMessage message) throws ChannelException {
        String webhookUrl = requireWebhookUrl(channel);
        if (message.content() == null || message.content().isBlank()) {
            throw new ChannelException("Nachrichteninhalt darf nicht leer sein.");
        }
        String target = resolveTarget(channel, message);
        String payload;
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("text", message.content());
            if (target != null && !target.isBlank()) {
                body.put("channel_name", target);
            }
            payload = objectMapper.writeValueAsString(body);
        } catch (JacksonException e) {
            throw new ChannelException("Synology-Chat-Payload konnte nicht erzeugt werden.", e);
        }

        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(webhookUrl))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new ChannelException("Synology-Chat-Webhook-Fehler: HTTP " + response.statusCode());
            }
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ChannelException("Synology-Chat-Webhook-Aufruf fehlgeschlagen: " + e.getMessage(), e);
        }
        log.info("Synology-Chat-Nachricht gesendet (target={}): {}", target,
                message.content().length() > 50
                        ? message.content().substring(0, 50) + "..." : message.content());
        return ChannelMessage.outbound(channel.id(), message.content(), target, message.sessionId());
    }

    @Override
    public boolean isAvailable(Channel channel) {
        if (channel == null || !channel.enabled()) {
            return false;
        }
        String url = configString(channel, CONFIG_INCOMING_WEBHOOK_URL);
        return url != null && !url.isBlank();
    }

    /**
     * Verifiziert den Token eines Outgoing-Webhook-Pushs gegen die Channel-Konfiguration.
     *
     * @return {@code true}, wenn der Token passt – oder keine Verifikation konfiguriert ist.
     */
    public boolean verifyWebhook(Channel channel, String token) {
        String expected = configString(channel, CONFIG_OUTGOING_WEBHOOK_TOKEN);
        if (expected == null || expected.isBlank()) {
            return true;
        }
        return expected.equals(token);
    }

    /**
     * Parst einen Outgoing-Webhook-Payload in eine {@link ChannelMessage} (inbound).
     * Liefert {@code null}, wenn der Payload ungueltig ist oder keinen Text enthaelt.
     */
    public ChannelMessage inboundFromWebhook(Channel channel, String rawPayload) {
        JsonNode body;
        try {
            body = objectMapper.readTree(rawPayload);
        } catch (JacksonException e) {
            log.warn("Ungueltiger Synology-Chat-Webhook-Payload: {}", e.getMessage());
            return null;
        }
        String text = body.path("text").asText(null);
        if (text == null || text.isBlank()) {
            return null;
        }
        String senderId = body.path("user_id").asText(null);
        if (senderId == null || senderId.isBlank()) {
            senderId = "unbekannt";
        }
        String senderName = body.path("user_name").asText(null);
        if (senderName == null || senderName.isBlank()) {
            senderName = senderId;
        }
        String channelId = body.path("channel_id").asText(null);
        if (channelId == null || channelId.isBlank()) {
            channelId = body.path("channel_name").asText(null);
        }
        String postId = body.path("post_id").asText(null);
        return new ChannelMessage(UUID.randomUUID().toString(), channel.id(),
                postId, MessageDirection.INBOUND, text, senderId, senderName, channelId, null, Instant.now());
    }

    private String resolveTarget(Channel channel, ChannelMessage message) {
        if (message.threadId() != null && !message.threadId().isBlank()) {
            return message.threadId();
        }
        if (message.senderId() != null && !message.senderId().isBlank()) {
            return message.senderId();
        }
        return configString(channel, CONFIG_CHANNEL);
    }

    private String requireWebhookUrl(Channel channel) throws ChannelException {
        String url = configString(channel, CONFIG_INCOMING_WEBHOOK_URL);
        if (url == null || url.isBlank()) {
            throw new ChannelException("Synology-Chat-incomingWebhookUrl fehlt in der Channel-Konfiguration.");
        }
        return url;
    }

    private String configString(Channel channel, String key) {
        Object v = channel.config().get(key);
        return v == null ? null : String.valueOf(v);
    }
}