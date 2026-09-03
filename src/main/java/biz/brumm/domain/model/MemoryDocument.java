package biz.brumm.domain.model;

import java.time.Instant;
import java.util.List;

/**
 * Ein Langzeit-Memory-Dokument im Open Memory Vault (P4-02).
 *
 * <p>Repräsentiert eine materialisierte Konversation als Asset, das ausserhalb
 * des LLM-Contexts lebt: als lesbarer/edierbarer Markdown-Eintrag mit
 * YAML-Frontmatter in einem konfigurierbaren Vault-Ordner (z. B. via Tolaria
 * oder Obsidian am Menschen durchsuchbar). H2 bleibt die Quelle der Wahrheit —
 * das Vault ist ein menschenlesbarer Auszug, kein Ersatz.</p>
 *
 * @param conversationId Konversations-/Context-ID, aus der das Dokument entstand
 * @param title          Generierter Titel des Dokuments
 * @param createdAt      Zeitpunkt der Materialisierung
 * @param tags           Tags für Kategorisierung (z. B. aus dem Frontmatter)
 * @param content        Markdown-Inhalt (menschenlesbarer Verlauf / Zusammenfassung)
 */
public record MemoryDocument(
        String conversationId,
        String title,
        Instant createdAt,
        List<String> tags,
        String content) {
}
