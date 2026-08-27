package biz.brumm.domain.service;

import biz.brumm.domain.model.*;
import biz.brumm.domain.port.out.ChannelAdapter;
import biz.brumm.domain.port.out.ChannelStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Service für Channel-Verwaltung: CRUD, Nachrichten senden, Bindungen verwalten.
 */
public class ChannelService {

    private static final Logger log = LoggerFactory.getLogger(ChannelService.class);

    private final ChannelStore channelStore;
    private final Map<ChannelType, ChannelAdapter> adapters = new EnumMap<>(ChannelType.class);

    public ChannelService(ChannelStore channelStore, List<ChannelAdapter> adapterList) {
        this.channelStore = channelStore;
        for (ChannelAdapter adapter : adapterList) {
            adapters.put(adapter.channelType(), adapter);
            registriert(adapter.channelType());
        }
    }

    private void registriert(ChannelType type) {
        log.info("Channel-Adapter fuer {} registriert.", type);
    }

    // --- Channel CRUD ---

    public List<Channel> findAll() {
        return channelStore.findAllChannels();
    }

    public Optional<Channel> findById(String id) {
        return channelStore.findChannelById(id);
    }

    public Channel save(Channel channel) {
        return channelStore.saveChannel(channel);
    }

    public void delete(String id) {
        channelStore.deleteChannelById(id);
    }

    // --- Adapter ---

    public Optional<ChannelAdapter> getAdapter(ChannelType type) {
        return Optional.ofNullable(adapters.get(type));
    }

    public Set<ChannelType> availableAdapterTypes() {
        return adapters.keySet();
    }

    // --- Nachrichten senden ---

    public ChannelMessage send(Channel channel, String content, String threadId, String sessionId)
            throws ChannelAdapter.ChannelException {
        ChannelAdapter adapter = adapters.get(channel.type());
        if (adapter == null) {
            throw new ChannelAdapter.ChannelException(
                    "Kein Adapter fuer Channel-Typ " + channel.type() + " registriert.");
        }
        if (!adapter.isAvailable(channel)) {
            throw new ChannelAdapter.ChannelException(
                    "Channel '" + channel.name() + "' ist nicht verfuegbar.");
        }

        ChannelMessage outbound = ChannelMessage.outbound(channel.id(), content, threadId, sessionId);
        ChannelMessage sent = adapter.send(channel, outbound);
        channelStore.saveMessage(sent);
        log.info("Nachricht an '{}' gesendet: {}", channel.name(),
                content.length() > 50 ? content.substring(0, 50) + "..." : content);
        return sent;
    }

    // --- Eingehende Nachricht verarbeiten ---

    public void handleInbound(ChannelMessage message) {
        channelStore.saveMessage(message);
        log.info("Eingehende Nachricht auf '{}': {}", message.channelId(),
                message.content().length() > 50
                        ? message.content().substring(0, 50) + "..." : message.content());
    }

    // --- Bindungen ---

    public Optional<ChannelBinding> findBinding(String channelId, String externalId) {
        return channelStore.findBindingByExternalId(channelId, externalId);
    }

    public List<ChannelBinding> findBindingsByChannel(String channelId) {
        return channelStore.findBindingsByChannel(channelId);
    }

    public ChannelBinding createBinding(String channelId, String externalId,
                                         String sessionId, BindingType bindingType) {
        ChannelBinding binding = ChannelBinding.of(
                UUID.randomUUID().toString(), channelId, externalId,
                sessionId, bindingType);
        return channelStore.saveBinding(binding);
    }

    public void deleteBinding(String id) {
        channelStore.deleteBindingById(id);
    }

    // --- Nachrichten abfragen ---

    public List<ChannelMessage> getMessagesBySession(String sessionId) {
        return channelStore.findMessagesBySession(sessionId);
    }

    public List<ChannelMessage> getMessagesByChannel(String channelId, int limit) {
        return channelStore.findMessagesByChannel(channelId, limit);
    }
}
