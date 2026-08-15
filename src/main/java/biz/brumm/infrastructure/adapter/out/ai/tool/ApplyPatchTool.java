package biz.brumm.infrastructure.adapter.out.ai.tool;

import biz.brumm.domain.port.out.AgentTool;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Kern-Werkzeug {@code apply_patch}: wendet einen Patch im apply_patch-Format
 * (OpenClaw/Claude-kompatibel) auf Dateien im Arbeitsverzeichnis an.
 *
 * <p>Unterstützt werden die Blöcke {@code *** Add File:}, {@code *** Update File:}
 * (mit {@code @@}-Hunks: Kontextzeilen mit führendem Leerzeichen, {@code -} entfernt,
 * {@code +} fügt hinzu) und {@code *** Delete File:}. Der Patch wird vollständig
 * geparst und gegen den Dateistand geprüft, bevor geschrieben wird
 * (Alles-oder-nichts); alle Pfade werden relativ zum Arbeitsverzeichnis aufgelöst.
 */
public class ApplyPatchTool implements AgentTool {

    private static final String BEGIN = "*** Begin Patch";
    private static final String END = "*** End Patch";
    private static final String TRAVERSAL_ERROR = "Pfad liegt außerhalb des Arbeitsverzeichnisses.";

    private final Path workdir;

    public ApplyPatchTool(Path workdir) {
        this.workdir = workdir.toAbsolutePath().normalize();
    }

    @Tool(name = "apply_patch",
            description = "Wendet einen Patch auf Dateien im Arbeitsverzeichnis an (anlegen, ändern, löschen).")
    public String applyPatch(
            @ToolParam(description = "Patch im apply_patch-Format: '*** Add File: <pfad>' und '*** Update File: <pfad>' mit '@@'-Hunks (Kontextzeilen mit Leerzeichen-Präfix, '-' löscht, '+' fügt hinzu) sowie '*** Delete File: <pfad>', abgeschlossen mit '*** End Patch'.") String patch) {
        if (patch == null || patch.isBlank()) {
            return "Fehler: Patch darf nicht leer sein.";
        }
        try {
            List<FileOperation> operations = parse(toLines(patch));
            apply(operations);
            return summary(operations);
        } catch (IllegalArgumentException e) {
            return "Fehler: " + e.getMessage();
        } catch (IOException e) {
            return "Fehler: Patch konnte nicht angewendet werden: " + e.getMessage();
        }
    }

    private List<String> toLines(String patch) {
        String normalized = patch.replace("\r\n", "\n").replace('\r', '\n');
        List<String> lines = new ArrayList<>();
        for (String part : normalized.split("\n", -1)) {
            lines.add(part);
        }
        return lines;
    }

    private List<FileOperation> parse(List<String> lines) {
        List<FileOperation> operations = new ArrayList<>();
        int index = 0;
        while (index < lines.size() && lines.get(index).isBlank()) {
            index++;
        }
        if (index < lines.size() && lines.get(index).equals(BEGIN)) {
            index++;
        }
        while (index < lines.size()) {
            String line = lines.get(index);
            if (line.startsWith("*** ")) {
                if (line.equals(END)) {
                    index++;
                    while (index < lines.size() && lines.get(index).isBlank()) {
                        index++;
                    }
                    if (index < lines.size()) {
                        throw new IllegalArgumentException("Unerwarteter Inhalt nach '" + END + "'.");
                    }
                    break;
                }
                OperationType type = commandType(line);
                if (type == null) {
                    throw new IllegalArgumentException("Unbekannter Patch-Befehl: " + line);
                }
                String path = line.substring(line.indexOf(":") + 1).strip();
                index++;
                List<String> body = new ArrayList<>();
                while (index < lines.size() && !lines.get(index).startsWith("*** ")) {
                    body.add(lines.get(index));
                    index++;
                }
                operations.add(new FileOperation(type, path, body));
            } else if (line.isBlank()) {
                index++;
            } else {
                throw new IllegalArgumentException("Unbekannter Patch-Inhalt: " + line);
            }
        }
        if (operations.isEmpty()) {
            throw new IllegalArgumentException("Patch enthält keine Operationen.");
        }
        return operations;
    }

    private OperationType commandType(String line) {
        if (line.startsWith("*** Add File: ")) {
            return OperationType.ADD;
        }
        if (line.startsWith("*** Update File: ")) {
            return OperationType.UPDATE;
        }
        if (line.startsWith("*** Delete File: ")) {
            return OperationType.DELETE;
        }
        return null;
    }

    private void apply(List<FileOperation> operations) throws IOException {
        List<PlannedWrite> writes = new ArrayList<>();
        List<Path> deletes = new ArrayList<>();
        for (FileOperation op : operations) {
            Path target = resolveWithinWorkdir(op.path());
            switch (op.type()) {
                case ADD -> {
                    if (Files.exists(target)) {
                        throw new IllegalArgumentException("Datei existiert bereits: " + op.path());
                    }
                    writes.add(new PlannedWrite(target, addContent(op.body())));
                }
                case UPDATE -> writes.add(new PlannedWrite(target, updateContent(target, op.path(), op.body())));
                case DELETE -> {
                    if (!Files.exists(target)) {
                        throw new IllegalArgumentException("Datei nicht gefunden: " + op.path());
                    }
                    deletes.add(target);
                }
            }
        }
        for (PlannedWrite write : writes) {
            Path parent = write.target().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(write.target(), write.content(), StandardCharsets.UTF_8);
        }
        for (Path delete : deletes) {
            Files.delete(delete);
        }
    }

    private String addContent(List<String> body) {
        List<String> content = new ArrayList<>(body);
        while (!content.isEmpty() && content.get(content.size() - 1).isBlank()) {
            content.remove(content.size() - 1);
        }
        if (content.isEmpty()) {
            return "";
        }
        return String.join("\n", content) + "\n";
    }

    private String updateContent(Path target, String displayPath, List<String> body) throws IOException {
        if (!Files.isRegularFile(target)) {
            throw new IllegalArgumentException("Datei nicht gefunden: " + displayPath);
        }
        List<String> result = new ArrayList<>(Files.readAllLines(target, StandardCharsets.UTF_8));
        for (Hunk hunk : parseHunks(body)) {
            applyHunk(result, hunk, displayPath);
        }
        if (result.isEmpty()) {
            return "";
        }
        return String.join("\n", result) + "\n";
    }

    private List<Hunk> parseHunks(List<String> body) {
        List<Hunk> hunks = new ArrayList<>();
        List<Entry> current = null;
        for (String line : body) {
            if (line.startsWith("@@")) {
                current = new ArrayList<>();
                hunks.add(new Hunk(current));
            } else if (current != null) {
                if (line.isEmpty()) {
                    continue;
                }
                switch (line.charAt(0)) {
                    case ' ' -> current.add(new Entry(false, false, line.substring(1)));
                    case '-' -> current.add(new Entry(true, false, line.substring(1)));
                    case '+' -> current.add(new Entry(false, true, line.substring(1)));
                    default -> throw new IllegalArgumentException("Ungültige Zeile im Patch: " + line);
                }
            }
        }
        if (hunks.isEmpty()) {
            throw new IllegalArgumentException("Update-Block enthält keine '@@'-Hunks.");
        }
        return hunks;
    }

    private void applyHunk(List<String> fileLines, Hunk hunk, String displayPath) {
        List<String> oldLines = hunk.oldLines();
        int index = findMatch(fileLines, oldLines);
        if (index == -1) {
            String hint = oldLines.isEmpty() ? "(leerer Hunk)" : "'" + oldLines.stream().filter(l -> !l.isBlank()).findFirst().orElse("") + "'";
            throw new IllegalArgumentException("Hunk konnte nicht angewendet werden (" + displayPath + "): " + hint);
        }
        List<String> updated = new ArrayList<>(fileLines.size() + hunk.newLines().size());
        updated.addAll(fileLines.subList(0, index));
        updated.addAll(hunk.newLines());
        updated.addAll(fileLines.subList(index + oldLines.size(), fileLines.size()));
        fileLines.clear();
        fileLines.addAll(updated);
    }

    private int findMatch(List<String> lines, List<String> oldLines) {
        if (oldLines.isEmpty()) {
            return 0;
        }
        outer:
        for (int i = 0; i <= lines.size() - oldLines.size(); i++) {
            for (int j = 0; j < oldLines.size(); j++) {
                if (!lines.get(i + j).equals(oldLines.get(j))) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private Path resolveWithinWorkdir(String requested) {
        if (requested == null || requested.isBlank()) {
            throw new IllegalArgumentException("Pfad darf nicht leer sein.");
        }
        Path resolved = workdir.resolve(requested.replace('\\', '/')).normalize();
        if (!resolved.startsWith(workdir)) {
            throw new IllegalArgumentException(TRAVERSAL_ERROR);
        }
        return resolved;
    }

    private String summary(List<FileOperation> operations) {
        int added = 0;
        int updated = 0;
        int deleted = 0;
        for (FileOperation op : operations) {
            switch (op.type()) {
                case ADD -> added++;
                case UPDATE -> updated++;
                case DELETE -> deleted++;
            }
        }
        return "Patch angewendet: " + added + " Datei(en) angelegt, " + updated + " geändert, " + deleted + " gelöscht.";
    }

    private enum OperationType {
        ADD, UPDATE, DELETE
    }

    private record FileOperation(OperationType type, String path, List<String> body) {
    }

    private record Hunk(List<Entry> entries) {

        List<String> oldLines() {
            return entries.stream().filter(entry -> !entry.added()).map(Entry::text).toList();
        }

        List<String> newLines() {
            return entries.stream().filter(entry -> !entry.removed()).map(Entry::text).toList();
        }
    }

    private record Entry(boolean removed, boolean added, String text) {
    }

    private record PlannedWrite(Path target, String content) {
    }
}
