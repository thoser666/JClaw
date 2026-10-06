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

class FileSystemSkillWorkshopWriterTest {

    @TempDir
    Path tempDir;

    private FileSystemSkillWorkshopWriter writer() {
        return new FileSystemSkillWorkshopWriter(new SkillProperties(tempDir.toString(), List.of()));
    }

    @Test
    void writeCreatesSkillProviderReadableSkill() {
        writer().write(new Skill("morning-catchup", "Morgen-Routine", "Tue montags die Inbox.", null));

        FileSystemSkillProvider provider = new FileSystemSkillProvider(
                new SkillProperties(tempDir.toString(), List.of()));
        List<Skill> skills = provider.findAll();

        assertThat(skills).hasSize(1);
        assertThat(skills.get(0).name()).isEqualTo("morning-catchup");
        assertThat(skills.get(0).description()).isEqualTo("Morgen-Routine");
        assertThat(skills.get(0).content()).contains("Tue montags die Inbox.");
    }

    @Test
    void existsReflectsWrittenSkill() {
        FileSystemSkillWorkshopWriter writer = writer();
        assertThat(writer.exists("morning-catchup")).isFalse();

        writer.write(new Skill("morning-catchup", "A", "Body", null));

        assertThat(writer.exists("morning-catchup")).isTrue();
        assertThat(writer.contentHash("morning-catchup")).isNotEmpty();
    }

    @Test
    void contentHashChangesWhenExternalEditChangesTheFile() throws IOException {
        FileSystemSkillWorkshopWriter writer = writer();
        writer.write(new Skill("qa-check", "QA", "alt", null));
        String before = writer.contentHash("qa-check");

        Path file = tempDir.resolve("qa-check").resolve("SKILL.md");
        String edited = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).replace("alt", "neu");
        Files.writeString(file, edited, StandardCharsets.UTF_8);

        assertThat(writer.contentHash("qa-check")).isNotEqualTo(before);
    }

    @Test
    void contentHashIsEmptyForMissingSkill() {
        assertThat(writer().contentHash("nicht-da")).isEmpty();
    }

    @Test
    void writeMaterializesFrontmatterAndSingleLineDescription() throws IOException {
        writer().write(new Skill("qa-check", "Zeile eins\nZeile zwei", "Body", null));

        String content = Files.readString(tempDir.resolve("qa-check").resolve("SKILL.md"), StandardCharsets.UTF_8);

        assertThat(content).startsWith("---");
        assertThat(content).contains("name: qa-check");
        assertThat(content).contains("description: Zeile eins Zeile zwei");
        assertThat(content).endsWith("Body" + System.lineSeparator());
    }
}