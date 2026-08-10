package biz.brumm.config;

import biz.brumm.infrastructure.adapter.out.ai.tool.FileTool;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Konfiguration für die Datei-Werkzeuge des Agenten. Erst wenn {@code workdir} gesetzt ist,
 * werden die File-Tools ({@code readFile}, {@code listDirectory}, {@code writeFile}) registriert
 * (Deny-by-Default).
 *
 * @param workdir      Arbeitsverzeichnis, auf das sich die Datei-Werkzeuge beschränken.
 * @param maxReadBytes Maximale Dateigröße in Bytes, die gelesen werden darf (Standard: 1 MiB).
 */
@ConfigurationProperties(prefix = "jclaw.agent.filetool")
public record FileToolProperties(String workdir, Integer maxReadBytes) {

    public FileToolProperties {
        if (workdir != null && workdir.isBlank()) {
            throw new IllegalArgumentException("jclaw.agent.filetool.workdir darf nicht leer sein.");
        }
        if (maxReadBytes != null && maxReadBytes <= 0) {
            throw new IllegalArgumentException("jclaw.agent.filetool.max-read-bytes muss positiv sein.");
        }
    }

    public long effectiveMaxReadBytes() {
        return maxReadBytes == null ? FileTool.DEFAULT_MAX_READ_BYTES : maxReadBytes;
    }
}
