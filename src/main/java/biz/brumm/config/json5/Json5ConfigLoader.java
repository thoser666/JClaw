package biz.brumm.config.json5;

import de.marhali.json5.Json5;
import de.marhali.json5.Json5Element;
import de.marhali.json5.Json5Object;
import de.marhali.json5.Json5Primitive;
import de.marhali.json5.config.Json5Options;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lädt eine JSON5-Konfigurationsdatei und flatten sie zu einem flachen
 * Properties-Map (Dot-Notation). Unterstützt {@code ${VAR}}-Substitution
 * und {@code "$include"} für Datei-Einbindung.
 *
 * <p>Die JSON5-Datei verwendet OpenClaw-kompatible Kurzschlüssel (z. B. {@code agents.max-iterations}).
 * Der Loader ergänzt automatisch den {@code jclaw.}-Prefix für Spring Boot's {@code @ConfigurationProperties}.</p>
 */
public class Json5ConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(Json5ConfigLoader.class);

    private static final Pattern VAR_PATTERN = Pattern.compile("\\$\\{([^}]+)}");
    private static final Json5 JSON5 = new Json5(Json5Options.DEFAULT);

    private static final String JCLAW_PREFIX = "jclaw.";

    // Top-Level-Bereiche, die den jclaw.-Prefix erhalten
    private static final Map<String, String> PREFIX_MAPPING = Map.of(
            "agents", "jclaw.agent",
            "session", "jclaw.session",
            "tools", "jclaw.agent.tools",
            "skills", "jclaw.agent.skills",
            "plugins", "jclaw.agent.plugins",
            "mcp", "jclaw.mcp",
            "gateway", "server"
    );

    /**
     * Lädt die angegebene JSON5-Datei und gibt eine flache Properties-Map zurück.
     * Pfade in {@code "$include"} werden relativ zum übergebenen Basisverzeichnis aufgelöst.
     *
     * @param basePath Verzeichnis, in dem die Datei liegt (für relative $include-Pfade)
     * @param fileName Name der JSON5-Datei (z. B. {@code openclaw.json})
     * @return Flache Map in Dot-Notation (z. B. {@code {"jclaw.agent.max-iterations": "8"}})
     * @throws IOException bei Lesefehlern oder ungültigem JSON5
     */
    public static Map<String, String> load(Path basePath, String fileName) throws IOException {
        return mapToSpringProperties(loadRaw(basePath, fileName));
    }

    /**
     * Lädt die angegebene JSON5-Datei und gibt die rohen Properties (vor dem Mapping)
     * zurück. Die Keys verwenden die JSON5-Kurzschlüssel (z. B. {@code agents.max-iterations}).
     *
     * @param basePath Verzeichnis, in dem die Datei liegt (für relative $include-Pfade)
     * @param fileName Name der JSON5-Datei (z. B. {@code openclaw.json})
     * @return Flache Map mit JSON5-Kurzschlüsseln (vor dem Spring-Boot-Mapping)
     * @throws IOException bei Lesefehlern oder ungültigem JSON5
     */
    public static Map<String, String> loadRaw(Path basePath, String fileName) throws IOException {
        Path filePath = basePath.resolve(fileName);
        if (!Files.exists(filePath)) {
            log.info("JSON5-Konfigurationsdatei nicht gefunden: {} — übersprungen.", filePath.toAbsolutePath());
            return Map.of();
        }

        String content = Files.readString(filePath);
        log.info("Lade JSON5-Konfiguration: {}", filePath.toAbsolutePath());

        Json5Element root = JSON5.parse(content);
        if (!(root instanceof Json5Object rootObj)) {
            throw new IOException("JSON5-Datei muss ein Objekt sein (oben): " + filePath);
        }

        Map<String, String> raw = new LinkedHashMap<>();
        flatten(rootObj, "", raw);

        // $include verarbeiten
        processIncludes(basePath, raw);

        // ${VAR}-Substitution
        Map<String, String> resolved = resolveVariables(raw);

        log.info("JSON5-Konfiguration geladen: {} Eigenschaften.", resolved.size());
        return resolved;
    }

    private static void flatten(Json5Object obj, String prefix, Map<String, String> target) {
        for (Map.Entry<String, Json5Element> entry : obj.entrySet()) {
            String key = entry.getKey();
            Json5Element value = entry.getValue();
            String fullKey = prefix.isEmpty() ? key : prefix + "." + key;

            if (value instanceof Json5Object nested) {
                flatten(nested, fullKey, target);
            } else if (value instanceof Json5Primitive primitive) {
                target.put(fullKey, primitiveToString(primitive));
            } else {
                // Null, Array oder unbekannt → leerer String
                target.put(fullKey, "");
            }
        }
    }

    private static String primitiveToString(Json5Primitive primitive) {
        if (primitive.isString()) return primitive.getAsString();
        if (primitive.isNumber()) return primitive.getAsNumber().toString();
        if (primitive.isBoolean()) return String.valueOf(primitive.getAsBoolean());
        return primitive.toString();
    }

    /**
     * Mappt Kurzschlüssel aus der JSON5-Datei auf Spring-Boot-Property-Namen.
     * Z. B. {@code agents.max-iterations} → {@code jclaw.agent.max-iterations}.
     */
    static Map<String, String> mapToSpringProperties(Map<String, String> rawProps) {
        Map<String, String> springProps = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : rawProps.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();

            // $include ist ein Meta-Feld, wird nicht gemappt
            if (key.equals("$include")) {
                springProps.put(key, value);
                continue;
            }

            String springKey = mapKeyToSpring(key);
            springProps.put(springKey, value);
        }
        return springProps;
    }

    static String mapKeyToSpring(String key) {
        // Bereits mit jclaw.-Prefix? Direkt durchreichen
        if (key.startsWith(JCLAW_PREFIX)) return key;

        // Top-Level-Bereich finden und mappen
        for (Map.Entry<String, String> mapping : PREFIX_MAPPING.entrySet()) {
            if (key.equals(mapping.getKey())) {
                // Nur der Bereich selbst, keine Kinder → z. B. "agents" → "jclaw.agent"
                return mapping.getValue();
            }
            if (key.startsWith(mapping.getKey() + ".")) {
                String rest = key.substring(mapping.getKey().length() + 1);
                return mapping.getValue() + "." + rest;
            }
        }

        // Unbekannter Bereich: jclaw.-Prefix hinzufügen
        return JCLAW_PREFIX + key;
    }

    private static void processIncludes(Path basePath, Map<String, String> props) {
        String includeValue = props.get("$include");
        if (includeValue == null || includeValue.isBlank()) return;

        props.remove("$include");

        for (String includePath : includeValue.split(",")) {
            String trimmed = includePath.trim();
            if (trimmed.isEmpty()) continue;
            try {
                Path resolved = basePath.resolve(trimmed);
                if (Files.exists(resolved)) {
                    String incContent = Files.readString(resolved);
                    Json5Element incRoot = JSON5.parse(incContent);
                    if (incRoot instanceof Json5Object incObj) {
                        Map<String, String> includedProps = new LinkedHashMap<>();
                        flatten(incObj, "", includedProps);
                        processIncludes(resolved.getParent(), includedProps);
                        includedProps.forEach((k, v) -> props.putIfAbsent(k, resolveSingleVariable(v, props)));
                        log.info("Inkludiert: {} ({} Eigenschaften).", trimmed, includedProps.size());
                    }
                } else {
                    log.warn("Inkludierte Datei nicht gefunden: {}", resolved.toAbsolutePath());
                }
            } catch (IOException e) {
                log.error("Fehler beim Lesen der inkludierten Datei {}: {}", trimmed, e.getMessage());
            }
        }
    }

    private static Map<String, String> resolveVariables(Map<String, String> props) {
        Map<String, String> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : props.entrySet()) {
            resolved.put(entry.getKey(), resolveSingleVariable(entry.getValue(), props));
        }
        return resolved;
    }

    static String resolveSingleVariable(String value, Map<String, String> props) {
        if (value == null) return "";
        Matcher matcher = VAR_PATTERN.matcher(value);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String varName = matcher.group(1);
            String replacement = props.getOrDefault(varName,
                    System.getenv().getOrDefault(varName, ""));
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
