package biz.brumm.infrastructure.adapter.out.skill;

import biz.brumm.config.SkillProperties;
import biz.brumm.domain.model.Skill;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FileSystemSkillProviderTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsSkillFromSkillMdWithFrontmatter() throws IOException {
        Path skillDir = Files.createDirectory(tempDir.resolve("code-review"));
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: code-review
                description: Prueft Pull Requests auf Bugs.
                ---
                Pruefe Aenderungen systematisch auf Fehler.
                """, StandardCharsets.UTF_8);

        List<Skill> skills = provider().findAll();

        assertThat(skills).hasSize(1);
        Skill skill = skills.get(0);
        assertThat(skill.name()).isEqualTo("code-review");
        assertThat(skill.description()).isEqualTo("Prueft Pull Requests auf Bugs.");
        assertThat(skill.content()).contains("Pruefe Aenderungen systematisch auf Fehler.");
        assertThat(skill.baseDir()).isEqualTo(skillDir.toString());
    }

    @Test
    void recognizesLowercaseSkillMd() throws IOException {
        Files.createDirectory(tempDir.resolve("docs"));
        Files.writeString(tempDir.resolve("docs/skill.md"), """
                ---
                name: docs
                description: Erstellt Dokumentation.
                ---
                Schreibe klare Doku.
                """, StandardCharsets.UTF_8);

        List<Skill> skills = provider().findAll();

        assertThat(skills).hasSize(1);
        assertThat(skills.get(0).name()).isEqualTo("docs");
    }

    @Test
    void skipsSkillWithoutFrontmatter() throws IOException {
        Files.createDirectory(tempDir.resolve("plain"));
        Files.writeString(tempDir.resolve("plain/SKILL.md"), "Kein Frontmatter hier.", StandardCharsets.UTF_8);

        assertThat(provider().findAll()).isEmpty();
    }

    @Test
    void fallsBackToDirectoryNameWhenNameMissing() throws IOException {
        Path skillDir = Files.createDirectory(tempDir.resolve("shorthand"));
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                description: Ohne Namen.
                ---
                Inhalt.
                """, StandardCharsets.UTF_8);

        List<Skill> skills = provider().findAll();

        assertThat(skills).hasSize(1);
        assertThat(skills.get(0).name()).isEqualTo("shorthand");
        assertThat(skills.get(0).description()).isEqualTo("Ohne Namen.");
    }

    @Test
    void skipsDirectoriesWithoutSkillFile() throws IOException {
        Files.createDirectory(tempDir.resolve("no-skill"));
        Files.writeString(tempDir.resolve("no-skill/readme.md"), "Nur Doku.", StandardCharsets.UTF_8);

        assertThat(provider().findAll()).isEmpty();
    }

    @Test
    void sortsSkillsByName() throws IOException {
        Files.createDirectory(tempDir.resolve("zeta"));
        Files.writeString(tempDir.resolve("zeta/SKILL.md"), "---\nname: zeta\n---\nInhalt.\n", StandardCharsets.UTF_8);
        Files.createDirectory(tempDir.resolve("alpha"));
        Files.writeString(tempDir.resolve("alpha/SKILL.md"), "---\nname: alpha\n---\nInhalt.\n", StandardCharsets.UTF_8);

        List<Skill> skills = provider().findAll();

        assertThat(skills).extracting(Skill::name).containsExactly("alpha", "zeta");
    }

    @Test
    void returnsEmptyListForMissingDirectory() {
        assertThat(provider().findAll()).isEmpty();
    }

    private FileSystemSkillProvider provider() {
        return new FileSystemSkillProvider(new SkillProperties(tempDir.toString(), List.of()));
    }
}
