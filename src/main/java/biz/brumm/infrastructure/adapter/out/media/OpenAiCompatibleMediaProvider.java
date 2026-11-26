package biz.brumm.infrastructure.adapter.out.media;

import biz.brumm.config.MediaProviderProperties;
import biz.brumm.domain.port.out.MediaProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * OpenAI-kompatibler Speech/Media-Provider (P4-03).
 * <p>
 * Spricht die OpenAI Audio-API ({@code /v1/audio/speech} und
 * {@code /v1/audio/transcriptions}) über {@code java.net.http} an.
 * Wird nur beansprucht, wenn {@code jclaw.media.provider.enabled=true}
 * und {@code jclaw.media.provider.provider=openai}.
 */
@Component
@ConditionalOnProperty(prefix = "jclaw.media.provider", name = "enabled", havingValue = "true")
public class OpenAiCompatibleMediaProvider implements MediaProvider {

    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final MediaProviderProperties properties;

    public OpenAiCompatibleMediaProvider(MediaProviderProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        this.mapper = new ObjectMapper();
    }

    @Override
    public String providerName() {
        return properties.provider();
    }

    @Override
    public boolean isAvailable() {
        return properties.enabled()
                && "openai".equalsIgnoreCase(properties.provider())
                && properties.apiKey() != null && !properties.apiKey().isBlank();
    }

    @Override
    public byte[] textToSpeech(String text, String voice, String format) throws MediaProvider.MediaProviderException {
        if (!isAvailable()) {
            throw new MediaProvider.MediaProviderException("Media-Provider nicht verfügbar (jclaw.media.provider.enabled).");
        }
        if (text == null || text.isBlank()) {
            throw new MediaProvider.MediaProviderException("Text darf nicht leer sein.");
        }
        String fmt = format == null || format.isBlank() ? properties.ttsFormat() : format;
        String voiceName = voice == null || voice.isBlank() ? properties.voice() : voice;

        ObjectNode req = mapper.createObjectNode();
        req.put("model", properties.model());
        req.put("input", text);
        if (voiceName != null && !voiceName.isBlank()) {
            req.put("voice", voiceName);
        }
        req.put("response_format", fmt);

        JsonNode json;
        try {
            json = postJson("/v1/audio/speech", mapper.writeValueAsString(req));
        } catch (Exception e) {
            throw new MediaProvider.MediaProviderException("TTS-Synthese fehlgeschlagen: " + e.getMessage(), e);
        }

        if (json.has("error")) {
            throw new MediaProvider.MediaProviderException(
                    "TTS-Fehler: " + json.path("error").path("message").asText("unbekannter Fehler"));
        }

        // OpenAI liefert Base64-codiertes Audio im Feld "audio" oder binär im Body.
        if (json.has("audio") && json.get("audio").isTextual()) {
            return Base64.getDecoder().decode(json.get("audio").asText());
        }
        throw new MediaProvider.MediaProviderException("TTS-Antwort enthält keine Audiodaten.");
    }

    @Override
    public String speechToText(byte[] audio, String format) throws MediaProvider.MediaProviderException {
        if (!isAvailable()) {
            throw new MediaProvider.MediaProviderException("Media-Provider nicht verfügbar (jclaw.media.provider.enabled).");
        }
        if (audio == null || audio.length == 0) {
            throw new MediaProvider.MediaProviderException("Audiodaten dürfen nicht leer sein.");
        }
        String fmt = format == null || format.isBlank() ? properties.sttFormat() : format;

        String boundary = "----JClawMediaBoundary" + java.util.UUID.randomUUID();
        byte[] body = buildMultipart(boundary, Map.of(
                "model", properties.model(),
                "response_format", "json",
                "language", ""
        ), List.of(new Part("file", "audio." + fmt, "audio/" + fmt, audio)));

        JsonNode json;
        try {
            json = postMultipart("/v1/audio/transcriptions", body, boundary);
        } catch (Exception e) {
            throw new MediaProvider.MediaProviderException("STT-Transkription fehlgeschlagen: " + e.getMessage(), e);
        }

        if (json.has("error")) {
            throw new MediaProvider.MediaProviderException(
                    "STT-Fehler: " + json.path("error").path("message").asText("unbekannter Fehler"));
        }
        return json.path("text").asText("");
    }

    /* ---------- HTTP-Helfer ---------- */

    private JsonNode postJson(String path, String jsonBody) throws Exception {
        String url = properties.baseUrl() + path;
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + properties.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new MediaProvider.MediaProviderException(
                    "OpenAI-Fehler: HTTP " + response.statusCode() + " " + response.body());
        }
        return mapper.readTree(response.body());
    }

    private JsonNode postMultipart(String path, byte[] body, String boundary) throws Exception {
        String url = properties.baseUrl() + path;
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Authorization", "Bearer " + properties.apiKey())
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new MediaProvider.MediaProviderException(
                    "OpenAI-Fehler: HTTP " + response.statusCode() + " " + response.body());
        }
        return mapper.readTree(response.body());
    }

    private record Part(String name, String fileName, String contentType, byte[] data) {
    }

    private static byte[] buildMultipart(String boundary, Map<String, String> fields, List<Part> files) {
        StringBuilder sb = new StringBuilder();
        if (fields != null) {
            for (Map.Entry<String, String> e : fields.entrySet()) {
                sb.append("--").append(boundary).append("\r\n");
                sb.append("Content-Disposition: form-data; name=\"").append(e.getKey()).append("\"\r\n");
                sb.append("\r\n");
                sb.append(e.getValue()).append("\r\n");
            }
        }
        if (files != null) {
            for (Part f : files) {
                sb.append("--").append(boundary).append("\r\n");
                sb.append("Content-Disposition: form-data; name=\"").append(f.name()).append("\"; filename=\"")
                        .append(f.fileName()).append("\"\r\n");
                sb.append("Content-Type: ").append(f.contentType()).append("\r\n");
                sb.append("\r\n");
                sb.append(new String(f.data(), StandardCharsets.ISO_8859_1)).append("\r\n");
            }
        }
        sb.append("--").append(boundary).append("--\r\n");
        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }
}