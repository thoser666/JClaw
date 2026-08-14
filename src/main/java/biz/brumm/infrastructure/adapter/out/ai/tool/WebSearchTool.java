package biz.brumm.infrastructure.adapter.out.ai.tool;

import biz.brumm.domain.port.out.AgentTool;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Web-Werkzeug {@code web_search}: fragt einen konfigurierbaren Such-Endpoint an
 * (Standard: DuckDuckGo Instant Answer API) und liefert Treffer mit Beschreibung
 * und URL an das Modell. Der Endpoint ist über {@code jclaw.agent.webtool.search-endpoint}
 * ersetzbar, damit auch ein selbst gehosteter oder anderer Anbieter genutzt werden kann.
 */
public class WebSearchTool implements AgentTool {

    public static final String DEFAULT_ENDPOINT = "https://api.duckduckgo.com";
    public static final int DEFAULT_TIMEOUT_SECONDS = 10;
    public static final int DEFAULT_MAX_RESULTS = 5;
    public static final int DEFAULT_MAX_RESPONSE_BYTES = 100_000;

    private static final String TRUNCATION_MARKER = "\n... (Antwort gekuerzt)";

    private final String endpoint;
    private final int maxResults;
    private final Duration timeout;
    private final int maxResponseBytes;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public WebSearchTool(String endpoint, int maxResults, Duration timeout) {
        this(endpoint, maxResults, timeout, DEFAULT_MAX_RESPONSE_BYTES, HttpClient.newHttpClient(), new ObjectMapper());
    }

    WebSearchTool(String endpoint, int maxResults, Duration timeout, int maxResponseBytes,
                  HttpClient httpClient, ObjectMapper objectMapper) {
        this.endpoint = endpoint;
        this.maxResults = maxResults;
        this.timeout = timeout;
        this.maxResponseBytes = maxResponseBytes;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Tool(name = "web_search",
            description = "Sucht im Web nach einer Anfrage und liefert Treffer mit Beschreibung und URL.")
    public String webSearch(
            @ToolParam(description = "Suchanfrage, z. B. 'Spring Boot Tool-Annotation'.") String query) {
        if (query == null || query.isBlank()) {
            return "Fehler: Suchanfrage darf nicht leer sein.";
        }
        String normalized = query.strip();
        URI uri = buildSearchUri(normalized);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .GET()
                .build();
        String body;
        int statusCode;
        try {
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            statusCode = response.statusCode();
            try (InputStream input = response.body()) {
                body = readCapped(input);
            }
        } catch (HttpTimeoutException e) {
            return "Fehler: Zeitlimit überschritten.";
        } catch (IOException e) {
            return "Fehler: Suche fehlgeschlagen: " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Fehler: Suche wurde unterbrochen.";
        }
        if (statusCode != 200) {
            return "Fehler: HTTP-Status " + statusCode + ".";
        }
        return format(body, normalized);
    }

    URI buildSearchUri(String query) {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String separator = endpoint.contains("?") ? "&" : "?";
        return URI.create(endpoint + separator + "q=" + encoded + "&format=json&no_html=1&skip_disambig=1");
    }

    private String readCapped(InputStream input) throws IOException {
        byte[] buffer = new byte[Math.max(1, Math.min(8_192, maxResponseBytes))];
        StringBuilder content = new StringBuilder();
        int total = 0;
        int read;
        boolean truncated = false;
        while ((read = input.read(buffer)) != -1) {
            if (total + read > maxResponseBytes) {
                int allowed = maxResponseBytes - total;
                if (allowed > 0) {
                    content.append(new String(buffer, 0, allowed, StandardCharsets.UTF_8));
                }
                truncated = true;
                break;
            }
            content.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
            total += read;
        }
        if (truncated) {
            content.append(TRUNCATION_MARKER);
        }
        return content.toString();
    }

    String format(String json, String query) {
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (JacksonException e) {
            return "Fehler: Ungültige Such-Antwort.";
        }
        List<String> results = new ArrayList<>();
        JsonNode abstractText = root.get("AbstractText");
        if (abstractText != null && abstractText.isTextual() && !abstractText.asText().isBlank()) {
            results.add(abstractText.asText() + " (" + root.path("AbstractURL").asText("") + ")");
        }
        JsonNode topics = root.get("RelatedTopics");
        if (topics != null && topics.isArray()) {
            for (JsonNode topic : topics) {
                if (results.size() >= maxResults) {
                    break;
                }
                JsonNode nested = topic.get("Topics");
                if (nested != null && nested.isArray()) {
                    for (JsonNode sub : nested) {
                        if (results.size() >= maxResults) {
                            break;
                        }
                        if (sub.hasNonNull("Text") && sub.hasNonNull("FirstURL")) {
                            results.add(sub.get("Text").asText() + " (" + sub.get("FirstURL").asText() + ")");
                        }
                    }
                } else if (topic.hasNonNull("Text") && topic.hasNonNull("FirstURL")) {
                    results.add(topic.get("Text").asText() + " (" + topic.get("FirstURL").asText() + ")");
                }
            }
        }
        StringBuilder sb = new StringBuilder("Ergebnisse für '").append(query).append("':");
        if (results.isEmpty()) {
            sb.append(" keine Treffer");
        } else {
            for (String result : results) {
                sb.append("\n- ").append(result);
            }
        }
        return sb.toString();
    }
}
