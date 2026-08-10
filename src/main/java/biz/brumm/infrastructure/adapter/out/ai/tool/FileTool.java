package biz.brumm.infrastructure.adapter.out.ai.tool;

import biz.brumm.domain.port.out.AgentTool;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Datei-Werkzeuge für den Agenten. Alle Pfade werden relativ zum konfigurierten
 * Arbeitsverzeichnis aufgelöst; Zugriffe ausserhalb (Pfad-Traversal) werden abgewiesen.
 */
public class FileTool implements AgentTool {

    public static final long DEFAULT_MAX_READ_BYTES = 1_048_576;

    private final Path workdir;
    private final long maxReadBytes;

    public FileTool(Path workdir) {
        this(workdir, DEFAULT_MAX_READ_BYTES);
    }

    public FileTool(Path workdir, long maxReadBytes) {
        this.workdir = workdir.toAbsolutePath().normalize();
        this.maxReadBytes = maxReadBytes;
    }

    @Tool(name = "readFile",
            description = "Liest den Inhalt einer Datei innerhalb des Arbeitsverzeichnisses.")
    public String readFile(
            @ToolParam(description = "Relativer Pfad zur Datei, z. B. 'src/App.java'.") String path) {
        if (path == null || path.isBlank()) {
            return "Fehler: Pfad darf nicht leer sein.";
        }
        try {
            Path target = resolveWithinWorkdir(path);
            if (!Files.isRegularFile(target)) {
                return "Fehler: Datei nicht gefunden: " + displayPath(target);
            }
            long size = Files.size(target);
            if (size > maxReadBytes) {
                return "Fehler: Datei zu gross zum Lesen (" + size + " Bytes, Limit " + maxReadBytes + ").";
            }
            return Files.readString(target, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return "Fehler: " + e.getMessage();
        } catch (IOException e) {
            return "Fehler: Datei konnte nicht gelesen werden: " + e.getMessage();
        }
    }

    @Tool(name = "listDirectory",
            description = "Listet die Eintraege eines Verzeichnisses innerhalb des Arbeitsverzeichnisses.")
    public String listDirectory(
            @ToolParam(description = "Relativer Pfad zum Verzeichnis, z. B. 'src'. Leer fuer das Arbeitsverzeichnis.") String path) {
        try {
            Path target = resolveWithinWorkdir(path);
            if (!Files.isDirectory(target)) {
                return "Fehler: Verzeichnis nicht gefunden: " + displayPath(target);
            }
            try (Stream<Path> entries = Files.list(target)) {
                return entries.sorted(Comparator.comparing(entry -> entry.getFileName().toString()))
                        .map(this::describeEntry)
                        .reduce((a, b) -> a + "\n" + b)
                        .orElse("(leer)");
            }
        } catch (IllegalArgumentException e) {
            return "Fehler: " + e.getMessage();
        } catch (IOException e) {
            return "Fehler: Verzeichnis konnte nicht gelesen werden: " + e.getMessage();
        }
    }

    @Tool(name = "writeFile",
            description = "Schreibt Inhalt in eine Datei innerhalb des Arbeitsverzeichnisses.")
    public String writeFile(
            @ToolParam(description = "Relativer Pfad zur Datei, z. B. 'notes.txt'.") String path,
            @ToolParam(description = "Inhalt der Datei.") String content) {
        if (path == null || path.isBlank()) {
            return "Fehler: Pfad darf nicht leer sein.";
        }
        try {
            Path target = resolveWithinWorkdir(path);
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(target, content == null ? "" : content, StandardCharsets.UTF_8);
            return "OK";
        } catch (IllegalArgumentException e) {
            return "Fehler: " + e.getMessage();
        } catch (IOException e) {
            return "Fehler: Datei konnte nicht geschrieben werden: " + e.getMessage();
        }
    }

    private Path resolveWithinWorkdir(String requested) {
        Path resolved = workdir.resolve(requested == null ? "" : requested).normalize();
        if (!resolved.startsWith(workdir)) {
            throw new IllegalArgumentException("Pfad liegt außerhalb des Arbeitsverzeichnisses.");
        }
        return resolved;
    }

    private String displayPath(Path path) {
        String relative = workdir.relativize(path).toString();
        return relative.isEmpty() ? "." : relative;
    }

    private String describeEntry(Path entry) {
        String type = Files.isDirectory(entry) ? "[dir]" : "[file]";
        return type + " " + entry.getFileName();
    }
}
