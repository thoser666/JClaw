package biz.brumm.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Konfiguration für Backup & Restore (P4-10):
 * {@code jclaw.backup.source} (Quellverzeichnis) und {@code jclaw.backup.dir} (Zielverzeichnis).
 */
@ConfigurationProperties(prefix = "jclaw.backup")
public record BackupProperties(String source, String dir) {
    public BackupProperties {
        if (source == null || source.isBlank()) source = "./data";
        if (dir == null || dir.isBlank()) dir = "./backups";
    }
}