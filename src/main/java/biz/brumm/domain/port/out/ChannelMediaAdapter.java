package biz.brumm.domain.port.out;

import biz.brumm.domain.model.Channel;
import biz.brumm.domain.model.ChannelMessage;
import biz.brumm.domain.model.MediaAttachment;

/**
 * Erweiterung des {@link ChannelAdapter} für Medien-Nachrichten
 * (P3-07 Media-Message-Support).
 * <p>
 * Adapter, die Audio/Bilder/Dokumente versenden können, implementieren dieses
 * Interface und überschreiben {@link #supportsMedia()}. Der Default lehnt den
 * Versand ab, sodass bestehende Adapter unverändert bleiben.
 */
public interface ChannelMediaAdapter extends ChannelAdapter {

    /**
     * @return {@code true}, wenn dieser Adapter Medien-Anhänge versenden kann
     */
    default boolean supportsMedia() {
        return false;
    }

    /**
     * Sendet eine Medien-Nachricht über den Channel.
     *
     * @throws ChannelException wenn der Adapter keine Medien unterstützt oder der Versand fehlschlägt
     */
    default ChannelMessage sendMedia(Channel channel, ChannelMessage message, MediaAttachment attachment)
            throws ChannelException {
        throw new ChannelException(
                "Channel-Typ " + channelType() + " unterstuetzt keine Medien-Nachrichten.");
    }
}