package biz.brumm.infrastructure.adapter.out.plugin;

import biz.brumm.domain.model.Plugin;
import biz.brumm.domain.model.PluginType;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Parst ein Plugin-Manifest (JSON) und fuehrt eine Control-Plane-Validierung ohne
 * Codeausfuehrung durch: Pflichtfelder je Bundle-Format sowie die Struktur optionaler
 * Felder (z. B. {@code configSchema}) werden geprueft.
 */
public final class PluginManifestParser {

    private final ObjectMapper objectMapper;

    public PluginManifestParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Plugin parse(Path pluginDir, Path manifestFile, PluginType type) throws IOException {
        JsonNode root;
        try {
            root = objectMapper.readTree(manifestFile.toFile());
        } catch (JacksonException e) {
            return invalid(pluginDir, type, "ungültiges JSON: " + e.getOriginalMessage());
        }

        if (!(root instanceof ObjectNode object)) {
            return invalid(pluginDir, type, "Manifest muss ein JSON-Objekt sein.");
        }

        String id = asText(object.get("id"));
        String name = asText(object.get("name"));
        String version = asText(object.get("version"));
        String description = asText(object.get("description"));
        String pluginId = switch (type) {
            case OPENCLAW -> id;
            default -> name;
        };

        List<String> errors = validate(type, id, name, version, object);
        return new Plugin(pluginId, name, version, description, type, pluginDir.toString(),
                errors.isEmpty(), String.join("; ", errors));
    }

    private List<String> validate(PluginType type, String id, String name, String version, ObjectNode root) {
        List<String> errors = new ArrayList<>();
        if (type == PluginType.OPENCLAW) {
            if (isBlank(id)) {
                errors.add("Feld 'id' fehlt.");
            }
            JsonNode configSchema = root.get("configSchema");
            if (configSchema != null && !configSchema.isObject()) {
                errors.add("'configSchema' muss ein JSON-Objekt sein.");
            }
            JsonNode mcpServers = root.get("mcpServers");
            if (mcpServers != null && !mcpServers.isObject()) {
                errors.add("'mcpServers' muss ein JSON-Objekt sein.");
            }
        } else {
            if (isBlank(name)) {
                errors.add("Feld 'name' fehlt.");
            }
            if (isBlank(version)) {
                errors.add("Feld 'version' fehlt.");
            }
        }
        return errors;
    }

    public Plugin parseError(Path pluginDir, PluginType type, String message) {
        return invalid(pluginDir, type, "Manifest kann nicht gelesen werden: " + message);
    }

    private Plugin invalid(Path pluginDir, PluginType type, String message) {
        return new Plugin(null, null, null, null, type, pluginDir.toString(), false, message);
    }

    private String asText(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }
        return node.asString().strip();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
