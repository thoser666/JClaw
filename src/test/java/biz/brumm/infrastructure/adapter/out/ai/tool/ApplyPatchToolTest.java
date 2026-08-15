package biz.brumm.infrastructure.adapter.out.ai.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ApplyPatchToolTest {

    @TempDir
    Path tempDir;

    private ApplyPatchTool tool() {
        return new ApplyPatchTool(tempDir);
    }

    @Test
    void addFileCreatesFileWithContent() throws Exception {
        String patch = """
                *** Begin Patch
                *** Add File: hello.txt
                Zeile eins
                Zeile zwei
                *** End Patch
                """;

        assertThat(tool().applyPatch(patch)).contains("1 Datei(en) angelegt").contains(", 0 geändert, 0 gelöscht");
        assertThat(Files.readString(tempDir.resolve("hello.txt"), StandardCharsets.UTF_8))
                .isEqualTo("Zeile eins\nZeile zwei\n");
    }

    @Test
    void addFileCreatesParentDirectories() throws Exception {
        String result = tool().applyPatch("*** Add File: src/main/Main.java\npublic class Main {}\n");

        assertThat(result).contains("1 Datei(en) angelegt");
        assertThat(Files.isRegularFile(tempDir.resolve("src/main/Main.java"))).isTrue();
    }

    @Test
    void addFileWorksWithoutBeginMarker() throws Exception {
        tool().applyPatch("*** Add File: einfach.txt\nInhalt\n");

        assertThat(Files.readString(tempDir.resolve("einfach.txt"), StandardCharsets.UTF_8))
                .isEqualTo("Inhalt\n");
    }

    @Test
    void addFilePreservesInternalBlankLines() throws Exception {
        tool().applyPatch("*** Add File: blank.txt\nZeile eins\n\nZeile drei\n");

        assertThat(Files.readString(tempDir.resolve("blank.txt"), StandardCharsets.UTF_8))
                .isEqualTo("Zeile eins\n\nZeile drei\n");
    }

    @Test
    void addFileRejectsExistingFile() throws Exception {
        Files.writeString(tempDir.resolve("x.txt"), "alt", StandardCharsets.UTF_8);

        assertThat(tool().applyPatch("*** Add File: x.txt\nneu\n")).contains("existiert bereits");
    }

    @Test
    void updateFileReplacesLines() throws Exception {
        Files.writeString(tempDir.resolve("notes.txt"), "eins\nzwei\ndrei\n", StandardCharsets.UTF_8);
        String patch = """
                *** Update File: notes.txt
                @@
                 eins
                -zwei
                +ZWEI
                 drei
                """;

        assertThat(tool().applyPatch(patch)).contains("1 geändert");
        assertThat(Files.readString(tempDir.resolve("notes.txt"), StandardCharsets.UTF_8))
                .isEqualTo("eins\nZWEI\ndrei\n");
    }

    @Test
    void updateFileAppliesMultipleHunks() throws Exception {
        Files.writeString(tempDir.resolve("f.txt"), "a\nb\nc\nd\n", StandardCharsets.UTF_8);
        String patch = """
                *** Update File: f.txt
                @@
                 a
                -b
                +B
                @@
                 c
                -d
                +D
                """;

        tool().applyPatch(patch);

        assertThat(Files.readString(tempDir.resolve("f.txt"), StandardCharsets.UTF_8)).isEqualTo("a\nB\nc\nD\n");
    }

    @Test
    void updateFileWithoutContextReplacesFirstMatch() throws Exception {
        Files.writeString(tempDir.resolve("f.txt"), "b\nb\n", StandardCharsets.UTF_8);

        tool().applyPatch("*** Update File: f.txt\n@@\n-b\n+B\n");

        assertThat(Files.readString(tempDir.resolve("f.txt"), StandardCharsets.UTF_8)).isEqualTo("B\nb\n");
    }

    @Test
    void updateFileCanInsertLines() throws Exception {
        Files.writeString(tempDir.resolve("f.txt"), "a\nc\n", StandardCharsets.UTF_8);
        String patch = """
                *** Update File: f.txt
                @@
                 a
                +b
                 c
                """;

        tool().applyPatch(patch);

        assertThat(Files.readString(tempDir.resolve("f.txt"), StandardCharsets.UTF_8)).isEqualTo("a\nb\nc\n");
    }

    @Test
    void updateFileCanRemoveLines() throws Exception {
        Files.writeString(tempDir.resolve("f.txt"), "a\nb\nc\n", StandardCharsets.UTF_8);
        String patch = """
                *** Update File: f.txt
                @@
                 a
                -b
                 c
                """;

        tool().applyPatch(patch);

        assertThat(Files.readString(tempDir.resolve("f.txt"), StandardCharsets.UTF_8)).isEqualTo("a\nc\n");
    }

    @Test
    void updateFileReportsMissingFile() {
        assertThat(tool().applyPatch("*** Update File: fehlt.txt\n@@\n-a\n+b\n"))
                .contains("Datei nicht gefunden");
    }

    @Test
    void updateFileReportsEmptyHunkBlock() throws Exception {
        Files.writeString(tempDir.resolve("f.txt"), "a\n", StandardCharsets.UTF_8);

        assertThat(tool().applyPatch("*** Update File: f.txt\nnur text\n"))
                .contains("keine '@@'-Hunks");
    }

    @Test
    void updateFileReportsUnmatchedHunkAndLeavesFileUntouched() throws Exception {
        Files.writeString(tempDir.resolve("f.txt"), "a\n", StandardCharsets.UTF_8);

        String result = tool().applyPatch("*** Update File: f.txt\n@@\n-z\n+x\n");

        assertThat(result).contains("Hunk konnte nicht angewendet werden");
        assertThat(Files.readString(tempDir.resolve("f.txt"), StandardCharsets.UTF_8)).isEqualTo("a\n");
    }

    @Test
    void failedPatchIsAtomic() throws Exception {
        Files.writeString(tempDir.resolve("target.txt"), "a\n", StandardCharsets.UTF_8);
        String patch = """
                *** Add File: neu.txt
                Inhalt
                *** Update File: target.txt
                @@
                -gibtEsNicht
                +neu
                """;

        String result = tool().applyPatch(patch);

        assertThat(result).contains("Hunk konnte nicht angewendet werden");
        assertThat(Files.exists(tempDir.resolve("neu.txt"))).isFalse();
        assertThat(Files.readString(tempDir.resolve("target.txt"), StandardCharsets.UTF_8)).isEqualTo("a\n");
    }

    @Test
    void deleteFileRemovesFile() throws Exception {
        Files.writeString(tempDir.resolve("weg.txt"), "x", StandardCharsets.UTF_8);

        assertThat(tool().applyPatch("*** Delete File: weg.txt")).contains("1 gelöscht");
        assertThat(Files.exists(tempDir.resolve("weg.txt"))).isFalse();
    }

    @Test
    void deleteFileReportsMissingFile() {
        assertThat(tool().applyPatch("*** Delete File: fehlt.txt")).contains("Datei nicht gefunden");
    }

    @Test
    void rejectsPathTraversal() {
        assertThat(tool().applyPatch("*** Add File: ../aussen.txt\nx\n"))
                .contains("außerhalb des Arbeitsverzeichnisses");
    }

    @Test
    void rejectsAbsolutePath() {
        assertThat(tool().applyPatch("*** Add File: /tmp/x.txt\nx\n"))
                .contains("außerhalb des Arbeitsverzeichnisses");
    }

    @Test
    void rejectsBlankPath() {
        assertThat(tool().applyPatch("*** Add File:   \nx\n")).contains("Pfad darf nicht leer sein");
    }

    @Test
    void rejectsEmptyPatch() {
        assertThat(tool().applyPatch("   ")).contains("Patch darf nicht leer sein");
    }

    @Test
    void rejectsUnknownCommand() {
        assertThat(tool().applyPatch("*** Explode File: x\n")).contains("Unbekannter Patch-Befehl");
    }

    @Test
    void rejectsPatchWithoutOperations() {
        assertThat(tool().applyPatch("*** Begin Patch\n*** End Patch\n"))
                .contains("keine Operationen");
    }

    @Test
    void rejectsContentAfterEndPatch() {
        String result = tool().applyPatch("*** Add File: ok.txt\nx\n*** End Patch\nRest\n");

        assertThat(result).contains("Unerwarteter Inhalt nach '*** End Patch'");
        assertThat(Files.exists(tempDir.resolve("ok.txt"))).isFalse();
    }
}
