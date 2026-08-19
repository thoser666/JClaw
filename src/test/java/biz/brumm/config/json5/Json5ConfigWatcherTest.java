package biz.brumm.config.json5;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class Json5ConfigWatcherTest {

    @TempDir
    Path tempDir;

    private Json5ConfigWatcher watcher;

    @AfterEach
    void tearDown() {
        if (watcher != null) {
            watcher.close();
        }
    }

    @Test
    void detectsFileChange() throws IOException, InterruptedException {
        // Datei anlegen
        Files.writeString(tempDir.resolve("openclaw.json"), """
                { "agents": { "max-iterations": 8 } }
                """);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean called = new AtomicBoolean(false);

        watcher = new Json5ConfigWatcher(tempDir, "openclaw.json", path -> {
            called.set(true);
            latch.countDown();
        });
        watcher.start();

        // Datei ändern
        Files.writeString(tempDir.resolve("openclaw.json"), """
                { "agents": { "max-iterations": 12 } }
                """);

        boolean triggered = latch.await(3, TimeUnit.SECONDS);

        assertThat(triggered).isTrue();
        assertThat(called.get()).isTrue();
    }

    @Test
    void ignoresUnrelatedFiles() throws IOException, InterruptedException {
        Files.writeString(tempDir.resolve("openclaw.json"), """
                { "agents": { "max-iterations": 8 } }
                """);

        AtomicInteger callCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(1);

        watcher = new Json5ConfigWatcher(tempDir, "openclaw.json", path -> {
            callCount.incrementAndGet();
            latch.countDown();
        });
        watcher.start();

        // Anderes File ändern
        Files.writeString(tempDir.resolve("other.txt"), "irrelevant");

        boolean triggered = latch.await(1, TimeUnit.SECONDS);

        assertThat(triggered).isFalse();
        assertThat(callCount.get()).isEqualTo(0);
    }

    @Test
    void closeStopsWatcher() throws IOException, InterruptedException {
        Files.writeString(tempDir.resolve("openclaw.json"), """
                { "agents": { "max-iterations": 8 } }
                """);

        CountDownLatch startLatch = new CountDownLatch(1);
        watcher = new Json5ConfigWatcher(tempDir, "openclaw.json", path -> startLatch.countDown());
        watcher.start();

        watcher.close();
        watcher = null;

        // Datei ändern nach Close — sollte keinen Trigger auslösen
        Thread.sleep(200);
        Files.writeString(tempDir.resolve("openclaw.json"), """
                { "agents": { "max-iterations": 20 } }
                """);

        boolean triggered = startLatch.await(1, TimeUnit.SECONDS);
        assertThat(triggered).isFalse();
    }
}
