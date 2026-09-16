package biz.brumm.infrastructure.adapter.out.plugin;

import biz.brumm.config.PluginRuntimeProperties;
import biz.brumm.domain.model.Plugin;
import biz.brumm.domain.model.PluginType;
import biz.brumm.domain.port.out.PluginProvider;
import biz.brumm.infrastructure.sidecar.NodeSidecarBridge;
import biz.brumm.infrastructure.sidecar.SidecarCallException;
import biz.brumm.infrastructure.sidecar.SidecarTimeoutException;
import biz.brumm.infrastructure.sidecar.SidecarToolDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Plugin-Laufzeit (P4-01) über den Node-Sidecar {@code plugin-sidecar.js}.
 * <p>
 * Der Service stellt die {@code definePluginEntry}/{@code defineChannelPluginEntry}-Entry-
 * Semantik für ein Control-Plane-geladenes Plugin bereit: Er löst den Entry-Punkt des
 * Bundles auf ({@code package.json} → {@code main}, sonst Fallbacks), übergibt den Source
 * an den Sidecar ({@code plugin.load}) und hält die Load-Quittung (Tools, Commands, Hooks)
 * lokal. Tools werden im Sidecar zur Laufzeit registriert statt statisch; sie erscheinen
 * zusätzlich in {@code sidecar.listTools} und sind über {@code tool.call} aufrufbar —
 * inklusive {@code before_tool_call}/{@code after_tool_call}-Hooks der Plugins
 * (Blocking über {@link NodeSidecarBridge#ERROR_HOOK_BLOCKED}).
 * <p>
 * Activity ist opt-in ({@code jclaw.agent.plugins.runtime.enabled=true}); ohne aktivierte
 * Laufzeit bleibt nur die Control-Plane (Manifest-Validierung) aktiv.
 */
@Component
@ConditionalOnProperty(prefix = "jclaw.agent.plugins.runtime", name = "enabled", havingValue = "true")
public class NodeSidecarPluginRuntime implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(NodeSidecarPluginRuntime.class);

    private final PluginProvider pluginProvider;
    private final EntryPointResolver entryPointResolver;
    private final ObjectMapper objectMapper;
    private final long callTimeoutMillis;

    private final Set<String> loadedIds = ConcurrentHashMap.newKeySet();
    private volatile NodeSidecarBridge bridge;

    public NodeSidecarPluginRuntime(PluginProvider pluginProvider, ObjectMapper objectMapper,
                                    PluginRuntimeProperties properties) {
        this.pluginProvider = pluginProvider;
        this.entryPointResolver = new EntryPointResolver(objectMapper);
        this.objectMapper = objectMapper;
        this.callTimeoutMillis = properties.callTimeoutMillis();
    }

    /** Lädt alle gültigen OpenClaw-Plugins mit auflösbarem Entry-Point in den Sidecar. */
    public List<PluginLoadReceipt> loadAvailable() throws IOException, SidecarCallException, SidecarTimeoutException {
        List<Plugin> candidates = pluginProvider.findAll().stream()
                .filter(plugin -> plugin.valid() && plugin.type() == PluginType.OPENCLAW)
                .toList();
        List<PluginLoadReceipt> loaded = new ArrayList<>();
        for (Plugin plugin : candidates) {
            load(plugin).ifPresent(loaded::add);
        }
        log.info("Plugin-Runtime: {} von {} OpenClaw-Plugins geladen.", loaded.size(), candidates.size());
        return loaded;
    }

    /**
     * Lädt ein einzelnes Plugin.
     *
     * @return {@link Optional#empty()}, wenn kein Entry-Point (Code) im Bundle existiert —
     *         das Plugin bleibt dann Control-Plane-only.
     */
    public Optional<PluginLoadReceipt> load(Plugin plugin) throws IOException, SidecarCallException, SidecarTimeoutException {
        Optional<Path> entryFile = entryPointResolver.resolve(Path.of(plugin.baseDir()));
        if (entryFile.isEmpty()) {
            log.info("Plugin '{}' hat keinen auflösbaren Entry-Point - nur Control-Plane.", plugin.id());
            return Optional.empty();
        }
        String source = Files.readString(entryFile.get(), StandardCharsets.UTF_8);
        String pluginId = plugin.id() != null ? plugin.id() : plugin.name();
        JsonNode receiptNode = bridge().loadPlugin(pluginId, source);
        PluginLoadReceipt receipt = parseReceipt(receiptNode);
        loadedIds.add(pluginId);
        log.info("Plugin '{}' geladen: {} Tool(s), {} Command(s), {} Hook-Registrierung(en).",
                pluginId, receipt.tools().size(), receipt.commands().size(), receipt.hooks().size());
        return Optional.of(receipt);
    }

    /** Entlädt ein Plugin; kein Fehler, wenn es nicht geladen war. */
    public boolean unload(String pluginId) throws IOException, SidecarCallException, SidecarTimeoutException {
        boolean removed = loadedIds.remove(pluginId);
        JsonNode result = bridge().unloadPlugin(pluginId);
        boolean sidecarRemoved = result.path("removed").asBoolean(false);
        log.info("Plugin '{}' entladen (lokal geladen: {}, Sidecar entfernt: {}).",
                pluginId, removed, sidecarRemoved);
        return removed || sidecarRemoved;
    }

    /** Entlädt alle aktuell geladenen Plugins. */
    public void unloadAll() throws IOException, SidecarCallException, SidecarTimeoutException {
        for (String pluginId : List.copyOf(loadedIds)) {
            unload(pluginId);
        }
    }

    public boolean isLoaded(String pluginId) {
        return loadedIds.contains(pluginId);
    }

    /** Liefert alle im Sidecar registrierten Tools (statische Referenz-Tools + Plugin-Tools). */
    public List<SidecarToolDescriptor> tools() throws IOException, SidecarCallException, SidecarTimeoutException {
        return bridge().listTools();
    }

    /** Ruft ein (Plugin- oder statisches) Tool über den Sidecar auf. */
    public JsonNode callTool(String name, JsonNode arguments)
            throws IOException, SidecarCallException, SidecarTimeoutException {
        return bridge().callTool(name, arguments);
    }

    /** {@code true}, wenn der Node-Sidecar-Prozess aktiv läuft. */
    public boolean active() {
        NodeSidecarBridge current = bridge;
        return current != null && current.processAlive();
    }

    @Override
    public void close() {
        NodeSidecarBridge current = bridge;
        bridge = null;
        loadedIds.clear();
        if (current != null) {
            current.close();
        }
    }

    private NodeSidecarBridge bridge() throws IOException {
        NodeSidecarBridge current = bridge;
        if (current == null) {
            synchronized (this) {
                current = bridge;
                if (current == null) {
                    current = NodeSidecarBridge.start(
                            NodeSidecarBridge.pluginScript(), objectMapper,
                            callTimeoutMillis, NodeSidecarBridge.DEFAULT_READY_TIMEOUT_MILLIS);
                    bridge = current;
                }
            }
        }
        return current;
    }

    private PluginLoadReceipt parseReceipt(JsonNode receipt) {
        String id = receipt.path("id").asString();
        String name = receipt.path("name").asString(id);

        List<PluginToolRegistration> tools = new ArrayList<>();
        for (JsonNode tool : receipt.path("tools")) {
            tools.add(new PluginToolRegistration(
                    tool.path("name").asString(),
                    tool.path("description").asString(),
                    tool.hasNonNull("parameters") ? tool.get("parameters") : null));
        }

        List<String> commands = new ArrayList<>();
        for (JsonNode command : receipt.path("commands")) {
            commands.add(command.asString());
        }

        List<PluginHookRegistration> hooks = new ArrayList<>();
        for (JsonNode hook : receipt.path("hooks")) {
            hooks.add(new PluginHookRegistration(
                    hook.path("event").asString(),
                    hook.path("priority").asInt(0)));
        }

        return new PluginLoadReceipt(id, name, tools, commands, hooks);
    }

    /** Quittung von {@code plugin.load}: was das Plugin zur Laufzeit registriert hat. */
    public record PluginLoadReceipt(String id, String name,
                                    List<PluginToolRegistration> tools,
                                    List<String> commands,
                                    List<PluginHookRegistration> hooks) {
    }

    /** Ein vom Plugin registriertes Tool (inkl. JSON-Schema der Parameter). */
    public record PluginToolRegistration(String name, String description, JsonNode parameters) {
    }

    /** Eine vom Plugin registrierte Hook-Registrierung ({@code event} + {@code priority}). */
    public record PluginHookRegistration(String event, int priority) {
    }
}