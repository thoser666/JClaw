package biz.brumm.infrastructure.adapter.out.hook;

import biz.brumm.config.HookProperties;
import biz.brumm.domain.model.Hook;
import biz.brumm.domain.port.out.HookProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Liest Hooks im HOOK.md-Format (YAML-Frontmatter) aus dem konfigurierten Verzeichnis.
 * Jedes Unterverzeichnis kann einen HOOK.md-Datei enthalten.
 *
 * <pre>
 * ---
 * name: before-agent-run
 * stage: before_agent_run
 * priority: 10
 * script: ./run.sh
 * description: Führt Vorab-Validierung durch
 * ---
 * </pre>
 */
@Component
public class FileHookProvider implements HookProvider {

    private static final Logger log = LoggerFactory.getLogger(FileHookProvider.class);

    private static final List<String> HOOK_FILENAMES = List.of("HOOK.md", "hook.md");
    private static final String FRONTMATTER_DELIMITER = "---";

    private final Path hooksDir;
    private final HookProperties properties;

    public FileHookProvider(HookProperties properties) {
        this.hooksDir = Path.of(properties.dir()).toAbsolutePath().normalize();
        this.properties = properties;
    }

    @Override
    public List<Hook> findAll() {
        if (!properties.enabled()) {
            return List.of();
        }
        if (!Files.isDirectory(hooksDir)) {
            log.info("Hook-Verzeichnis '{}' existiert nicht — keine Hooks geladen.", hooksDir);
            return List.of();
        }

        try (var stream = Files.list(hooksDir)) {
            return stream.filter(Files::isDirectory)
                    .map(this::loadHook)
                    .flatMap(Optional::stream)
                    .filter(this::isStageAllowed)
                    .sorted(Comparator.comparing(Hook::priority).reversed())
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("Hook-Verzeichnis konnte nicht gelesen werden: " + hooksDir, e);
        }
    }

    @Override
    public List<Hook> findByStage(String stage) {
        return findAll().stream()
                .filter(hook -> hook.stage().equals(stage))
                .toList();
    }

    private Optional<Hook> loadHook(Path directory) {
        for (String filename : HOOK_FILENAMES) {
            Path file = directory.resolve(filename);
            if (Files.isRegularFile(file)) {
                try {
                    return parseHook(file);
                } catch (IOException e) {
                    log.warn("Hook '{}' konnte nicht gelesen werden: {}", directory.getFileName(), e.getMessage());
                    return Optional.empty();
                }
            }
        }
        log.debug("Kein HOOK.md in '{}' gefunden — übersprungen.", directory);
        return Optional.empty();
    }

    private Optional<Hook> parseHook(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !FRONTMATTER_DELIMITER.equals(lines.get(0).trim())) {
            log.debug("Datei '{}' hat kein Frontmatter — übersprungen.", file);
            return Optional.empty();
        }

        int end = -1;
        for (int i = 1; i < lines.size(); i++) {
            if (FRONTMATTER_DELIMITER.equals(lines.get(i).trim())) {
                end = i;
                break;
            }
        }
        if (end == -1) {
            log.debug("Datei '{}' hat kein schließendes '---' — übersprungen.", file);
            return Optional.empty();
        }

        String frontmatter = String.join("\n", lines.subList(1, end));
        Map<String, Object> metadata = parseFrontmatter(frontmatter);

        String fallbackName = file.getParent().getFileName().toString();
        String name = asString(metadata.get("name"), fallbackName);
        String stage = asString(metadata.get("stage"), "");
        int priority = asInt(metadata.get("priority"), 0);
        String script = asString(metadata.get("script"), "");
        String description = asString(metadata.get("description"), "");

        if (name.isBlank() || stage.isBlank()) {
            log.debug("Hook in '{}' hat keinen Namen oder Stage — übersprungen.", file);
            return Optional.empty();
        }

        Path scriptPath;
        if (script.isBlank()) {
            // Standard: Suche nach ausführbarer Datei im gleichen Verzeichnis
            scriptPath = findDefaultScript(file.getParent());
        } else {
            scriptPath = file.getParent().resolve(script).toAbsolutePath().normalize();
        }

        if (scriptPath == null || !Files.isRegularFile(scriptPath)) {
            log.warn("Hook '{}' hat kein gültiges Script — übersprungen (script={}).", name, script);
            return Optional.empty();
        }

        log.info("Hook '{}' geladen aus '{}' (stage={}, priority={}).", name, file, stage, priority);
        return Optional.of(new Hook(name, stage, priority, scriptPath, description));
    }

    private Path findDefaultScript(Path hookDir) {
        // Suche nach run.sh, run.bat, execute.sh, script.sh
        List<String> candidates = List.of("run.sh", "run.bat", "execute.sh", "script.sh", "run");
        for (String candidate : candidates) {
            Path path = hookDir.resolve(candidate);
            if (Files.isRegularFile(path)) {
                return path.toAbsolutePath().normalize();
            }
        }
        return null;
    }

    private boolean isStageAllowed(Hook hook) {
        if (properties.allowedStages().isEmpty()) {
            return true;
        }
        return properties.allowedStages().contains(hook.stage());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseFrontmatter(String frontmatter) {
        if (frontmatter.isBlank()) {
            return Map.of();
        }
        try {
            Object parsed = new Yaml().load(frontmatter);
            return parsed instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        } catch (YAMLException e) {
            log.warn("Frontmatter konnte nicht als YAML geparst werden: {}", e.getMessage());
            return Map.of();
        }
    }

    private String asString(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value).strip();
    }

    private int asInt(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return fallback;
    }
}
