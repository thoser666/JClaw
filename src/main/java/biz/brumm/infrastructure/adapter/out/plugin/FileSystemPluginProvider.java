package biz.brumm.infrastructure.adapter.out.plugin;

import biz.brumm.config.PluginProperties;
import biz.brumm.domain.model.Plugin;
import biz.brumm.domain.model.PluginType;
import biz.brumm.domain.port.out.PluginProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Liest Plugin-Manifeste aus dem konfigurierten Verzeichnis. Jedes Unterverzeichnis
 * entspricht einem Plugin; erkannt werden OpenClaw-Manifeste ({@code openclaw.plugin.json})
 * sowie kompatible fremde Bundles (Agent Plugins, Codex, Claude, Cursor). Manifeste werden
 * geparst und ohne Codeausfuehrung validiert (Control-Plane).
 */
@Component
public class FileSystemPluginProvider implements PluginProvider {

    private static final Logger log = LoggerFactory.getLogger(FileSystemPluginProvider.class);

    private final Path pluginsDir;
    private final PluginManifestParser parser;

    public FileSystemPluginProvider(PluginProperties properties, ObjectMapper objectMapper) {
        this.pluginsDir = Path.of(properties.dir()).toAbsolutePath().normalize();
        this.parser = new PluginManifestParser(objectMapper);
    }

    @Override
    public List<Plugin> findAll() {
        if (!Files.isDirectory(pluginsDir)) {
            log.info("Plugin-Verzeichnis '{}' existiert nicht - keine Plugins geladen.", pluginsDir);
            return List.of();
        }

        try (var stream = Files.list(pluginsDir)) {
            return stream.filter(Files::isDirectory)
                    .map(this::loadPlugin)
                    .flatMap(Optional::stream)
                    .sorted(Comparator.comparing(Plugin::id, Comparator.nullsLast(String::compareTo)))
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("Plugin-Verzeichnis konnte nicht gelesen werden: " + pluginsDir, e);
        }
    }

    private Optional<Plugin> loadPlugin(Path directory) {
        for (PluginType type : PluginType.values()) {
            Path manifestFile = directory.resolve(type.manifestLocation());
            if (Files.isRegularFile(manifestFile)) {
                try {
                    Plugin plugin = parser.parse(directory, manifestFile, type);
                    log.info("Plugin '{}' ({}) geladen aus '{}'.", plugin.id(), type, manifestFile);
                    return Optional.of(plugin);
                } catch (IOException e) {
                    log.warn("Plugin-Manifest '{}' konnte nicht gelesen werden: {}", manifestFile, e.getMessage());
                    return Optional.of(parser.parseError(directory, type, e.getMessage()));
                }
            }
        }
        log.debug("Kein Plugin-Manifest in '{}' gefunden - uebersprungen.", directory);
        return Optional.empty();
    }
}
