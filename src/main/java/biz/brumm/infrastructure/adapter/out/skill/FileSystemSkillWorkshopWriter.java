package biz.brumm.infrastructure.adapter.out.skill;

import biz.brumm.config.SkillProperties;
import biz.brumm.domain.model.Skill;
import biz.brumm.domain.port.out.SkillWorkshopWriter;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Dateisystem-Writer für den Skill-Workshop. Schreibt Skills als
 * {@code <skills.dir>/<name>/SKILL.md} (AgentSkills-Format, kompatibel zu
 * {@link FileSystemSkillProvider}). Die {@code targetHash}-Bindung läuft über die rohen
 * {@code SKILL.md}-Bytes, damit externe Änderungen vor {@code apply} als stale erkannt werden.
 */
@Component
public class FileSystemSkillWorkshopWriter implements SkillWorkshopWriter {

    private static final List<String> SKILL_FILENAMES = List.of("SKILL.md", "skill.md");
    private static final String FRONTMATTER_DELIMITER = "---";
    private static final String SHA_256 = "SHA-256";

    private final Path skillsDir;

    public FileSystemSkillWorkshopWriter(SkillProperties properties) {
        this.skillsDir = Path.of(properties.dir()).toAbsolutePath().normalize();
    }

    @Override
    public boolean exists(String name) {
        return skillFile(name) != null;
    }

    @Override
    public String contentHash(String name) {
        Path file = skillFile(name);
        if (file == null) {
            return "";
        }
        try {
            return sha256Hex(Files.readAllBytes(file));
        } catch (IOException e) {
            throw new IllegalStateException("SKILL.md konnte nicht gelesen werden: " + file, e);
        }
    }

    @Override
    public Skill write(Skill skill) {
        Path dir = skillsDir.resolve(skill.name());
        Path file = dir.resolve("SKILL.md");
        try {
            Files.createDirectories(dir);
            Files.writeString(file, serialize(skill), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Skill konnte nicht geschrieben werden: " + file, e);
        }
        return new Skill(skill.name(), skill.description(), skill.content(), dir.toString());
    }

    private Path skillFile(String name) {
        Path dir = skillsDir.resolve(name);
        if (!Files.isDirectory(dir)) {
            return null;
        }
        for (String filename : SKILL_FILENAMES) {
            Path file = dir.resolve(filename);
            if (Files.isRegularFile(file)) {
                return file;
            }
        }
        return null;
    }

    private String serialize(Skill skill) {
        String description = skill.description().replaceAll("\\R+", " ").strip();
        return FRONTMATTER_DELIMITER + System.lineSeparator()
                + "name: " + skill.name() + System.lineSeparator()
                + "description: " + description + System.lineSeparator()
                + FRONTMATTER_DELIMITER + System.lineSeparator()
                + System.lineSeparator()
                + skill.content().strip() + System.lineSeparator();
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance(SHA_256);
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 nicht verfügbar", e);
        }
    }
}