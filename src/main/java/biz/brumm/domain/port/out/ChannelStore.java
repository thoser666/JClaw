package biz.brumm.domain.port.out;

import biz.brumm.domain.model.Channel;
import biz.brumm.domain.model.ChannelBinding;
import biz.brumm.domain.model.ChannelMessage;

import java.util.List;
import java.util.Optional;

/**
 * Persistenz-Schnittstelle für Channels, Bindungen und Nachrichten.
 */
public interface ChannelStore {

    // --- Channels ---

    Optional<Channel> findChannelById(String id);

    List<Channel> findAllChannels();

    List<Channel> findChannelsByType(biz.brumm.domain.model.ChannelType type);

    Channel saveChannel(Channel channel);

    void deleteChannelById(String id);

    // --- Bindungen ---

    Optional<ChannelBinding> findBindingById(String id);

    List<ChannelBinding> findBindingsByChannel(String channelId);

    Optional<ChannelBinding> findBindingByExternalId(String channelId, String externalId);

    ChannelBinding saveBinding(ChannelBinding binding);

    void deleteBindingById(String id);

    // --- Nachrichten ---

    ChannelMessage saveMessage(ChannelMessage message);

    List<ChannelMessage> findMessagesBySession(String sessionId);

    List<ChannelMessage> findMessagesByChannel(String channelId, int limit);
}
