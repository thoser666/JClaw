package biz.brumm.domain.port.out;

import biz.brumm.domain.model.Channel;
import biz.brumm.domain.model.ChannelMessage;

/**
 * Schnittstelle für Channel-Adapter (z.B. Telegram, Slack, Discord).
 * Jeder Channel-Typ implementiert diese Schnittstelle.
 */
public interface ChannelAdapter {

    /**
     * Gibt den Channel-Typ zurück, den dieser Adapter unterstützt.
     */
    biz.brumm.domain.model.ChannelType channelType();

    /**
     * Sendet eine Nachricht über den Channel.
     *
     * @param channel Die Channel-Konfiguration
     * @param message Die zu sendende Nachricht
     * @return Die gesendete Nachricht mit externer ID
     */
    ChannelMessage send(Channel channel, ChannelMessage message) throws ChannelException;

    /**
     * Prüft, ob der Adapter verbunden und betriebsbereit ist.
     */
    boolean isAvailable(Channel channel);

    /**
     * Optional: Startet den Empfang von Nachrichten (z.B. Polling, WebSocket).
     * Nicht alle Adapter unterstützen das (z.B. Webhook-basierte).
     */
    default void startReceiving(Channel channel, InboundMessageHandler handler) {
        // Default: nichts (adapter ist push-basiert oder unterstützt kein Polling)
    }

    /**
     * Optional: Stoppt den Empfang.
     */
    default void stopReceiving(Channel channel) {
        // Default: nichts
    }

    /**
     * Callback-Schnittstelle für eingehende Nachrichten.
     */
    @FunctionalInterface
    interface InboundMessageHandler {
        void onMessage(ChannelMessage message);
    }

    /**
     * Exception bei Channel-Fehlern.
     */
    class ChannelException extends Exception {
        public ChannelException(String message) {
            super(message);
        }

        public ChannelException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
