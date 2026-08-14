package biz.brumm.config;

import biz.brumm.infrastructure.adapter.out.ai.tool.WebFetchTool;
import biz.brumm.infrastructure.adapter.out.ai.tool.WebSearchTool;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Konfiguration für die Web-Werkzeuge des Agenten ({@code web_fetch}, {@code web_search}).
 * Erst wenn {@code enabled=true} gesetzt ist, werden die Werkzeuge registriert
 * (Deny-by-Default). {@code web_fetch} akzeptiert nur URLs, deren Host in
 * {@code allowedDomains} liegt (leere Liste = kein Abruf erlaubt).
 *
 * @param enabled             Schaltet die Web-Werkzeuge frei.
 * @param allowedDomains      Erlaubte Domains für {@code web_fetch} (inkl. Subdomains).
 * @param searchEndpoint      Basis-URL des Such-Endpoints (Standard: DuckDuckGo Instant Answer).
 * @param fetchTimeoutSeconds Timeout für Abrufe/Suchen in Sekunden (Standard: 10).
 * @param maxFetchBytes       Maximale Größe einer abgerufenen Antwort in Bytes (Standard: 200.000).
 * @param maxSearchResults    Maximale Anzahl von Treffern pro Suche (Standard: 5).
 */
@ConfigurationProperties(prefix = "jclaw.agent.webtool")
public record WebToolProperties(
        boolean enabled,
        List<String> allowedDomains,
        String searchEndpoint,
        Integer fetchTimeoutSeconds,
        Integer maxFetchBytes,
        Integer maxSearchResults) {

    public WebToolProperties {
        allowedDomains = allowedDomains == null ? List.of() : List.copyOf(allowedDomains);
        if (fetchTimeoutSeconds != null && fetchTimeoutSeconds <= 0) {
            throw new IllegalArgumentException("jclaw.agent.webtool.fetch-timeout-seconds muss positiv sein.");
        }
        if (maxFetchBytes != null && maxFetchBytes <= 0) {
            throw new IllegalArgumentException("jclaw.agent.webtool.max-fetch-bytes muss positiv sein.");
        }
        if (maxSearchResults != null && maxSearchResults <= 0) {
            throw new IllegalArgumentException("jclaw.agent.webtool.max-search-results muss positiv sein.");
        }
    }

    public Duration effectiveFetchTimeout() {
        return Duration.ofSeconds(fetchTimeoutSeconds == null ? WebFetchTool.DEFAULT_TIMEOUT_SECONDS : fetchTimeoutSeconds);
    }

    public int effectiveMaxFetchBytes() {
        return maxFetchBytes == null ? WebFetchTool.DEFAULT_MAX_FETCH_BYTES : maxFetchBytes;
    }

    public int effectiveMaxSearchResults() {
        return maxSearchResults == null ? WebSearchTool.DEFAULT_MAX_RESULTS : maxSearchResults;
    }

    public String effectiveSearchEndpoint() {
        return (searchEndpoint == null || searchEndpoint.isBlank()) ? WebSearchTool.DEFAULT_ENDPOINT : searchEndpoint;
    }
}
