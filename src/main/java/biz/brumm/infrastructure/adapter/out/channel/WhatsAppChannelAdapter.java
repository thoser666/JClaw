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
 * WhatsApp-Channel-Adapter (P3-05) über die Meta WhatsApp Cloud API.
 * <p>
 * Implementiert {@link ChannelAdapter} für WhatsApp:
 * <ul>
 *   <li><b>Senden:</b> {@code POST {graphUrl}/{phoneNumberId}/messages} (Graph API)</li>
 *   <li><b>Empfang:</b> push-basiert über den Meta-Webhook — Meta liefert Nachrichten per
 *       Webhook an JClaw; der Adapter stellt die Verifikation ({@code verifyWebhook})
 *       und das Parsen des Webhook-Payloads ({@code inboundFromWebhook}) bereit.</li>
 * </ul>
 * <p>
 * Erwartete Konfiguration im {@code Channel.config}:
 * <ul>
 *   <li>{@code token} – Meta-Access-Token (Pflicht, System-User-Token der WhatsApp-App)</li>
 *   <li>{@code phoneNumberId} – WhatsApp Business Phone Number ID (Pflicht)</li>
 *   <li>{@code graphUrl} – Graph-API-Basis-URL (optional, Standard https://graph.facebook.com/v21.0)</li>
 *   <li>{@code verifyToken} – Webhook-Verifizierungs-Token (optional, für den Meta-GET-Handshake)</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(prefix = "jclaw.channels", name = "enabled", havingValue = "true")
public class WhatsAppChannelAdapter implements ChannelAdapter {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppChannelAdapter.class);
    private static final String DEFAULT_GRAPH_URL = "https://graph.facebook.com/v21.0";
    private static final String CONFIG_TOKEN = "token";
    private static final String CONFIG_PHONE_NUMBER_ID = "phoneNumberId";
    private static final String CONFIG_GRAPH_URL = "graphUrl";
    private static final String CONFIG_VERIFY_TOKEN = "verifyToken";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public WhatsAppChannelAdapter() {
        this(HttpClient.newHttpClient(), new ObjectMapper());
    }

    WhatsAppChannelAdapter(HttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public ChannelType channelType() {
        return ChannelType.WHATSAPP;
    }

    @Override
    public ChannelMessage send(Channel channel, ChannelMessage message) throws ChannelException {
        String token = requireToken(channel);
        String phoneNumberId = requirePhoneNumberId(channel);
        if (message.content() == null || message.content().isBlank()) {
            throw new ChannelException("Nachrichteninhalt darf nicht leer sein.");
        }
        String to = resolveRecipient(message);
        if (to == null || to.isBlank()) {
            throw new ChannelException("Kein WhatsApp-Empfaenger (threadId/senderId) fuer die Nachricht vorhanden.");
        }

        String url = graphUrl(channel) + "/" + phoneNumberId + "/messages";
        String payload = "{\"messaging_product\":\"whatsapp\",\"to\":" + jsonQuote(to)
                + ",\"text\":{\"body\":" + jsonQuote(message.content()) + "}}";
        JsonNode json;
        try {
            json = postAuthorizedJson(url, payload, token);
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ChannelException("WhatsApp-API-Aufruf fehlgeschlagen: " + e.getMessage(), e);
        }
        String id = json.path("messages").path(0).path("id").asText(null);
        return ChannelMessage.outbound(channel.id(), message.content(),
                to, message.sessionId()).withExternalId(id);
    }

    @Override
    public boolean isAvailable(Channel channel) {
        if (channel == null || !channel.enabled()) {
            return false;
        }
        return requireTokenSafe(channel) != null && phoneNumberId(channel) != null;
    }

    /**
     * Verifiziert den Meta-Webhook-Handshake (GET mit {@code hub.mode} etc.).
     *
     * @return die {@code hub.challenge}, wenn die Verifikation erfolgreich ist, sonst {@code null}.
     */
    public String verifyWebhook(Channel channel, String mode, String verifyTokenIn, String challenge) {
        String expected = verifyToken(channel);
        if (expected == null || expected.isBlank() || expected.equals(verifyTokenIn)) {
            if ("subscribe".equals(mode) && challenge != null) {
                return challenge;
            }
        }
        return null;
    }

    /**
     * Parst einen eingehenden Meta-Webhook-Payload (Status/Message) in eine
     * {@link ChannelMessage} (inbound). Liefert {@code null}, wenn keine Text-Nachricht enthalten ist
     * (z. B. Status-Updates, Medien ohne Text, eigene Nachrichten).
     */
    public ChannelMessage inboundFromWebhook(Channel channel, String rawPayload) {
        JsonNode body;
        try {
            body = objectMapper.readTree(rawPayload);
        } catch (JacksonException e) {
            log.warn("Ungueltiger WhatsApp-Webhook-Payload: {}", e.getMessage());
            return null;
        }
        JsonNode value = firstMessageValue(body);
        if (value == null) {
            return null;
        }
        JsonNode messages = value.path("messages");
        if (!messages.isArray() || messages.isEmpty()) {
            return null;
        }
        JsonNode msg = messages.get(0);
        String from = msg.path("from").asText(null);
        if (from == null || from.isBlank()) {
            return null;
        }
        String msgId = msg.path("id").asText(null);
        String text = msg.path("text").path("body").asText(null);
        if (text == null || text.isBlank()) {
            return null;
        }
        String senderName = firstProfileName(value);
        return new ChannelMessage(UUID.randomUUID().toString(), channel.id(),
                msgId, MessageDirection.INBOUND, text, from, senderName, from, null, Instant.now());
    }

    private JsonNode firstMessageValue(JsonNode body) {
        JsonNode entries = body.path("entry");
        if (!entries.isArray() || entries.isEmpty()) {
            return null;
        }
        JsonNode changes = entries.get(0).path("changes");
        if (!changes.isArray() || changes.isEmpty()) {
            return null;
        }
        return changes.get(0).path("value");
    }

    private String firstProfileName(JsonNode value) {
        JsonNode contacts = value.path("contacts");
        if (contacts.isArray() && !contacts.isEmpty()) {
            String name = contacts.get(0).path("profile").path("name").asText(null);
            if (name != null && !name.isBlank()) {
                return name;
            }
        }
        return null;
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
            throw new ChannelException("WhatsApp-API-Fehler: HTTP " + response.statusCode());
        }
        try {
            return objectMapper.readTree(response.body());
        } catch (JacksonException e) {
            throw new ChannelException("Ungueltige WhatsApp-Antwort.", e);
        }
    }

    private String resolveRecipient(ChannelMessage message) {
        if (message.threadId() != null && !message.threadId().isBlank()) {
            return message.threadId();
        }
        if (message.senderId() != null && !message.senderId().isBlank()) {
            return message.senderId();
        }
        return null;
    }

    private String requireToken(Channel channel) throws ChannelException {
        String token = requireTokenSafe(channel);
        if (token == null) {
            throw new ChannelException("WhatsApp-Token fehlt im Channel-Konfiguration.");
        }
        return token;
    }

    private String requireTokenSafe(Channel channel) {
        String token = configString(channel, CONFIG_TOKEN);
        return token == null || token.isBlank() ? null : token;
    }

    private String requirePhoneNumberId(Channel channel) throws ChannelException {
        String id = phoneNumberId(channel);
        if (id == null) {
            throw new ChannelException("WhatsApp-phoneNumberId fehlt im Channel-Konfiguration.");
        }
        return id;
    }

    private String phoneNumberId(Channel channel) {
        String id = configString(channel, CONFIG_PHONE_NUMBER_ID);
        return id == null || id.isBlank() ? null : id;
    }

    private String verifyToken(Channel channel) {
        return configString(channel, CONFIG_VERIFY_TOKEN);
    }

    private String graphUrl(Channel channel) {
        String url = configString(channel, CONFIG_GRAPH_URL);
        if (url == null || url.isBlank()) {
            return DEFAULT_GRAPH_URL;
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
}
