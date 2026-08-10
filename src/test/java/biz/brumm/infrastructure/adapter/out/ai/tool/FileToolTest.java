package biz.brumm.infrastructure.adapter.out.ai.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FileToolTest {

    @TempDir
    Path tempDir;

    private FileTool tool() {
        return new FileTool(tempDir);
    }

    @Test
    void readFileReturnsContent() throws IOException {
        Files.writeString(tempDir.resolve("notes.txt"), "Hallo Welt", StandardCharsets.UTF_8);

        assertThat(tool().readFile("notes.txt")).isEqualTo("Hallo Welt");
    }

    @Test
    void readFileReturnsErrorForMissingFile() {
        assertThat(tool().readFile("gibt-es-nicht.txt")).isEqualTo("Fehler: Datei nicht gefunden: gibt-es-nicht.txt");
    }

    @Test
    void readFileReturnsErrorForBlankPath() {
        assertThat(tool().readFile(" ")).isEqualTo("Fehler: Pfad darf nicht leer sein.");
    }

    @Test
    void readFileReturnsErrorWhenFileTooLarge() throws IOException {
        Files.writeString(tempDir.resolve("gross.txt"), "x".repeat(100), StandardCharsets.UTF_8);
        FileTool strictTool = new FileTool(tempDir, 10);

        assertThat(strictTool.readFile("gross.txt"))
                .isEqualTo("Fehler: Datei zu gross zum Lesen (100 Bytes, Limit 10).");
    }

    @Test
    void readFileRejectsPathOutsideWorkdir() throws IOException {
        Path outside = Files.createTempFile("jclaw-outside", ".txt");
        try {
            assertThat(tool().readFile("../" + outside.getFileName()))
                    .isEqualTo("Fehler: Pfad liegt außerhalb des Arbeitsverzeichnisses.");
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void readFileRejectsAbsolutePathOutsideWorkdir() {
        assertThat(tool().readFile(Path.of("C:").toAbsolutePath().resolve("Windows/system32/drivers/etc/hosts").toString()))
                .isEqualTo("Fehler: Pfad liegt außerhalb des Arbeitsverzeichnisses.");
    }

    @Test
    void listDirectoryListsEntriesSorted() throws IOException {
        Files.writeString(tempDir.resolve("b.txt"), "b", StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("a.txt"), "a", StandardCharsets.UTF_8);
        Files.createDirectory(tempDir.resolve("sub"));

        String listing = tool().listDirectory("");

        assertThat(listing).containsSubsequence("[file] a.txt", "[file] b.txt", "[dir] sub");
    }

    @Test
    void listDirectoryReturnsErrorForMissingDirectory() {
        assertThat(tool().listDirectory("unbekannt"))
                .isEqualTo("Fehler: Verzeichnis nicht gefunden: unbekannt");
    }

    @Test
    void listDirectoryRejectsPathOutsideWorkdir() {
        assertThat(tool().listDirectory("../../"))
                .isEqualTo("Fehler: Pfad liegt außerhalb des Arbeitsverzeichnisses.");
    }

    @Test
    void writeFileCreatesNestedContentAndDirectories() throws IOException {
        assertThat(tool().writeFile("sub/notes.txt", "Inhalt")).isEqualTo("OK");

        assertThat(tempDir.resolve("sub/notes.txt")).hasContent("Inhalt");
    }

    @Test
    void writeFileWithNullContentWritesEmptyFile() throws IOException {
        tool().writeFile("leer.txt", null);

        assertThat(tempDir.resolve("leer.txt")).hasContent("");
    }

    @Test
    void writeFileRejectsPathOutsideWorkdir() {
        assertThat(tool().writeFile("../böse.txt", "x"))
                .isEqualTo("Fehler: Pfad liegt außerhalb des Arbeitsverzeichnisses.");
        assertThat(tempDir.resolveSibling("böse.txt")).doesNotExist();
    }

    @Test
    void writeFileRejectsBlankPath() {
        assertThat(tool().writeFile("", "x")).isEqualTo("Fehler: Pfad darf nicht leer sein.");
    }
}
