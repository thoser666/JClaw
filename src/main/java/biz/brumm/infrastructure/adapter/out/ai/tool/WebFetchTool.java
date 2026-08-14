package biz.brumm.infrastructure.adapter.out.ai.tool;

import biz.brumm.domain.port.out.AgentTool;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Web-Werkzeug {@code web_fetch}: lädt den Inhalt einer Webseite und liefert ihn
 * als Text an das Modell. Aus Sicherheitsgründen werden nur URLs mit
 * http/https-Schema akzeptiert, deren Host gegen die konfigurierten
 * {@code allowedDomains} geprüft wird (leere Liste = alle Abrufe abgewiesen).
 */
public class WebFetchTool implements AgentTool {

    public static final int DEFAULT_TIMEOUT_SECONDS = 10;
    public static final int DEFAULT_MAX_FETCH_BYTES = 200_000;

    private static final String TRUNCATION_MARKER = "\n... (Inhalt gekuerzt)";
    private static final Pattern SCRIPT_STYLE_PATTERN = Pattern.compile("(?is)<(script|style)[^>]*>.*?</(script|style)>");
    private static final Pattern TAG_PATTERN = Pattern.compile("<[^>]+>");
    private static final Pattern NUMERIC_ENTITY_PATTERN = Pattern.compile("&#(\\d{1,7});");
    private static final Pattern HEX_ENTITY_PATTERN = Pattern.compile("&#x([0-9a-fA-F]{1,6});");

    private final List<String> allowedDomains;
    private final Duration timeout;
    private final int maxFetchBytes;
    private final HttpClient httpClient;

    public WebFetchTool(List<String> allowedDomains, Duration timeout, int maxFetchBytes) {
        this(allowedDomains, timeout, maxFetchBytes, HttpClient.newHttpClient());
    }

    WebFetchTool(List<String> allowedDomains, Duration timeout, int maxFetchBytes, HttpClient httpClient) {
        this.allowedDomains = List.copyOf(allowedDomains);
        this.timeout = timeout;
        this.maxFetchBytes = maxFetchBytes;
        this.httpClient = httpClient;
    }

    @Tool(name = "web_fetch",
            description = "Lädt den Inhalt einer Webseite als Text (nur für konfigurierte erlaubte Domains).")
    public String webFetch(
            @ToolParam(description = "Vollständige URL (http/https), z. B. 'https://example.com/doc'.") String url) {
        if (url == null || url.isBlank()) {
            return "Fehler: URL darf nicht leer sein.";
        }
        URI uri;
        try {
            uri = URI.create(url.strip());
        } catch (IllegalArgumentException e) {
            return "Fehler: Ungültige URL.";
        }
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            return "Fehler: Nur http/https-URLs sind erlaubt.";
        }
        if (uri.getHost() == null || uri.getUserInfo() != null) {
            return "Fehler: Ungültige URL.";
        }
        if (!isAllowedHost(uri.getHost())) {
            return "Fehler: Domain '" + uri.getHost() + "' ist nicht erlaubt.";
        }

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
            return "Fehler: Abruf fehlgeschlagen: " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Fehler: Abruf wurde unterbrochen.";
        }
        if (statusCode != 200) {
            return "Fehler: HTTP-Status " + statusCode + ".";
        }
        String text = toText(body);
        return text.isBlank() ? "(leere Antwort)" : text;
    }

    boolean isAllowedHost(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        for (String domain : allowedDomains) {
            String candidate = domain.strip().toLowerCase(Locale.ROOT);
            if (candidate.isEmpty()) {
                continue;
            }
            if (normalized.equals(candidate) || normalized.endsWith("." + candidate)) {
                return true;
            }
        }
        return false;
    }

    private String readCapped(InputStream input) throws IOException {
        byte[] buffer = new byte[Math.max(1, Math.min(8_192, maxFetchBytes))];
        StringBuilder content = new StringBuilder();
        int total = 0;
        int read;
        boolean truncated = false;
        while ((read = input.read(buffer)) != -1) {
            if (total + read > maxFetchBytes) {
                int allowed = maxFetchBytes - total;
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

    static String toText(String html) {
        String text = SCRIPT_STYLE_PATTERN.matcher(html).replaceAll(" ");
        text = text.replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</(p|div|li|tr|section|article|h[1-6])>", "\n");
        text = TAG_PATTERN.matcher(text).replaceAll(" ");
        text = decodeEntities(text);
        text = text.replaceAll("[ \t]+", " ").replaceAll("\\n\\s+", "\n");
        return text.strip();
    }

    private static String decodeEntities(String input) {
        String result = input
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&nbsp;", " ");
        result = NUMERIC_ENTITY_PATTERN.matcher(result).replaceAll(match -> String.valueOf((char) Integer.parseInt(match.group(1))));
        result = HEX_ENTITY_PATTERN.matcher(result).replaceAll(match -> String.valueOf((char) Integer.parseInt(match.group(1), 16)));
        return result;
    }
}
