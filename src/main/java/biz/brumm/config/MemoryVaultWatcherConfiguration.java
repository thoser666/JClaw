package biz.brumm.config;

import biz.brumm.domain.service.MemoryVaultIngestService;
import biz.brumm.infrastructure.adapter.out.persistence.MemoryVaultWatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Konfiguration für den bidirektionalen Memory-Vault-Sync (P4-02). Erzeugt den
 * {@link MemoryVaultWatcher}, der bei {@code .md}-Änderungen im Vault-Ordner den
 * {@link MemoryVaultIngestService} aufruft, damit User-Änderungen zurück in H2
 * fließen.
 *
 * <p>Nur aktiv, wenn das Vault-Feature per {@code jclaw.memory.vault.enabled=true}
 * aktiviert ist (Deny-by-Default).</p>
 */
@Configuration
public class MemoryVaultWatcherConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MemoryVaultWatcherConfiguration.class);

    @Bean
    @ConditionalOnProperty(prefix = "jclaw.memory.vault", name = "enabled", havingValue = "true")
    public MemoryVaultWatcher memoryVaultWatcher(MemoryVaultProperties properties,
                                            MemoryVaultIngestService ingestService) {
        Path vaultDir = Path.of(properties.dir());
        log.info("Memory-Vault-Read-Back aktiviert — überwache: {}", vaultDir.toAbsolutePath());

        MemoryVaultWatcher watcher = new MemoryVaultWatcher(vaultDir, file -> {
            try {
                ingestService.ingest(file);
            } catch (Exception e) {
                log.error("Memory-Vault-Read-Back fehlgeschlagen: {}", e.getMessage());
            }
        });

        try {
            watcher.start();
        } catch (IOException e) {
            log.error("Konnte Memory-Vault-Überwachung nicht starten: {}", e.getMessage());
        }

        return watcher;
    }
}
