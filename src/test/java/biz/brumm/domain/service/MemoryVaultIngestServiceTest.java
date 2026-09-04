package biz.brumm.domain.service;

import biz.brumm.domain.model.ConversationMessage;
import biz.brumm.domain.port.out.ConversationStore;
import biz.brumm.infrastructure.adapter.out.persistence.MarkdownMemoryVault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class MemoryVaultIngestServiceTest {

    @Mock
    private ConversationStore conversationStore;

    private MemoryVaultIngestService service() {
        return new MemoryVaultIngestService(conversationStore);
    }

    @Test
    void ingestWritesParsedMessagesBackToStore(@TempDir Path dir) throws Exception {
        MemoryDocumentContent content = writeVaultFile(dir);
        MemoryVaultIngestService service = service();

        boolean result = service.ingest(content.file);

        assertThat(result).isTrue();
        ArgumentCaptor<String> idCaptor = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ConversationMessage>> msgCaptor = ArgumentCaptor.forClass(List.class);
        verify(conversationStore).saveAll(idCaptor.capture(), msgCaptor.capture());
        assertThat(idCaptor.getValue()).isEqualTo("conv-ingest");
        assertThat(msgCaptor.getValue())
                .extracting(ConversationMessage::role)
                .containsExactly("USER", "ASSISTANT");
    }

    @Test
    void ingestReturnsFalseForMissingFile(@TempDir Path dir) {
        MemoryVaultIngestService service = service();

        boolean result = service.ingest(dir.resolve("fehlt.md"));

        assertThat(result).isFalse();
        verifyNoInteractions(conversationStore);
    }

    @Test
    void ingestReturnsFalseForFileWithoutConversationId(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("no-id.md");
        Files.writeString(file, "---\ntitle: Ohne ID\n---\n\n# Ohne ID\n\n**USER**\n\nHallo\n");
        MemoryVaultIngestService service = service();

        boolean result = service.ingest(file);

        assertThat(result).isFalse();
        verify(conversationStore, never()).saveAll(
                org.mockito.Mockito.anyString(),
                org.mockito.Mockito.anyList());
    }

    private MemoryDocumentContent writeVaultFile(Path dir) throws Exception {
        String content = MarkdownMemoryVault.renderMessages(List.of(
                new ConversationMessage("USER", "Hallo aus Vault"),
                new ConversationMessage("ASSISTANT", "Hi!")));
        String file = """
                ---
                conversationId: conv-ingest
                title: 'Titel'
                createdAt: '2026-09-03T10:00:00Z'
                tags: []
                ---

                # Titel

                %s""".formatted(content);
        Path path = dir.resolve("conv-ingest.md");
        Files.writeString(path, file);
        return new MemoryDocumentContent(path);
    }

    private record MemoryDocumentContent(Path file) {
    }
}
