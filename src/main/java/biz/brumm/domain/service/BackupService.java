package biz.brumm.domain.service;

import biz.brumm.config.BackupProperties;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Backup & Restore (P4-10): sichert ein Quellverzeichnis (Daten, Konfiguration,
 * Memory-Vault) als ZIP-Archiv und stellt es wieder her.
 *
 * <p>Keine neuen Abhängigkeiten — reines JDK ({@link java.util.zip}). Das
 * Quell- und Zielverzeichnis sind konfigurierbar ({@code jclaw.backup.source} /
 * {@code jclaw.backup.dir}), im Test direkt über den Konstruktor setzbar.</p>
 */
@Service
public class BackupService {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());

    private final Path sourceDir;
    private final Path backupDir;

    public BackupService(Path sourceDir, Path backupDir) {
        this.sourceDir = sourceDir;
        this.backupDir = backupDir;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public BackupService(BackupProperties properties) {
        this(Paths.get(properties.source()), Paths.get(properties.dir()));
    }

    public Path sourceDir() {
        return sourceDir;
    }

    public Path backupDir() {
        return backupDir;
    }

    /**
     * Erstellt ein neues ZIP-Backup des Quellverzeichnisses.
     *
     * @return Pfad des erzeugten Archivs
     */
    public Path createBackup() throws IOException {
        Files.createDirectories(backupDir);
        String name = "backup-" + TS.format(Instant.now()) + ".zip";
        Path archive = backupDir.resolve(name);
        try (OutputStream fos = Files.newOutputStream(archive);
             ZipOutputStream zos = new ZipOutputStream(fos)) {
            if (Files.exists(sourceDir)) {
                try (Stream<Path> walk = Files.walk(sourceDir)) {
                    walk.filter(Files::isRegularFile).forEach(file -> {
                        String entryName = sourceDir.relativize(file).toString().replace('\\', '/');
                        try {
                            zos.putNextEntry(new ZipEntry(entryName));
                            Files.copy(file, zos);
                            zos.closeEntry();
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
                }
            }
        }
        return archive;
    }

    /**
     * Listet vorhandene Backup-Archive (neueste zuerst).
     */
    public List<BackupInfo> listBackups() throws IOException {
        List<BackupInfo> result = new ArrayList<>();
        if (!Files.exists(backupDir)) {
            return result;
        }
        try (Stream<Path> stream = Files.list(backupDir)) {
            stream.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".zip"))
                    .forEach(p -> {
                        try {
                            result.add(new BackupInfo(p.getFileName().toString(), p,
                                    Files.size(p), Files.getLastModifiedTime(p).toInstant()));
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        }
        result.sort((a, b) -> b.createdAt().compareTo(a.createdAt()));
        return result;
    }

    /**
     * Prüft, ob ein Archiv intakt ist (alle Entries lesbar).
     */
    public boolean verifyBackup(Path archive) {
        if (archive == null || !Files.exists(archive)) {
            return false;
        }
        try (ZipFile zf = new ZipFile(archive.toFile())) {
            var entries = zf.entries();
            while (entries.hasMoreElements()) {
                var e = entries.nextElement();
                if (e.isDirectory()) continue;
                // Entry-Daten lesen, um Korruption zu erkennen
                try (var is = zf.getInputStream(e)) {
                    byte[] buf = new byte[4096];
                    while (is.read(buf) != -1) {
                        // konsumieren
                    }
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Stellt ein Archiv in das Zielverzeichnis wieder her (überschreibt vorhandene Dateien).
     */
    public void restoreBackup(Path archive, Path targetDir) throws IOException {
        if (!Files.exists(archive)) {
            throw new IOException("Backup-Archiv nicht gefunden: " + archive);
        }
        Files.createDirectories(targetDir);
        try (ZipFile zf = new ZipFile(archive.toFile())) {
            var entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                Path target = targetDir.resolve(entry.getName()).normalize();
                if (!target.startsWith(targetDir.normalize())) {
                    throw new IOException("Ungültiger Entry-Pfad: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    try (var is = zf.getInputStream(entry)) {
                        Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
    }

    public record BackupInfo(String name, Path path, long size, Instant createdAt) {
    }
}