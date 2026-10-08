package biz.brumm.domain.service;

import biz.brumm.domain.model.*;
import biz.brumm.domain.port.out.ChannelAdapter;
import biz.brumm.domain.port.out.ChannelStore;
import biz.brumm.domain.port.out.PluginHookDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Service für Channel-Verwaltung: CRUD, Nachrichten senden, Bindungen verwalten.
 */
public class ChannelService {

    private static final Logger log = LoggerFactory.getLogger(ChannelService.class);

    private final ChannelStore channelStore;
    private final CredentialLeakGuard credentialLeakGuard;
    private final PluginHookDispatcher pluginHooks;
    private final Map<ChannelType, ChannelAdapter> adapters = new EnumMap<>(ChannelType.class);

    public ChannelService(ChannelStore channelStore, List<ChannelAdapter> adapterList,
                          CredentialLeakGuard credentialLeakGuard, PluginHookDispatcher pluginHooks) {
        this.channelStore = channelStore;
        this.credentialLeakGuard = credentialLeakGuard;
        this.pluginHooks = pluginHooks;
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
        Channel saved = channelStore.saveChannel(channel);
        credentialLeakGuard.refresh();
        return saved;
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

        String sanitized = credentialLeakGuard.redact(content);

        // message_sending (OpenClaw): blockbarer Hook vor dem eigentlichen Versand
        PluginHookDispatcher.HookOutcome sendDecision = pluginHooks.dispatch("message_sending",
                Map.of("channelId", channel.id(), "sessionId", sessionId == null ? "" : sessionId,
                        "threadId", threadId == null ? "" : threadId, "content", sanitized));
        if (sendDecision.blocked()) {
            log.warn("Nachricht an '{}' blockiert durch message_sending-Hook: {}", channel.name(), sendDecision.message());
            throw new ChannelAdapter.ChannelException(
                    "Versand blockiert durch message_sending-Hook: " + sendDecision.message());
        }

        ChannelMessage outbound = ChannelMessage.outbound(channel.id(), sanitized, threadId, sessionId);
        ChannelMessage sent = adapter.send(channel, outbound);
        channelStore.saveMessage(sent);
        log.info("Nachricht an '{}' gesendet: {}", channel.name(),
                sanitized.length() > 50 ? sanitized.substring(0, 50) + "..." : sanitized);

        // message_sent (OpenClaw): beobachtet nach erfolgreichem Versand
        pluginHooks.dispatch("message_sent",
                Map.of("channelId", channel.id(), "sessionId", sessionId == null ? "" : sessionId,
                        "threadId", threadId == null ? "" : threadId, "content", sanitized,
                        "messageId", sent.id()));
        return sent;
    }

    // --- Eingehende Nachricht verarbeiten ---

    public void handleInbound(ChannelMessage message) {
        // message_received (OpenClaw): blockbarer Hook — blockiert, wird die Nachricht
        // verworfen (nicht gespeichert, nicht weiterverarbeitet).
        PluginHookDispatcher.HookOutcome received = pluginHooks.dispatch("message_received",
                Map.of("channelId", message.channelId(), "sessionId", message.sessionId() == null ? "" : message.sessionId(),
                        "threadId", message.threadId() == null ? "" : message.threadId(), "content", message.content(),
                        "direction", "inbound"));
        if (received.blocked()) {
            log.warn("Eingehende Nachricht auf '{}' verworfen (message_received-Hook blockiert): {}",
                    message.channelId(), received.message());
            return;
        }
        channelStore.saveMessage(message);
        String preview = credentialLeakGuard.redact(message.content());
        log.info("Eingehende Nachricht auf '{}': {}", message.channelId(),
                preview.length() > 50 ? preview.substring(0, 50) + "..." : preview);
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
