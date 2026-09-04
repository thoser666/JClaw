package biz.brumm.infrastructure.adapter.out.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Überwacht den Memory-Vault-Ordner auf neue oder geänderte {@code .md}-Dateien
 * (bidirektionaler Sync, P4-02). User-Änderungen im Vault werden so erkannt und
 * über den {@code onChange}-Callback zurück in die Konversation ingestet.
 *
 * <p>Folgt dem Muster von {@code Json5ConfigWatcher} und wird nur erzeugt, wenn
 * das Vault-Feature aktiviert ist.</p>
 */
public class MemoryVaultWatcher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MemoryVaultWatcher.class);

    private static final long DEBOUNCE_MILLIS = 500;

    private final Path vaultDir;
    private final Consumer<Path> onChange;
    private final ExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private WatchService watchService;

    /**
     * @param vaultDir Verzeichnis, das überwacht werden soll
     * @param onChange Callback bei erkannter {@code .md}-Änderung (Hintergrund-Thread)
     */
    public MemoryVaultWatcher(Path vaultDir, Consumer<Path> onChange) {
        this.vaultDir = vaultDir;
        this.onChange = onChange;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "memory-vault-watcher");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Startet die Überwachung im Hintergrund-Thread.
     *
     * @throws IOException wenn der WatchService nicht erstellt werden kann
     */
    public void start() throws IOException {
        Files.createDirectories(vaultDir);
        watchService = FileSystems.getDefault().newWatchService();
        vaultDir.register(watchService,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY);
        log.info("Memory-Vault-Überwachung gestartet für: {}", vaultDir.toAbsolutePath());

        executor.submit(this::watchLoop);
    }

    private void watchLoop() {
        while (running.get()) {
            try {
                WatchKey key = watchService.poll(1, TimeUnit.SECONDS);
                if (key == null) {
                    continue;
                }

                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.context() instanceof Path changedFile) {
                        if (changedFile.toString().endsWith(".md")) {
                            log.info("Memory-Vault-Datei geändert: {} — Sync in {} ms.",
                                    changedFile, DEBOUNCE_MILLIS);

                            Thread.sleep(DEBOUNCE_MILLIS);

                            if (running.get()) {
                                try {
                                    onChange.accept(vaultDir.resolve(changedFile));
                                } catch (Exception e) {
                                    log.error("Fehler beim Vault-Sync von {}: {}", changedFile, e.getMessage(), e);
                                }
                            }
                        }
                    }
                }

                if (!key.reset()) {
                    log.warn("WatchKey nicht mehr gültig — Memory-Vault-Überwachung wird beendet.");
                    break;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                if (running.get()) {
                    log.error("Unerwarteter Fehler in der Vault-Überwachung: {}", e.getMessage(), e);
                }
            }
        }

        try {
            if (watchService != null) {
                watchService.close();
            }
        } catch (IOException e) {
            log.debug("Fehler beim Schließen des WatchService: {}", e.getMessage());
        }
        log.info("Memory-Vault-Überwachung beendet.");
    }

    @Override
    public void close() {
        running.set(false);
        executor.shutdownNow();
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
