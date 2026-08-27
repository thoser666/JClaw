package biz.brumm.infrastructure.adapter.out.persistence;

import biz.brumm.domain.model.*;
import biz.brumm.domain.port.out.ChannelStore;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class H2ChannelStore implements ChannelStore {

    private static final Logger log = LoggerFactory.getLogger(H2ChannelStore.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public H2ChannelStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    // --- Channels ---

    @Override
    public Optional<Channel> findChannelById(String id) {
        List<Channel> results = jdbc.query(
                "SELECT id, name, type, enabled, config_json, created_at, updated_at FROM channel WHERE id = ?",
                channelRowMapper(), id);
        return results.stream().findFirst();
    }

    @Override
    public List<Channel> findAllChannels() {
        return jdbc.query(
                "SELECT id, name, type, enabled, config_json, created_at, updated_at FROM channel ORDER BY name",
                channelRowMapper());
    }

    @Override
    public List<Channel> findChannelsByType(ChannelType type) {
        return jdbc.query(
                "SELECT id, name, type, enabled, config_json, created_at, updated_at FROM channel WHERE type = ? ORDER BY name",
                channelRowMapper(), type.name());
    }

    @Override
    public Channel saveChannel(Channel channel) {
        int updated = jdbc.update(
                "UPDATE channel SET name = ?, type = ?, enabled = ?, config_json = ?, updated_at = ? WHERE id = ?",
                channel.name(), channel.type().name(), channel.enabled(),
                toJson(channel.config()), toTimestamp(Instant.now()), channel.id());
        if (updated == 0) {
            jdbc.update(
                    "INSERT INTO channel (id, name, type, enabled, config_json, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    channel.id(), channel.name(), channel.type().name(), channel.enabled(),
                    toJson(channel.config()), toTimestamp(channel.createdAt()), toTimestamp(Instant.now()));
            log.info("Neuen Channel erstellt: '{}' (id={}).", channel.name(), channel.id());
        } else {
            log.info("Channel '{}' aktualisiert.", channel.name());
        }
        return channel;
    }

    @Override
    public void deleteChannelById(String id) {
        jdbc.update("DELETE FROM channel WHERE id = ?", id);
        log.info("Channel '{}' geloescht.", id);
    }

    // --- Bindungen ---

    @Override
    public Optional<ChannelBinding> findBindingById(String id) {
        List<ChannelBinding> results = jdbc.query(
                "SELECT id, channel_id, external_id, session_id, binding_type, created_at FROM channel_binding WHERE id = ?",
                bindingRowMapper(), id);
        return results.stream().findFirst();
    }

    @Override
    public List<ChannelBinding> findBindingsByChannel(String channelId) {
        return jdbc.query(
                "SELECT id, channel_id, external_id, session_id, binding_type, created_at FROM channel_binding WHERE channel_id = ?",
                bindingRowMapper(), channelId);
    }

    @Override
    public Optional<ChannelBinding> findBindingByExternalId(String channelId, String externalId) {
        List<ChannelBinding> results = jdbc.query(
                "SELECT id, channel_id, external_id, session_id, binding_type, created_at FROM channel_binding WHERE channel_id = ? AND external_id = ?",
                bindingRowMapper(), channelId, externalId);
        return results.stream().findFirst();
    }

    @Override
    public ChannelBinding saveBinding(ChannelBinding binding) {
        jdbc.update(
                "INSERT INTO channel_binding (id, channel_id, external_id, session_id, binding_type, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                binding.id(), binding.channelId(), binding.externalId(),
                binding.sessionId(), binding.bindingType().name(), toTimestamp(binding.createdAt()));
        log.info("Channel-Bindung erstellt: {} -> {}", binding.externalId(), binding.sessionId());
        return binding;
    }

    @Override
    public void deleteBindingById(String id) {
        jdbc.update("DELETE FROM channel_binding WHERE id = ?", id);
    }

    // --- Nachrichten ---

    @Override
    public ChannelMessage saveMessage(ChannelMessage message) {
        jdbc.update(
                "INSERT INTO channel_message (id, channel_id, external_id, direction, content, sender_id, sender_name, thread_id, session_id, timestamp) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                message.id(), message.channelId(), message.externalId(),
                message.direction().name(), message.content(),
                message.senderId(), message.senderName(),
                message.threadId(), message.sessionId(), toTimestamp(message.timestamp()));
        return message;
    }

    @Override
    public List<ChannelMessage> findMessagesBySession(String sessionId) {
        return jdbc.query(
                "SELECT id, channel_id, external_id, direction, content, sender_id, sender_name, thread_id, session_id, timestamp FROM channel_message WHERE session_id = ? ORDER BY timestamp",
                messageRowMapper(), sessionId);
    }

    @Override
    public List<ChannelMessage> findMessagesByChannel(String channelId, int limit) {
        return jdbc.query(
                "SELECT id, channel_id, external_id, direction, content, sender_id, sender_name, thread_id, session_id, timestamp FROM channel_message WHERE channel_id = ? ORDER BY timestamp DESC LIMIT ?",
                messageRowMapper(), channelId, limit);
    }

    // --- RowMapper ---

    private RowMapper<Channel> channelRowMapper() {
        return (rs, rowNum) -> new Channel(
                rs.getString("id"), rs.getString("name"),
                ChannelType.valueOf(rs.getString("type")),
                rs.getBoolean("enabled"),
                fromJson(rs.getString("config_json")),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")));
    }

    private RowMapper<ChannelBinding> bindingRowMapper() {
        return (rs, rowNum) -> new ChannelBinding(
                rs.getString("id"), rs.getString("channel_id"),
                rs.getString("external_id"), rs.getString("session_id"),
                BindingType.valueOf(rs.getString("binding_type")),
                toInstant(rs.getTimestamp("created_at")));
    }

    private RowMapper<ChannelMessage> messageRowMapper() {
        return (rs, rowNum) -> new ChannelMessage(
                rs.getString("id"), rs.getString("channel_id"),
                rs.getString("external_id"),
                MessageDirection.valueOf(rs.getString("direction")),
                rs.getString("content"),
                rs.getString("sender_id"), rs.getString("sender_name"),
                rs.getString("thread_id"), rs.getString("session_id"),
                toInstant(rs.getTimestamp("timestamp")));
    }

    // --- Hilfsmethoden ---

    @SuppressWarnings("unchecked")
    private String toJson(Map<String, Object> config) {
        if (config == null || config.isEmpty()) return "{}";
        try {
            return objectMapper.writeValueAsString(config);
        } catch (JacksonException e) {
            log.warn("Fehler beim Serialisieren der Channel-Konfiguration: {}", e.getMessage());
            return "{}";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fromJson(String json) {
        if (json == null || json.isBlank()) return new HashMap<>();
        try {
            return objectMapper.readValue(json, HashMap.class);
        } catch (JacksonException e) {
            log.warn("Fehler beim Deserialisieren der Channel-Konfiguration: {}", e.getMessage());
            return new HashMap<>();
        }
    }

    private static java.sql.Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : java.sql.Timestamp.from(instant);
    }

    private static Instant toInstant(java.sql.Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
