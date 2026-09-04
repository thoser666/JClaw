package biz.brumm.infrastructure.adapter.out.persistence;

import biz.brumm.config.MemoryVaultProperties;
import biz.brumm.domain.model.ConversationMessage;
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

    @Test
    void renderAndParseMessagesRoundTrip() {
        List<ConversationMessage> messages = List.of(
                new ConversationMessage("USER", "Hallo Welt"),
                new ConversationMessage("ASSISTANT", "Hallo!"));
        Instant createdAt = Instant.parse("2026-09-03T10:00:00Z");
        MemoryDocument doc = new MemoryDocument("conv-round", "Titel", createdAt, List.of(),
                MarkdownMemoryVault.renderMessages(messages));

        List<ConversationMessage> parsed = MarkdownMemoryVault.parseMessages(doc.content());

        assertThat(parsed).hasSize(2);
        assertThat(parsed.get(0).role()).isEqualTo("USER");
        assertThat(parsed.get(0).text()).isEqualTo("Hallo Welt");
        assertThat(parsed.get(1).role()).isEqualTo("ASSISTANT");
        assertThat(parsed.get(1).text()).isEqualTo("Hallo!");
    }

    @Test
    void parseMessagesHandlesMultilineAndNull() {
        String content = MarkdownMemoryVault.renderMessages(List.of(
                new ConversationMessage("USER", "Zeile eins\nZeile zwei")));

        List<ConversationMessage> parsed = MarkdownMemoryVault.parseMessages(content);
        assertThat(parsed).hasSize(1);
        assertThat(parsed.get(0).text()).isEqualTo("Zeile eins\nZeile zwei");
        assertThat(MarkdownMemoryVault.parseMessages(null)).isEmpty();
        assertThat(MarkdownMemoryVault.parseMessages("")).isEmpty();
    }

    @Test
    void readDocumentParsesStoredFile() throws Exception {
        MarkdownMemoryVault vault = vault(tempDir);
        Instant createdAt = Instant.parse("2026-09-03T10:00:00Z");
        vault.store(new MemoryDocument("conv-read", "Titel", createdAt, List.of("x"),
                MarkdownMemoryVault.renderMessages(List.of(
                        new ConversationMessage("USER", "Hallo")))));

        var read = MarkdownMemoryVault.readDocument(tempDir.resolve("conv-read.md"));

        assertThat(read).isPresent();
        assertThat(read.get().conversationId()).isEqualTo("conv-read");
        assertThat(read.get().title()).isEqualTo("Titel");
        assertThat(read.get().createdAt()).isEqualTo(createdAt);
        assertThat(MarkdownMemoryVault.parseMessages(read.get().content()))
                .hasSize(1).first().extracting(ConversationMessage::role).isEqualTo("USER");
    }
}
