package biz.brumm.infrastructure.adapter.out.ai.tool;

import biz.brumm.domain.port.out.AgentTool;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Shell-Werkzeug für den Agenten. Führt Befehle im konfigurierten Arbeitsverzeichnis aus,
 * begrenzt durch Zeitlimit und maximale Ausgabelänge. Nur über
 * {@code jclaw.agent.shelltool.enabled=true} aktivierbar (Deny-by-Default).
 */
public class ShellTool implements AgentTool {

    public static final int DEFAULT_TIMEOUT_SECONDS = 30;
    public static final int DEFAULT_MAX_OUTPUT_CHARS = 10_000;

    private static final int MAX_COMMAND_LENGTH = 500;
    private static final String TRUNCATION_MARKER = "\n... (Ausgabe gekuerzt)";

    private final Path workdir;
    private final Duration timeout;
    private final int maxOutputChars;

    public ShellTool(Path workdir, Duration timeout, int maxOutputChars) {
        this.workdir = workdir.toAbsolutePath().normalize();
        this.timeout = timeout;
        this.maxOutputChars = maxOutputChars;
    }

    @Tool(name = "runCommand",
            description = "Fuehrt einen Shell-Befehl im Arbeitsverzeichnis aus und liefert die Ausgabe.")
    public String runCommand(
            @ToolParam(description = "Shell-Befehl, z. B. 'git status' oder 'mvnw test'.") String command) {
        if (command == null || command.isBlank()) {
            return "Fehler: Befehl darf nicht leer sein.";
        }
        if (command.length() > MAX_COMMAND_LENGTH) {
            return "Fehler: Befehl zu lang (max " + MAX_COMMAND_LENGTH + " Zeichen).";
        }

        Process process;
        try {
            // NOSONAR - Shell-Ausfuehrung ist die gewollte Funktion dieses Tools
            ProcessBuilder processBuilder = new ProcessBuilder(shellCommand(command))
                    .directory(workdir.toFile())
                    .redirectErrorStream(true);
            process = processBuilder.start();
        } catch (IOException e) {
            return "Fehler: Befehl konnte nicht gestartet werden: " + e.getMessage();
        }

        CompletableFuture<byte[]> outputFuture = CompletableFuture.supplyAsync(() -> readAllBytes(process.getInputStream()));

        boolean completed;
        try {
            completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            killProcessTree(process);
            Thread.currentThread().interrupt();
            return "Fehler: Ausfuehrung wurde unterbrochen.";
        }
        if (!completed) {
            killProcessTree(process);
            return "Fehler: Befehl hat das Zeitlimit von " + timeout.toSeconds() + " Sekunden ueberschritten.";
        }

        String output = truncate(new String(outputFuture.join(), StandardCharsets.UTF_8));
        int exitCode = process.exitValue();
        if (exitCode != 0) {
            return output.isBlank()
                    ? "Fehler: Befehl endete mit Exit-Code " + exitCode + "."
                    : output + "\n(Exit-Code: " + exitCode + ")";
        }
        return output.isBlank() ? "(keine Ausgabe)" : output;
    }

    private List<String> shellCommand(String command) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return List.of("cmd", "/c", command);
        }
        return List.of("/bin/sh", "-c", command);
    }

    private void killProcessTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private byte[] readAllBytes(InputStream input) {
        try {
            return input.readAllBytes();
        } catch (IOException e) {
            return new byte[0];
        }
    }

    private String truncate(String output) {
        if (output.length() <= maxOutputChars) {
            return output;
        }
        return output.substring(0, maxOutputChars) + TRUNCATION_MARKER;
    }
}
