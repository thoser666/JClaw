package biz.brumm.infrastructure.adapter.in.web;

import biz.brumm.domain.model.*;
import biz.brumm.domain.port.out.ChannelAdapter;
import biz.brumm.domain.service.ChannelService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/channels")
public class ChannelRestController {

    private final ChannelService channelService;

    public ChannelRestController(ChannelService channelService) {
        this.channelService = channelService;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return channelService.findAll().stream().map(this::channelToMap).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String id) {
        return channelService.findById(id)
                .map(ch -> ResponseEntity.ok(channelToMap(ch)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        String name = (String) body.get("name");
        String typeStr = (String) body.get("type");
        Boolean enabled = body.get("enabled") instanceof Boolean b ? b : true;

        if (name == null || name.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "name ist erforderlich."));
        }
        if (typeStr == null || typeStr.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "type ist erforderlich."));
        }

        ChannelType type;
        try {
            type = ChannelType.valueOf(typeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unbekannter Channel-Typ: " + typeStr));
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> config = body.get("config") instanceof Map m ? m : Map.of();

        Channel channel = new Channel(
                java.util.UUID.randomUUID().toString(), name, type, enabled, config,
                Instant.now(), Instant.now());
        channel = channelService.save(channel);
        return ResponseEntity.ok(channelToMap(channel));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return channelService.findById(id).map(existing -> {
            String name = body.get("name") instanceof String s ? s : existing.name();
            Boolean enabled = body.get("enabled") instanceof Boolean b ? b : existing.enabled();

            @SuppressWarnings("unchecked")
            Map<String, Object> config = body.get("config") instanceof Map m ? m : existing.config();

            Channel updated = new Channel(existing.id(), name, existing.type(), enabled,
                    config, existing.createdAt(), Instant.now());
            updated = channelService.save(updated);
            return ResponseEntity.ok(channelToMap(updated));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String id) {
        return channelService.findById(id).map(ch -> {
            channelService.delete(id);
            return ResponseEntity.ok(Map.<String, Object>of("deleted", true, "id", id));
        }).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/send")
    public ResponseEntity<Map<String, Object>> send(@PathVariable String id, @RequestBody Map<String, String> body) {
        var opt = channelService.findById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Channel ch = opt.get();
        String content = body.get("content");
        String threadId = body.get("threadId");
        String sessionId = body.get("sessionId");

        if (content == null || content.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "content ist erforderlich."));
        }

        try {
            ChannelMessage sent = channelService.send(ch, content, threadId, sessionId);
            return ResponseEntity.ok(messageToMap(sent));
        } catch (ChannelAdapter.ChannelException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/inbound")
    public ResponseEntity<Map<String, Object>> inbound(@RequestBody Map<String, String> body) {
        String channelId = body.get("channelId");
        String externalId = body.get("externalId");
        String content = body.get("content");
        String senderId = body.get("senderId");
        String senderName = body.get("senderName");
        String threadId = body.get("threadId");
        String sessionId = body.get("sessionId");

        if (channelId == null || content == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "channelId und content sind erforderlich."));
        }

        ChannelMessage msg = ChannelMessage.inbound(channelId, externalId, content,
                senderId, senderName, threadId, sessionId);
        channelService.handleInbound(msg);
        return ResponseEntity.ok(messageToMap(msg));
    }

    @GetMapping("/{id}/bindings")
    public List<Map<String, Object>> bindings(@PathVariable String id) {
        return channelService.findBindingsByChannel(id).stream().map(this::bindingToMap).toList();
    }

    @PostMapping("/{id}/bindings")
    public ResponseEntity<Map<String, Object>> createBinding(@PathVariable String id,
                                                              @RequestBody Map<String, String> body) {
        var opt = channelService.findById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        String externalId = body.get("externalId");
        String sessionId = body.get("sessionId");
        String typeStr = body.getOrDefault("bindingType", "DM");

        if (externalId == null || sessionId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "externalId und sessionId sind erforderlich."));
        }

        BindingType bindingType;
        try {
            bindingType = BindingType.valueOf(typeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            bindingType = BindingType.DM;
        }

        ChannelBinding binding = channelService.createBinding(id, externalId, sessionId, bindingType);
        return ResponseEntity.ok(bindingToMap(binding));
    }

    @DeleteMapping("/bindings/{bindingId}")
    public ResponseEntity<Map<String, Object>> deleteBinding(@PathVariable String bindingId) {
        channelService.deleteBinding(bindingId);
        return ResponseEntity.ok(Map.<String, Object>of("deleted", true, "id", bindingId));
    }

    @GetMapping("/adapters")
    public Map<String, Object> adapters() {
        return Map.of("available", channelService.availableAdapterTypes().stream()
                .map(Enum::name).toList());
    }

    private Map<String, Object> channelToMap(Channel ch) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", ch.id());
        map.put("name", ch.name());
        map.put("type", ch.type().name());
        map.put("enabled", ch.enabled());
        map.put("config", ch.config());
        map.put("createdAt", ch.createdAt() != null ? ch.createdAt().toString() : null);
        map.put("updatedAt", ch.updatedAt() != null ? ch.updatedAt().toString() : null);
        return map;
    }

    private Map<String, Object> bindingToMap(ChannelBinding b) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", b.id());
        map.put("channelId", b.channelId());
        map.put("externalId", b.externalId());
        map.put("sessionId", b.sessionId());
        map.put("bindingType", b.bindingType().name());
        map.put("createdAt", b.createdAt() != null ? b.createdAt().toString() : null);
        return map;
    }

    private Map<String, Object> messageToMap(ChannelMessage m) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", m.id());
        map.put("channelId", m.channelId());
        map.put("externalId", m.externalId());
        map.put("direction", m.direction().name());
        map.put("content", m.content());
        map.put("senderId", m.senderId());
        map.put("senderName", m.senderName());
        map.put("threadId", m.threadId());
        map.put("sessionId", m.sessionId());
        map.put("timestamp", m.timestamp() != null ? m.timestamp().toString() : null);
        return map;
    }
}
