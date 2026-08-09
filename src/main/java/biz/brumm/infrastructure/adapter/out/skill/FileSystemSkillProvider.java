package biz.brumm.infrastructure.adapter.out.skill;

import biz.brumm.config.SkillProperties;
import biz.brumm.domain.model.Skill;
import biz.brumm.domain.port.out.SkillProvider;
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
 * Liest Skills im AgentSkills-Format ({@code SKILL.md} bzw. {@code skill.md} mit YAML-Frontmatter)
 * aus dem konfigurierten Verzeichnis. Jedes Unterverzeichnis entspricht einem Skill.
 */
@Component
public class FileSystemSkillProvider implements SkillProvider {

    private static final Logger log = LoggerFactory.getLogger(FileSystemSkillProvider.class);

    private static final List<String> SKILL_FILENAMES = List.of("SKILL.md", "skill.md");
    private static final String FRONTMATTER_DELIMITER = "---";

    private final Path skillsDir;

    public FileSystemSkillProvider(SkillProperties properties) {
        this.skillsDir = Path.of(properties.dir()).toAbsolutePath().normalize();
    }

    @Override
    public List<Skill> findAll() {
        if (!Files.isDirectory(skillsDir)) {
            log.info("Skill-Verzeichnis '{}' existiert nicht - keine Skills geladen.", skillsDir);
            return List.of();
        }

        try (var stream = Files.list(skillsDir)) {
            return stream.filter(Files::isDirectory)
                    .map(this::loadSkill)
                    .flatMap(Optional::stream)
                    .sorted(Comparator.comparing(Skill::name))
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("Skill-Verzeichnis konnte nicht gelesen werden: " + skillsDir, e);
        }
    }

    private Optional<Skill> loadSkill(Path directory) {
        for (String filename : SKILL_FILENAMES) {
            Path file = directory.resolve(filename);
            if (Files.isRegularFile(file)) {
                try {
                    return parseSkill(file);
                } catch (IOException e) {
                    log.warn("Skill '{}' konnte nicht gelesen werden: {}", directory.getFileName(), e.getMessage());
                    return Optional.empty();
                }
            }
        }
        log.debug("Kein SKILL.md in '{}' gefunden - uebersprungen.", directory);
        return Optional.empty();
    }

    private Optional<Skill> parseSkill(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !FRONTMATTER_DELIMITER.equals(lines.get(0).trim())) {
            log.debug("Datei '{}' hat kein Frontmatter - uebersprungen.", file);
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
            log.debug("Datei '{}' hat kein schliessendes '---' - uebersprungen.", file);
            return Optional.empty();
        }

        String frontmatter = String.join("\n", lines.subList(1, end));
        String body = String.join("\n", lines.subList(end + 1, lines.size())).strip();

        Map<String, Object> metadata = parseFrontmatter(frontmatter);
        String fallbackName = file.getParent().getFileName().toString();
        String name = asString(metadata.get("name"), fallbackName);
        String description = asString(metadata.get("description"), "");

        if (name.isBlank()) {
            log.debug("Skill in '{}' hat keinen Namen - uebersprungen.", file);
            return Optional.empty();
        }

        log.info("Skill '{}' geladen aus '{}'.", name, file);
        return Optional.of(new Skill(name, description, body, file.getParent().toString()));
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
}
