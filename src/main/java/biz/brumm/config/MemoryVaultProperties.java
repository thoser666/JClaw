package biz.brumm.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Konfiguration für den Open Memory Vault (P4-02).
 *
 * @param enabled Vault aktiveren (Deny-by-Default)
 * @param dir     Verzeichnis, in das Markdown-Dokumente materialisiert werden
 */
@ConfigurationProperties(prefix = "jclaw.memory.vault")
public record MemoryVaultProperties(boolean enabled, String dir) {

    public MemoryVaultProperties {
        if (dir == null || dir.isBlank()) {
            dir = "./vault";
        }
    }
}
