package biz.brumm.domain.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class BackupServiceTest {

    @TempDir
    Path sourceDir;

    @TempDir
    Path backupDir;

    @Test
    void createBackupZipsSourceFiles() throws Exception {
        Files.writeString(sourceDir.resolve("a.txt"), "hello");
        Files.createDirectories(sourceDir.resolve("sub"));
        Files.writeString(sourceDir.resolve("sub/b.txt"), "world");

        BackupService service = new BackupService(sourceDir, backupDir);
        Path archive = service.createBackup();

        assertThat(Files.exists(archive)).isTrue();
        assertThat(archive.getFileName().toString()).startsWith("backup-").endsWith(".zip");
        assertThat(service.verifyBackup(archive)).isTrue();
        assertThat(service.listBackups()).hasSize(1);
    }

    @Test
    void listBackupsEmptyWhenNoDir() throws Exception {
        Path emptyBackup = backupDir.resolve("none");
        BackupService service = new BackupService(sourceDir, emptyBackup);
        assertThat(service.listBackups()).isEmpty();
    }

    @Test
    void verifyBackupFalseForMissingFile() {
        BackupService service = new BackupService(sourceDir, backupDir);
        assertThat(service.verifyBackup(backupDir.resolve("gibt-es-nicht.zip"))).isFalse();
    }

    @Test
    void restoreBackupExtractsFiles() throws Exception {
        Files.writeString(sourceDir.resolve("a.txt"), "hello");
        BackupService service = new BackupService(sourceDir, backupDir);
        Path archive = service.createBackup();

        Path target = backupDir.resolve("restored");
        service.restoreBackup(archive, target);

        assertThat(Files.readString(target.resolve("a.txt"))).isEqualTo("hello");
    }

    @Test
    void restoreRejectsPathTraversal() throws Exception {
        // Manuell ein Archiv mit traversal entry erstellen
        Path evil = backupDir.resolve("evil.zip");
        try (var zos = new java.util.zip.ZipOutputStream(Files.newOutputStream(evil))) {
            zos.putNextEntry(new java.util.zip.ZipEntry("../escape.txt"));
            zos.write("x".getBytes());
            zos.closeEntry();
        }
        BackupService service = new BackupService(sourceDir, backupDir);
        org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
                () -> service.restoreBackup(evil, backupDir.resolve("target")));
    }
}