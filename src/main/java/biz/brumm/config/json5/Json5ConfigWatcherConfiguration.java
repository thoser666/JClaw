package biz.brumm.config.json5;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Konfiguration für das JSON5-Hot-Reload-Feature. Erstellt den
 * {@link Json5ConfigReloadService} und optional den {@link Json5ConfigWatcher}.
 *
 * <p>Hot-Reload ist über {@code jclaw.config.hot-reload.enabled=true}
 * zu aktivieren (Default: {@code false}).</p>
 */
@Configuration
public class Json5ConfigWatcherConfiguration {

    private static final Logger log = LoggerFactory.getLogger(Json5ConfigWatcherConfiguration.class);

    private static final String CONFIG_FILE = "openclaw.json";

    @Bean
    public Json5ConfigReloadService configReloadService(ConfigurableEnvironment environment) {
        Path configDir = determineConfigDir(environment);
        return new Json5ConfigReloadService(environment, configDir, CONFIG_FILE);
    }

    @Bean
    @ConditionalOnProperty(prefix = "jclaw.config.hot-reload", name = "enabled", havingValue = "true")
    public Json5ConfigWatcher json5ConfigWatcher(ConfigurableEnvironment environment,
                                                  Json5ConfigReloadService reloadService) {
        Path configDir = determineConfigDir(environment);
        log.info("Hot-Reload aktiviert — Überwache: {}", configDir.resolve(CONFIG_FILE).toAbsolutePath());

        Json5ConfigWatcher watcher = new Json5ConfigWatcher(configDir, CONFIG_FILE, path -> {
            try {
                reloadService.reload();
            } catch (Exception e) {
                log.error("Hot-Reload fehlgeschlagen: {}", e.getMessage());
            }
        });

        try {
            watcher.start();
        } catch (IOException e) {
            log.error("Konnte JSON5-Überwachung nicht starten: {}", e.getMessage());
        }

        return watcher;
    }

    private Path determineConfigDir(ConfigurableEnvironment environment) {
        String configDir = environment.getProperty("jclaw.config.dir");
        if (configDir != null && !configDir.isBlank()) {
            return Path.of(configDir);
        }
        return Path.of(".");
    }
}
