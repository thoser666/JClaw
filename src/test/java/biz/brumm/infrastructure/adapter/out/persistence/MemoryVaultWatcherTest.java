package biz.brumm.infrastructure.adapter.out.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryVaultWatcherTest {

    @TempDir
    Path tempDir;

    private MemoryVaultWatcher watcher;

    @AfterEach
    void tearDown() {
        if (watcher != null) {
            watcher.close();
        }
    }

    @Test
    void detectsNewMarkdownFile() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean called = new AtomicBoolean(false);

        watcher = new MemoryVaultWatcher(tempDir, path -> {
            called.set(true);
            latch.countDown();
        });
        watcher.start();

        Files.writeString(tempDir.resolve("neu.md"), "---\nconversationId: x\n---\n\n**USER**\n\nHallo\n");

        boolean triggered = latch.await(3, TimeUnit.SECONDS);

        assertThat(triggered).isTrue();
        assertThat(called.get()).isTrue();
    }

    @Test
    void ignoresNonMarkdownFiles() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger callCount = new AtomicInteger(0);

        watcher = new MemoryVaultWatcher(tempDir, path -> {
            callCount.incrementAndGet();
            latch.countDown();
        });
        watcher.start();

        Files.writeString(tempDir.resolve("note.txt"), "kein markdown");

        boolean triggered = latch.await(1, TimeUnit.SECONDS);

        assertThat(triggered).isFalse();
        assertThat(callCount.get()).isEqualTo(0);
    }

    @Test
    void closeStopsWatcher() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        watcher = new MemoryVaultWatcher(tempDir, path -> latch.countDown());
        watcher.start();

        watcher.close();
        watcher = null;

        Thread.sleep(200);
        Files.writeString(tempDir.resolve("spaet.md"), "---\nconversationId: y\n---\n\n**USER**\n\nHi\n");

        boolean triggered = latch.await(1, TimeUnit.SECONDS);
        assertThat(triggered).isFalse();
    }
}
