package biz.brumm.domain.service;

import biz.brumm.domain.model.ConversationMessage;
import biz.brumm.domain.model.MemoryDocument;
import biz.brumm.domain.port.out.ConversationStore;
import biz.brumm.domain.port.out.MemoryVaultStore;
import biz.brumm.infrastructure.adapter.out.persistence.MarkdownMemoryVault;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Orchestriert den Open Memory Vault (P4-02): materialisiert eine Konversation
 * als menschenlesbares Markdown-Dokument (Memory als Asset statt Cache) und
 * listet vorhandene Vault-Dokumente.
 *
 * <p>Der Vault-Adapter ist per {@code jclaw.memory.vault.enabled=true}
 * (Deny-by-Default) aktiviert. Ist kein Adapter verfügbar (Feature aus),
 * bleiben die Methoden No-ops bzw. liefern eine leere Liste.</p>
 */
@Service
public class MemoryVaultService {

    private final ConversationStore conversationStore;
    private final MemoryVaultStore vaultStore;

    public MemoryVaultService(ConversationStore conversationStore, ObjectProvider<MemoryVaultStore> vaultStoreProvider) {
        this.conversationStore = conversationStore;
        this.vaultStore = vaultStoreProvider.getIfAvailable();
    }

    /**
     * Materialisiert die Konversation einer {@code contextId} als Vault-Dokument.
     * Leere/fehlende Konversationen sowie ein deaktiviertes Vault-Feature werden
     * ignoriert.
     *
     * @return {@code true}, wenn ein Dokument geschrieben wurde, sonst {@code false}
     */
    public boolean syncConversation(String contextId) {
        if (vaultStore == null) {
            return false;
        }
        if (contextId == null || contextId.isBlank()) {
            return false;
        }
        List<ConversationMessage> messages = conversationStore.findByContextId(contextId);
        if (messages.isEmpty()) {
            return false;
        }
        String title = deriveTitle(messages);
        String content = MarkdownMemoryVault.renderMessages(messages);
        vaultStore.store(new MemoryDocument(contextId, title, Instant.now(), List.of(), content));
        return true;
    }

    /**
     * @return alle im Vault vorhandenen Dokumente (leer, wenn das Feature aus ist)
     */
    public List<MemoryDocument> listDocuments() {
        return vaultStore == null ? List.of() : vaultStore.list();
    }

    private String deriveTitle(List<ConversationMessage> messages) {
        for (ConversationMessage message : messages) {
            String text = message.text() == null ? "" : message.text().trim().replace('\n', ' ').trim();
            if (!text.isBlank()) {
                return text.length() > 60 ? text.substring(0, 60) + "…" : text;
            }
        }
        return "Konversation";
    }
}
