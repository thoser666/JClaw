package biz.brumm.domain.model;

import java.time.Instant;

/**
 * Bindung zwischen einem externen Channel-Thread/DM und einer JClaw-Session.
 *
 * @param id          Eindeutige Bindungs-ID
 * @param channelId   Zugehoeriger Channel
 * @param externalId  Externe Thread/DM-ID
 * @param sessionId   Zugehoerige JClaw-Session
 * @param bindingType DM oder THREAD
 * @param createdAt   Erstellungszeitpunkt
 */
public record ChannelBinding(String id, String channelId, String externalId,
                              String sessionId, BindingType bindingType,
                              Instant createdAt) {

    public ChannelBinding {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Bindungs-ID darf nicht leer sein.");
        }
        if (channelId == null || channelId.isBlank()) {
            throw new IllegalArgumentException("Channel-ID darf nicht leer sein.");
        }
        if (externalId == null || externalId.isBlank()) {
            throw new IllegalArgumentException("Externe ID darf nicht leer sein.");
        }
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("Session-ID darf nicht leer sein.");
        }
        if (bindingType == null) {
            throw new IllegalArgumentException("BindingType darf nicht null sein.");
        }
    }

    public static ChannelBinding of(String id, String channelId, String externalId,
                                     String sessionId, BindingType bindingType) {
        return new ChannelBinding(id, channelId, externalId, sessionId, bindingType,
                Instant.now());
    }
}
