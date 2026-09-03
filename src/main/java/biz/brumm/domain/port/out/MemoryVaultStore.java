package biz.brumm.domain.port.out;

import biz.brumm.domain.model.MemoryDocument;

import java.util.List;

/**
 * Speichert und listet Open-Memory-Vault-Dokumente (P4-02).
 *
 * <p>Vault-Dokumente sind menschenlesbare Markdown-Dateien mit YAML-Frontmatter
 * in einem konfigurierbaren Verzeichnis — sie materialisieren Konversations-
 * Memory als Asset, das Compaction, Neustarts und Session-Verluste überlebt.</p>
 */
public interface MemoryVaultStore {

    /**
     * Schreibt ein Memory-Dokument in den Vault (idempotent je conversationId).
     *
     * @param document zu speicherndes Dokument (darf nicht null sein)
     */
    void store(MemoryDocument document);

    /**
     * Listet alle aktuell im Vault vorhandenen Dokumente.
     *
     * @return Liste der Dokumente (leer, wenn der Vault leer/nicht vorhanden ist)
     */
    List<MemoryDocument> list();
}
