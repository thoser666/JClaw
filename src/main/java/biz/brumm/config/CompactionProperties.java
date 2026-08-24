package biz.brumm.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Konfiguration für Kontext-Compaction (P1-10).
 *
 * @param enabled         Compaction aktivieren (Deny-by-Default)
 * @param threshold       Mindestanzahl Nachrichten, bevor Compaction ausgelöst wird
 * @param retainCount     Anzahl der jüngsten Nachrichten, die nie komprimiert werden
 * @param summaryPrompt   System-Prompt für die Zusammenfassung
 */
@ConfigurationProperties(prefix = "jclaw.compaction")
public record CompactionProperties(boolean enabled, int threshold, int retainCount, String summaryPrompt) {

    public CompactionProperties {
        if (threshold <= 0) {
            threshold = 20;
        }
        if (retainCount <= 0) {
            retainCount = 4;
        }
        if (summaryPrompt == null || summaryPrompt.isBlank()) {
            summaryPrompt = """
                    Du bist ein präziser Assistent. Fasse den folgenden Konversationsverlauf in 3-5 Sätzen zusammen.
                    Behalte die wichtigsten Fakten, Benutzeranforderungen und getroffenen Entscheidungen bei.
                    Antworte auf Deutsch.
                    """;
        }
    }
}
