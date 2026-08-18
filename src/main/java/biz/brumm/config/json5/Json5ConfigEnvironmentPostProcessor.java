package biz.brumm.config.json5;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * Lädt die JSON5-Konfigurationsdatei ({@code openclaw.json}) beim Start
 * und integriert sie als PropertySource in die Spring-Environment.
 * Properties aus JSON5 überschreiben application.properties-Werte.
 */
public class Json5ConfigEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(Json5ConfigEnvironmentPostProcessor.class);

    static final String CONFIG_FILE = "openclaw.json";
    static final String PROPERTY_SOURCE_NAME = "jclawJson5Config";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        try {
            Path configDir = determineConfigDir(environment);
            Map<String, String> properties = Json5ConfigLoader.load(configDir, CONFIG_FILE);

            if (properties.isEmpty()) {
                log.info("Keine JSON5-Konfiguration gefunden oder Datei leer.");
                return;
            }

            // Validierung
            try {
                Json5ConfigValidator.validate(properties);
                log.info("JSON5-Konfiguration validiert: keine Fehler.");
            } catch (Json5ConfigValidationException e) {
                log.error("JSON5-Konfiguration ungültig:\n{}", e.getMessage());
                throw e;
            }

            // Als PropertySource registrieren (höchste Priorität)
            Map<String, Object> sourceMap = new java.util.LinkedHashMap<>(properties);
            MapPropertySource propertySource = new MapPropertySource(PROPERTY_SOURCE_NAME, sourceMap);
            environment.getPropertySources().addFirst(propertySource);
            log.info("JSON5-Konfiguration als PropertySource registriert ({} Eigenschaften).", properties.size());

        } catch (IOException e) {
            log.warn("Fehler beim Lesen der JSON5-Konfiguration: {}", e.getMessage());
        }
    }

    private Path determineConfigDir(ConfigurableEnvironment environment) {
        // 1. System-Property: jclaw.config.dir
        String configDir = environment.getProperty("jclaw.config.dir");
        if (configDir != null && !configDir.isBlank()) {
            return Path.of(configDir);
        }

        // 2. Arbeitsverzeichnis
        return Path.of(".");
    }
}
