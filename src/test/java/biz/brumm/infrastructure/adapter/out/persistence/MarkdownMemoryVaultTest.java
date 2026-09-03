package biz.brumm.infrastructure.adapter.out.persistence;

import biz.brumm.config.MemoryVaultProperties;
import biz.brumm.domain.model.MemoryDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownMemoryVaultTest {

    @TempDir
    Path tempDir;

    private MarkdownMemoryVault vault(Path dir) {
        return new MarkdownMemoryVault(new MemoryVaultProperties(true, dir.toString()));
    }

    @Test
    void storeWritesMarkdownFileWithFrontmatter() throws Exception {
        MarkdownMemoryVault vault = vault(tempDir);
        MemoryDocument doc = new MemoryDocument("conv-123", "Ein Titel", Instant.parse("2026-09-03T10:00:00Z"),
                List.of("wichtig"), "Erste Zeile.\nZweite Zeile.");

        vault.store(doc);

        Path file = tempDir.resolve("conv-123.md");
        assertThat(file).exists();
        String text = Files.readString(file);
        assertThat(text).startsWith("---\n");
        assertThat(text).contains("conversationId: conv-123");
        assertThat(text).contains("title: Ein Titel");
        assertThat(text).contains("createdAt");
        assertThat(text).contains("2026-09-03T10:00:00Z");
        assertThat(text).contains("# Ein Titel");
        assertThat(text).contains("Erste Zeile.");
    }

    @Test
    void listRoundTripsStoredDocuments() {
        MarkdownMemoryVault vault = vault(tempDir);
        Instant createdAt = Instant.parse("2026-09-03T10:00:00Z");
        vault.store(new MemoryDocument("conv-abc", "Titel A", createdAt, List.of("a"), "Inhalt A"));

        List<MemoryDocument> docs = vault.list();

        assertThat(docs).hasSize(1);
        MemoryDocument doc = docs.get(0);
        assertThat(doc.conversationId()).isEqualTo("conv-abc");
        assertThat(doc.title()).isEqualTo("Titel A");
        assertThat(doc.createdAt()).isEqualTo(createdAt);
        assertThat(doc.content()).isEqualTo("Inhalt A");
    }

    @Test
    void storeIsIdempotentPerConversationId() {
        MarkdownMemoryVault vault = vault(tempDir);
        vault.store(new MemoryDocument("conv-x", "Alt", Instant.now(), List.of(), "alt"));
        vault.store(new MemoryDocument("conv-x", "Neu", Instant.now(), List.of(), "neu"));

        List<MemoryDocument> docs = vault.list();
        assertThat(docs).hasSize(1);
        assertThat(docs.get(0).title()).isEqualTo("Neu");
        assertThat(docs.get(0).content()).isEqualTo("neu");
    }

    @Test
    void listIsEmptyForMissingDirectory() {
        MarkdownMemoryVault vault = vault(tempDir.resolve("nicht-vorhanden"));
        assertThat(vault.list()).isEmpty();
    }

    @Test
    void slugifyNormalizesUnsafeStrings() {
        assertThat(MarkdownMemoryVault.slugify("Meine Konversation!! 123")).isEqualTo("meine-konversation-123");
        assertThat(MarkdownMemoryVault.slugify("  ")).isEqualTo("memory");
        assertThat(MarkdownMemoryVault.slugify("")).isEqualTo("memory");
    }

    @Test
    void listIgnoresNonMarkdownFiles() throws Exception {
        MarkdownMemoryVault vault = vault(tempDir);
        Files.writeString(tempDir.resolve("note.txt"), "kein markdown");
        assertThat(vault.list()).isEmpty();
    }
}
