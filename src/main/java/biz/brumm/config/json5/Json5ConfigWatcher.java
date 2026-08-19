package biz.brumm.config.json5;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Überwacht die JSON5-Konfigurationsdatei auf Änderungen und löst bei
 * einer Änderung einen Reload aus. Verwendet {@link WatchService} zur
 * plattformübergreifenden Dateiüberwachung.
 *
 * <p>Die Überwachung ist optional und wird über
 * {@code jclaw.config.hot-reload.enabled=true} aktiviert.</p>
 */
public class Json5ConfigWatcher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Json5ConfigWatcher.class);

    private static final long DEBOUNCE_MILLIS = 500;

    private final Path configDir;
    private final String fileName;
    private final Consumer<Path> onChange;
    private final ExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private WatchService watchService;

    /**
     * @param configDir Verzeichnis, das überwacht werden soll
     * @param fileName  Name der Konfigurationsdatei (z. B. {@code openclaw.json})
     * @param onChange  Callback bei erkannter Änderung (wird im Hintergrund-Thread aufgerufen)
     */
    public Json5ConfigWatcher(Path configDir, String fileName, Consumer<Path> onChange) {
        this.configDir = configDir;
        this.fileName = fileName;
        this.onChange = onChange;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "json5-config-watcher");
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
        watchService = FileSystems.getDefault().newWatchService();
        configDir.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY);
        log.info("JSON5-Konfigurationsüberwachung gestartet für: {}", configDir.toAbsolutePath());

        executor.submit(this::watchLoop);
    }

    private void watchLoop() {
        while (running.get()) {
            try {
                WatchKey key = watchService.poll(1, TimeUnit.SECONDS);
                if (key == null) continue;

                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.context() instanceof Path changedFile) {
                        if (changedFile.toString().equals(fileName)) {
                            log.info("Konfigurationsdatei geändert: {} — Reload in {} ms.",
                                    changedFile, DEBOUNCE_MILLIS);

                            // Debounce: Warten, bis die Datei fertig geschrieben ist
                            Thread.sleep(DEBOUNCE_MILLIS);

                            // Doppelten Event verarbeiten
                            if (running.get()) {
                                try {
                                    onChange.accept(configDir.resolve(fileName));
                                } catch (Exception e) {
                                    log.error("Fehler beim Reload der Konfiguration: {}", e.getMessage(), e);
                                }
                            }
                        }
                    }
                }

                if (!key.reset()) {
                    log.warn("WatchKey nicht mehr gültig — Überwachung wird beendet.");
                    break;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                if (running.get()) {
                    log.error("Unerwarteter Fehler in der Konfigurationsüberwachung: {}", e.getMessage(), e);
                }
            }
        }

        try {
            watchService.close();
        } catch (IOException e) {
            log.debug("Fehler beim Schließen des WatchService: {}", e.getMessage());
        }
        log.info("JSON5-Konfigurationsüberwachung beendet.");
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
