package biz.brumm.domain.model;

import java.time.Instant;

/**
 * Eine Nachricht, die über einen Channel gesendet oder empfangen wurde.
 *
 * @param id          Eindeutige Nachrichten-ID (intern)
 * @param channelId   Zugehöriger Channel
 * @param externalId  ID der Nachricht auf der externen Plattform
 * @param direction   INBOUND (von Plattform) oder OUTBOUND (an Plattform)
 * @param content     Nachrichteninhalt
 * @param senderId    Absender-ID
 * @param senderName  Absender-Anzeigename
 * @param threadId    Thread/Topic-ID (fuer Thread-Bindung)
 * @param sessionId   Zugehoerige JClaw-Session
 * @param timestamp   Zeitpunkt des Sendens/Empfangens
 */
public record ChannelMessage(String id, String channelId, String externalId,
                              MessageDirection direction, String content,
                              String senderId, String senderName,
                              String threadId, String sessionId,
                              Instant timestamp) {

    public ChannelMessage {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Nachrichten-ID darf nicht leer sein.");
        }
        if (channelId == null || channelId.isBlank()) {
            throw new IllegalArgumentException("Channel-ID darf nicht leer sein.");
        }
        if (direction == null) {
            throw new IllegalArgumentException("Richtung darf nicht null sein.");
        }
        if (content == null) {
            content = "";
        }
        if (timestamp == null) {
            timestamp = Instant.now();
        }
    }

    /**
     * Erstellt eine eingehende Nachricht.
     */
    public static ChannelMessage inbound(String channelId, String externalId, String content,
                                          String senderId, String senderName,
                                          String threadId, String sessionId) {
        return new ChannelMessage(
                java.util.UUID.randomUUID().toString(),
                channelId, externalId, MessageDirection.INBOUND,
                content, senderId, senderName, threadId, sessionId, Instant.now());
    }

    /**
     * Erstellt eine ausgehende Nachricht.
     */
    public static ChannelMessage outbound(String channelId, String content,
                                           String threadId, String sessionId) {
        return new ChannelMessage(
                java.util.UUID.randomUUID().toString(),
                channelId, null, MessageDirection.OUTBOUND,
                content, null, null, threadId, sessionId, Instant.now());
    }

    public ChannelMessage withExternalId(String externalId) {
        return new ChannelMessage(id, channelId, externalId, direction,
                content, senderId, senderName, threadId, sessionId, timestamp);
    }
}
