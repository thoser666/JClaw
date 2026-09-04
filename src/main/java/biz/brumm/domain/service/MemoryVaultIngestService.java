package biz.brumm.domain.service;

import biz.brumm.domain.model.MemoryDocument;
import biz.brumm.domain.port.out.ConversationStore;
import biz.brumm.infrastructure.adapter.out.persistence.MarkdownMemoryVault;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Stellt geänderte Memory-Vault-Dokumente wieder in die Konversation zurück
 * (bidirektionaler Sync, P4-02). Wird vom {@code MemoryVaultWatcher} bei
 * einer {@code .md}-Änderung aufgerufen: Die Datei wird als
 * {@link MemoryDocument} gelesen und ihr Inhalt über den
 * {@link ConversationStore} wieder in H2 ingestet (H2 bleibt Quelle der Wahrheit).
 */
@Service
public class MemoryVaultIngestService {

    private static final Logger log = LoggerFactory.getLogger(MemoryVaultIngestService.class);

    private final ConversationStore conversationStore;

    public MemoryVaultIngestService(ConversationStore conversationStore) {
        this.conversationStore = conversationStore;
    }

    /**
     * Liest eine Vault-Datei und ersetzt die gespeicherten Nachrichten der
     * enthaltenen {@code conversationId}.
     *
     * @param file geänderte {@code .md}-Datei
     * @return {@code true}, wenn ein Dokument verarbeitet wurde, sonst {@code false}
     */
    public boolean ingest(Path file) {
        Optional<MemoryDocument> document = MarkdownMemoryVault.readDocument(file);
        if (document.isEmpty()) {
            return false;
        }
        MemoryDocument doc = document.get();
        if (doc.conversationId() == null || doc.conversationId().isBlank()) {
            log.warn("Memory-Vault-Read-Back: {} hat keine conversationId — übersprungen.", file);
            return false;
        }
        conversationStore.saveAll(doc.conversationId(),
                MarkdownMemoryVault.parseMessages(doc.content()));
        log.info("Memory-Vault-Read-Back: {} nach H2 ingestet ({}).", file, doc.conversationId());
        return true;
    }
}
