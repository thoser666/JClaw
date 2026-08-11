package biz.brumm.infrastructure.adapter.out.ai.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class ShellToolTest {

    @TempDir
    Path tempDir;

    private ShellTool tool() {
        return new ShellTool(tempDir, Duration.ofSeconds(30), 10_000);
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    @Test
    void runCommandReturnsOutput() {
        assertThat(tool().runCommand("echo hello")).contains("hello");
    }

    @Test
    void runCommandRejectsBlankCommand() {
        assertThat(tool().runCommand(" ")).isEqualTo("Fehler: Befehl darf nicht leer sein.");
    }

    @Test
    void runCommandReportsNonZeroExitCode() {
        String result = tool().runCommand("exit 3");

        assertThat(result).contains("Exit-Code 3");
    }

    @Test
    void runCommandEnforcesTimeout() throws IOException {
        String command = isWindows() ? "ping -n 10 127.0.0.1 > nul" : "sleep 5";
        Path isolatedWorkdir = Files.createTempDirectory("jclaw-timeout-");
        ShellTool strictTool = new ShellTool(isolatedWorkdir, Duration.ofSeconds(1), 10_000);

        assertThat(strictTool.runCommand(command)).contains("Zeitlimit");
    }

    @Test
    void runCommandTruncatesLargeOutput() {
        String command = isWindows() ? "for /L %i in (1,1,50) do @echo line" : "seq 1 50";
        ShellTool truncatingTool = new ShellTool(tempDir, Duration.ofSeconds(30), 20);

        assertThat(truncatingTool.runCommand(command)).contains("... (Ausgabe gekuerzt)");
    }

    @Test
    void runCommandUsesConfiguredWorkdir() throws IOException {
        Files.writeString(tempDir.resolve("marker.txt"), "x", StandardCharsets.UTF_8);
        String command = isWindows() ? "dir /b" : "ls -1";

        assertThat(tool().runCommand(command)).contains("marker.txt");
    }

    @Test
    void runCommandRejectsOverlyLongCommand() {
        assertThat(tool().runCommand("x".repeat(501)))
                .isEqualTo("Fehler: Befehl zu lang (max 500 Zeichen).");
    }
}
