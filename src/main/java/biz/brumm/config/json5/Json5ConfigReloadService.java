package biz.brumm.config.json5;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Service zum Neuladen der JSON5-Konfiguration. Aktualisiert die
 * Spring-Environment mit den neuen Werten. Laufende Agents behalten
 * ihre aktuelle Konfiguration (kein Bean-Restart).
 *
 * <p>Dieser Service wird für manuelle Reloads ({@code config.apply})
 * und für die automatische Überwachung ({@link Json5ConfigWatcher}) verwendet.</p>
 */
public class Json5ConfigReloadService {

    private static final Logger log = LoggerFactory.getLogger(Json5ConfigReloadService.class);

    private final ConfigurableEnvironment environment;
    private final Path configDir;
    private final String configFileName;

    /**
     * @param environment    Spring-Environment (wird bei Reload aktualisiert)
     * @param configDir      Verzeichnis der JSON5-Konfigurationsdatei
     * @param configFileName Name der JSON5-Konfigurationsdatei (z. B. {@code openclaw.json})
     */
    public Json5ConfigReloadService(ConfigurableEnvironment environment, Path configDir, String configFileName) {
        this.environment = environment;
        this.configDir = configDir;
        this.configFileName = configFileName;
    }

    /**
     * Liest die JSON5-Datei, validiert die Konfiguration und aktualisiert die Spring-Environment.
     *
     * @return {@code true} wenn der Reload erfolgreich war, {@code false} bei Fehler
     * @throws IOException                  bei Lesefehlern
     * @throws Json5ConfigValidationException bei ungültiger Konfiguration
     */
    public boolean reload() throws IOException, Json5ConfigValidationException {
        log.info("Starte Reload der JSON5-Konfiguration...");

        // Rohe Properties laden (vor dem Mapping auf Spring-Boot-Namen)
        Map<String, String> rawProperties = Json5ConfigLoader.loadRaw(configDir, configFileName);
        if (rawProperties.isEmpty()) {
            log.warn("JSON5-Konfiguration ist leer oder nicht vorhanden — Reload übersprungen.");
            return false;
        }

        // Validierung gegen die rohen JSON5-Keys
        try {
            Json5ConfigValidator.validate(rawProperties);
        } catch (Json5ConfigValidationException e) {
            log.error("JSON5-Konfiguration ungültig — Reload abgebrochen:\n{}", e.getMessage());
            throw e;
        }

        // Auf Spring-Boot-Properties mappen
        Map<String, String> springProperties = Json5ConfigLoader.mapToSpringProperties(rawProperties);

        // PropertySource in der Environment aktualisieren
        updateEnvironment(springProperties);

        log.info("JSON5-Konfiguration erfolgreich neu geladen ({} Eigenschaften).", springProperties.size());
        return true;
    }

    private void updateEnvironment(Map<String, String> properties) {
        String sourceName = Json5ConfigEnvironmentPostProcessor.PROPERTY_SOURCE_NAME;

        // Alte PropertySource entfernen
        environment.getPropertySources().remove(sourceName);

        // Neue PropertySource hinzufügen
        Map<String, Object> sourceMap = new LinkedHashMap<>(properties);
        MapPropertySource propertySource = new MapPropertySource(sourceName, sourceMap);
        environment.getPropertySources().addFirst(propertySource);
    }
}
