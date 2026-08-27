package biz.brumm.domain.model;

import java.time.Instant;
import java.util.Map;

/**
 * Repräsentiert einen konfigurierten Nachrichten-Channel (z.B. Telegram, Slack).
 *
 * @param id        Eindeutige Channel-ID
 * @param name      Anzeigename
 * @param type      Channel-Typ (TELEGRAM, SLACK, ...)
 * @param enabled   Ob der Channel aktiv ist
 * @param config    Channel-spezifische Konfiguration (Tokens, Webhook-URLs, ...)
 * @param createdAt Erstellungszeitpunkt
 * @param updatedAt Letzter Aktualisierungszeitpunkt
 */
public record Channel(String id, String name, ChannelType type, boolean enabled,
                       Map<String, Object> config, Instant createdAt, Instant updatedAt) {

    public Channel {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Channel-ID darf nicht leer sein.");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Channel-Name darf nicht leer sein.");
        }
        if (type == null) {
            throw new IllegalArgumentException("ChannelType darf nicht null sein.");
        }
        if (config == null) {
            config = Map.of();
        }
    }

    public Channel withEnabled(boolean enabled) {
        return new Channel(id, name, type, enabled, config, createdAt, Instant.now());
    }

    public Channel withConfig(Map<String, Object> config) {
        return new Channel(id, name, type, enabled, config, createdAt, Instant.now());
    }
}
