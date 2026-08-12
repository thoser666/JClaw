package biz.brumm.infrastructure.adapter.out.ai.tool;

import biz.brumm.domain.port.out.AgentTool;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/**
 * Datei-Werkzeuge für den Agenten (readFile, listDirectory, writeFile, glob, grep).
 * Alle Pfade werden relativ zum konfigurierten Arbeitsverzeichnis aufgelöst; Zugriffe
 * ausserhalb (Pfad-Traversal) werden abgewiesen.
 */
public class FileTool implements AgentTool {

    public static final long DEFAULT_MAX_READ_BYTES = 1_048_576;

    private static final int MAX_GLOB_RESULTS = 100;
    private static final int MAX_GREP_RESULTS = 200;
    private static final String TRAVERSAL_ERROR = "Pfad liegt außerhalb des Arbeitsverzeichnisses.";

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

    @Tool(name = "glob",
            description = "Findet Dateien anhand eines Glob-Musters im Arbeitsverzeichnis.")
    public String glob(
            @ToolParam(description = "Glob-Muster relativ zum Arbeitsverzeichnis, z. B. '**/*.java' oder 'src/**/*.txt'.") String pattern) {
        if (pattern == null || pattern.isBlank()) {
            return "Fehler: Muster darf nicht leer sein.";
        }
        String normalized = pattern.replace('\\', '/');
        if (isTraversalUnsafe(normalized)) {
            return "Fehler: " + TRAVERSAL_ERROR;
        }
        try {
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + normalized);
            PathMatcher rootMatcher = normalized.startsWith("**/")
                    ? FileSystems.getDefault().getPathMatcher("glob:" + normalized.substring(3))
                    : null;
            Path walkRoot = resolveWithinWorkdir(staticPrefix(normalized));
            if (!Files.isDirectory(walkRoot)) {
                return "Fehler: Verzeichnis nicht gefunden: " + displayPath(walkRoot);
            }

            List<String> matches = new ArrayList<>();
            boolean truncated = false;
            try (Stream<Path> stream = Files.walk(walkRoot)) {
                Iterator<Path> iterator = stream
                        .filter(Files::isRegularFile)
                        .filter(path -> matchesPattern(matcher, rootMatcher, workdir.relativize(path)))
                        .iterator();
                while (iterator.hasNext()) {
                    if (matches.size() >= MAX_GLOB_RESULTS) {
                        truncated = true;
                        break;
                    }
                    matches.add(displayPath(iterator.next()));
                }
            }
            matches.sort(Comparator.naturalOrder());
            if (matches.isEmpty()) {
                return "(keine Treffer)";
            }
            return String.join("\n", matches) + (truncated ? "\n(gekürzt, Trefferlimit erreicht)" : "");
        } catch (PatternSyntaxException e) {
            return "Fehler: ungültiges Glob-Muster: " + e.getMessage();
        } catch (IllegalArgumentException e) {
            return "Fehler: " + e.getMessage();
        } catch (IOException e) {
            return "Fehler: Verzeichnis konnte nicht gelesen werden: " + e.getMessage();
        }
    }

    @Tool(name = "grep",
            description = "Durchsucht Textdateien im Arbeitsverzeichnis nach einem regulären Ausdruck.")
    public String grep(
            @ToolParam(description = "Regulärer Ausdruck, nach dem gesucht wird.") String regex,
            @ToolParam(description = "Relativer Pfad zu Datei oder Verzeichnis. Leer für das Arbeitsverzeichnis.") String path) {
        if (regex == null || regex.isBlank()) {
            return "Fehler: Suchmuster darf nicht leer sein.";
        }
        Pattern compiled;
        try {
            compiled = Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            return "Fehler: ungültiger regulärer Ausdruck: " + e.getMessage();
        }
        try {
            Path target = resolveWithinWorkdir(path);
            List<String> matches = new ArrayList<>();
            if (Files.isRegularFile(target)) {
                searchFile(target, compiled, matches);
            } else if (Files.isDirectory(target)) {
                try (Stream<Path> stream = Files.walk(target)) {
                    Iterator<Path> iterator = stream.filter(Files::isRegularFile).iterator();
                    while (iterator.hasNext() && matches.size() < MAX_GREP_RESULTS) {
                        searchFile(iterator.next(), compiled, matches);
                    }
                }
            } else {
                return "Fehler: Pfad nicht gefunden: " + displayPath(target);
            }

            if (matches.isEmpty()) {
                return "(keine Treffer)";
            }
            return String.join("\n", matches)
                    + (matches.size() >= MAX_GREP_RESULTS ? "\n(gekürzt, Trefferlimit erreicht)" : "");
        } catch (IllegalArgumentException e) {
            return "Fehler: " + e.getMessage();
        } catch (IOException e) {
            return "Fehler: Verzeichnis konnte nicht gelesen werden: " + e.getMessage();
        }
    }

    private void searchFile(Path file, Pattern pattern, List<String> matches) {
        try {
            if (Files.size(file) > maxReadBytes) {
                return;
            }
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size() && matches.size() < MAX_GREP_RESULTS; i++) {
                if (pattern.matcher(lines.get(i)).find()) {
                    matches.add(displayPath(file) + ":" + (i + 1) + ": " + lines.get(i));
                }
            }
        } catch (IOException | RuntimeException e) {
            // Datei überspringen (binär oder nicht lesbar)
        }
    }

    private boolean matchesPattern(PathMatcher matcher, PathMatcher rootMatcher, Path relative) {
        return matcher.matches(relative) || (rootMatcher != null && rootMatcher.matches(relative));
    }

    private Path resolveWithinWorkdir(String requested) {
        Path resolved = workdir.resolve(requested == null ? "" : requested).normalize();
        if (!resolved.startsWith(workdir)) {
            throw new IllegalArgumentException(TRAVERSAL_ERROR);
        }
        return resolved;
    }

    private boolean isTraversalUnsafe(String normalized) {
        if (normalized.startsWith("/") || normalized.matches("^[A-Za-z]:.*")) {
            return true;
        }
        for (String segment : normalized.split("/")) {
            if ("..".equals(segment)) {
                return true;
            }
        }
        return false;
    }

    private String staticPrefix(String pattern) {
        for (char wildcard : new char[]{'*', '?', '[', '{'}) {
            int index = pattern.indexOf(wildcard);
            if (index != -1) {
                return pattern.substring(0, index);
            }
        }
        return pattern;
    }

    private String displayPath(Path path) {
        String relative = workdir.relativize(path).toString().replace('\\', '/');
        return relative.isEmpty() ? "." : relative;
    }

    private String describeEntry(Path entry) {
        String type = Files.isDirectory(entry) ? "[dir]" : "[file]";
        return type + " " + entry.getFileName();
    }
}
