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

    @Test
    void globFindsFilesRecursively() throws IOException {
        Files.writeString(tempDir.resolve("a.txt"), "a", StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("b.log"), "b", StandardCharsets.UTF_8);
        Files.createDirectories(tempDir.resolve("src/main"));
        Files.writeString(tempDir.resolve("src/main/c.txt"), "c", StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("src/main/d.java"), "d", StandardCharsets.UTF_8);

        assertThat(tool().glob("**/*.txt"))
                .contains("a.txt", "src/main/c.txt")
                .doesNotContain("b.log", "src/main/d.java");
    }

    @Test
    void globFindsFilesWithRelativePrefix() throws IOException {
        Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(tempDir.resolve("src/App.java"), "x", StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("other.txt"), "y", StandardCharsets.UTF_8);

        assertThat(tool().glob("src/*.java")).isEqualTo("src/App.java");
    }

    @Test
    void globReturnsNoMatchesMessage() throws IOException {
        Files.writeString(tempDir.resolve("a.txt"), "a", StandardCharsets.UTF_8);

        assertThat(tool().glob("*.xyz")).isEqualTo("(keine Treffer)");
    }

    @Test
    void globRejectsTraversalPattern() {
        assertThat(tool().glob("../*.txt"))
                .isEqualTo("Fehler: Pfad liegt außerhalb des Arbeitsverzeichnisses.");
    }

    @Test
    void globRejectsAbsolutePattern() {
        assertThat(tool().glob("C:/Windows/*.txt"))
                .isEqualTo("Fehler: Pfad liegt außerhalb des Arbeitsverzeichnisses.");
    }

    @Test
    void globRejectsBlankPattern() {
        assertThat(tool().glob(" ")).isEqualTo("Fehler: Muster darf nicht leer sein.");
    }

    @Test
    void globTruncatesAtResultLimit() throws IOException {
        for (int i = 0; i < 105; i++) {
            Files.writeString(tempDir.resolve("file-" + i + ".txt"), "x", StandardCharsets.UTF_8);
        }

        String result = tool().glob("*.txt");

        assertThat(result.lines()).hasSize(101);
        assertThat(result).endsWith("(gekürzt, Trefferlimit erreicht)");
    }

    @Test
    void grepFindsMatchesAcrossFiles() throws IOException {
        Files.writeString(tempDir.resolve("one.txt"), "Hallo Welt\nkein Treffer", StandardCharsets.UTF_8);
        Files.createDirectories(tempDir.resolve("sub"));
        Files.writeString(tempDir.resolve("sub/two.txt"),
                "Zweite\nWelt wieder", StandardCharsets.UTF_8);

        String result = tool().grep("Welt", "");

        assertThat(result).contains("one.txt:1: Hallo Welt");
        assertThat(result).contains("sub/two.txt:2: Welt wieder");
    }

    @Test
    void grepScopesSearchToSingleFile() throws IOException {
        Files.createDirectories(tempDir.resolve("sub"));
        Files.writeString(tempDir.resolve("sub/only.txt"), "geheim", StandardCharsets.UTF_8);

        assertThat(tool().grep("geheim", "sub/only.txt")).contains("sub/only.txt:1: geheim");
    }

    @Test
    void grepRespectsDirectoryScope() throws IOException {
        Files.writeString(tempDir.resolve("root.txt"), "marker", StandardCharsets.UTF_8);
        Files.createDirectories(tempDir.resolve("sub"));
        Files.writeString(tempDir.resolve("sub/nested.txt"), "marker", StandardCharsets.UTF_8);

        String result = tool().grep("marker", "sub");

        assertThat(result).contains("sub/nested.txt:1: marker");
        assertThat(result).doesNotContain("root.txt");
    }

    @Test
    void grepReturnsNoMatchesMessage() throws IOException {
        Files.writeString(tempDir.resolve("a.txt"), "Hallo", StandardCharsets.UTF_8);

        assertThat(tool().grep("nix-da", "")).isEqualTo("(keine Treffer)");
    }

    @Test
    void grepRejectsInvalidRegex() {
        assertThat(tool().grep("[", "")).startsWith("Fehler: ungültiger regulärer Ausdruck:");
    }

    @Test
    void grepRejectsBlankRegex() {
        assertThat(tool().grep(" ", "")).isEqualTo("Fehler: Suchmuster darf nicht leer sein.");
    }

    @Test
    void grepRejectsTraversalPath() {
        assertThat(tool().grep("x", "../"))
                .isEqualTo("Fehler: Pfad liegt außerhalb des Arbeitsverzeichnisses.");
    }

    @Test
    void grepReturnsErrorForMissingPath() {
        assertThat(tool().grep("x", "unbekannt"))
                .isEqualTo("Fehler: Pfad nicht gefunden: unbekannt");
    }
}
