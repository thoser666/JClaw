package biz.brumm.infrastructure.adapter.out.plugin;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Ermittelt den Entry-Point eines Plugin-Bundles für die Laufzeit (P4-01).
 * <p>
 * Auflösungsreihenfolge: {@code package.json} → {@code main} (nur wenn der aufgelöste
 * Pfad innerhalb des Plugin-Ordners bleibt — Traversal-Schutz), danach die üblichen
 * Fallbacks {@code src/index.js}, {@code src/index.mjs}, {@code index.js},
 * {@code index.mjs}, {@code main.js}.
 */
public final class EntryPointResolver {

    public static final List<String> FALLBACK_ENTRIES = List.of(
            "src/index.js", "src/index.mjs", "index.js", "index.mjs", "main.js");

    private static final String PACKAGE_JSON = "package.json";

    private final ObjectMapper objectMapper;

    public EntryPointResolver(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public EntryPointResolver() {
        this(new ObjectMapper());
    }

    public Optional<Path> resolve(Path pluginDir) {
        Optional<Path> fromManifest = resolveFromPackageJson(pluginDir);
        if (fromManifest.isPresent()) {
            return fromManifest;
        }
        for (String candidate : FALLBACK_ENTRIES) {
            Path file = pluginDir.resolve(candidate);
            if (Files.isRegularFile(file)) {
                return Optional.of(file);
            }
        }
        return Optional.empty();
    }

    private Optional<Path> resolveFromPackageJson(Path pluginDir) {
        Path packageJson = pluginDir.resolve(PACKAGE_JSON);
        if (!Files.isRegularFile(packageJson)) {
            return Optional.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(packageJson.toFile());
            String main = root.path("main").asString("").strip();
            if (main.isEmpty()) {
                return Optional.empty();
            }
            Path resolved = pluginDir.resolve(main).normalize().toAbsolutePath();
            Path base = pluginDir.toAbsolutePath().normalize();
            if (resolved.startsWith(base) && Files.isRegularFile(resolved)) {
                return Optional.of(resolved);
            }
            return Optional.empty();
        } catch (JacksonException e) {
            return Optional.empty();
        }
    }
}