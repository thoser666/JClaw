package biz.brumm.infrastructure.adapter.out.plugin;

import biz.brumm.config.PluginRuntimeProperties;
import biz.brumm.domain.model.Plugin;
import biz.brumm.domain.model.PluginType;
import biz.brumm.domain.port.out.PluginHookDispatcher;
import biz.brumm.domain.port.out.PluginProvider;
import biz.brumm.infrastructure.sidecar.NodeSidecarBridge;
import biz.brumm.infrastructure.sidecar.SidecarCallException;
import biz.brumm.infrastructure.sidecar.SidecarTimeoutException;
import biz.brumm.infrastructure.sidecar.SidecarToolDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * <p>
 * Install-Provenance (P4-09, fail-closed): Vor dem Laden prüft {@link PluginInstallProvenanceGuard},
 * ob das Plugin über eine vertrauenswürdige Install-Quelle (Standard {@code bundled}/{@code catalog})
 * oder eine explizite Freigabe ({@code trusted-sources}/{@code allow} = OpenClaw {@code --force})
 * verfügt. Blockierte Plugins (fehlende/ungültige Provenance) bleiben Control-Plane-only und werden
 * nie in den Sidecar geladen.
 * <p>
 * Hook-Dispatch (P4-01 Folgearbeit „Voll-Hook-Katalog"): Die Laufzeit implementiert den
 * {@link PluginHookDispatcher}-Port und reicht jeden Dispatch als {@code plugin.callHook} an den
 * Sidecar weiter. Fast-Path: Sind keine Plugins geladen und läuft kein Sidecar, wird auf einen
 * Sidecar-Start verzichtet (ausgenommen {@code before_install}, das vor dem ersten Load läuft).
 */
@Component
@ConditionalOnProperty(prefix = "jclaw.agent.plugins.runtime", name = "enabled", havingValue = "true")
public class NodeSidecarPluginRuntime implements Closeable, PluginHookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NodeSidecarPluginRuntime.class);

    private final PluginProvider pluginProvider;
    private final EntryPointResolver entryPointResolver;
    private final PluginInstallProvenanceGuard provenanceGuard;
    private final ObjectMapper objectMapper;
    private final long callTimeoutMillis;

    private final Set<String> loadedIds = ConcurrentHashMap.newKeySet();
    private volatile NodeSidecarBridge bridge;

    public NodeSidecarPluginRuntime(PluginProvider pluginProvider, ObjectMapper objectMapper,
                                    PluginRuntimeProperties properties) {
        this.pluginProvider = pluginProvider;
        this.entryPointResolver = new EntryPointResolver(objectMapper);
        this.provenanceGuard = new PluginInstallProvenanceGuard(objectMapper, properties);
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
     * @return {@link Optional#empty()}, wenn keine vertrauenswürdige Install-Provenance vorliegt
     *         (P4-09, fail-closed) oder kein Entry-Point (Code) im Bundle existiert —
     *         das Plugin bleibt dann Control-Plane-only.
     */
    public Optional<PluginLoadReceipt> load(Plugin plugin) throws IOException, SidecarCallException, SidecarTimeoutException {
        Optional<PluginInstallProvenanceGuard.ProvenanceBlock> provenanceBlock = provenanceGuard.checkLoad(plugin);
        if (provenanceBlock.isPresent()) {
            log.warn("Plugin '{}' wird NICHT in den Sidecar geladen (Install-Provenance): {} (Control-Plane-only).",
                    plugin.id(), provenanceBlock.get().message());
            return Optional.empty();
        }
        // before_install-Hook (OpenClaw): Plugins können eine Installation vor dem Laden
        // inspizieren und blockieren. Läuft vor dem ersten Bridge-Start, deshalb kein
        // Fast-Path (dispatch() nimmt 'before_install' explizit davon aus).
        Map<String, Object> installCtx = Map.<String, Object>of(
                "pluginId", plugin.id() != null ? plugin.id() : plugin.name(),
                "name", plugin.name(),
                "baseDir", plugin.baseDir(),
                "type", plugin.type().name().toLowerCase());
        HookOutcome installDecision = dispatch("before_install", installCtx);
        if (installDecision.blocked()) {
            log.warn("Plugin '{}' wird NICHT in den Sidecar geladen (before_install-Hook blockiert): {}",
                    plugin.id(), installDecision.message());
            return Optional.empty();
        }
        Optional<Path> entryFile = entryPointResolver.resolve(Path.of(plugin.baseDir()));
        if (entryFile.isEmpty()) {
            log.info("Plugin '{}' hat keinen auflösbaren Entry-Point - nur Control-Plane.", plugin.id());
            return Optional.empty();
        }
        String pluginId = plugin.id() != null ? plugin.id() : plugin.name();
        // Bundle-Modus (P4-01 npm/TypeScript-Bundles): Der Sidecar liest und kompiliert das
        // Entry selbst — CommonJS mit hermetischem require-Scope + TypeScript-Type-Stripping.
        JsonNode receiptNode = bridge().loadPluginBundle(pluginId, entryFile.get(), Path.of(plugin.baseDir()));
        PluginLoadReceipt receipt = parseReceipt(receiptNode);
        loadedIds.add(pluginId);
        log.info("Plugin '{}' geladen: {} Tool(s), {} Command(s), {} Channel(s), {} Hook-Registrierung(en).",
                pluginId, receipt.tools().size(), receipt.commands().size(), receipt.channels().size(),
                receipt.hooks().size());
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

    /**
     * Stellt eine eingehende Nachricht (Empfang) an einen vom Plugin registrierten Channel zu.
     * <p>
     * Analog zur {@code ChannelMessage.inbound}-Semantik der plattformnativen Adapter wird die
     * Nachricht über dieselbe Bridge als {@code channel.deliver} an den {@code receive}-Handler
     * des Plugins zugestellt; unbekannte Channels bzw. werfende Handler liefern einen
     * strukturierten Fehler ({@code ERROR_CHANNEL_NOT_FOUND}/{@code ERROR_CHANNEL_EXECUTION}).
     */
    public JsonNode deliverChannelMessage(String channel, JsonNode message)
            throws IOException, SidecarCallException, SidecarTimeoutException {
        return bridge().deliverChannelMessage(channel, message);
    }

    /**
     * Liefert Spring-AI-{@link ToolCallback}s für alle im Sidecar registrierten Plugin-Tools.
     * <p>
     * Spiegel zu {@code McpToolRegistry.toolCallbacks()}: Jeder {@link SidecarToolDescriptor} wird
     * über das hermetische {@link PluginToolCallback}-Binding (getSharedToolDescriptor + Dispatcher)
     * an das Spring-AI-Tool-Calling angebunden; der Dispatcher delegiert an {@link #callTool(String, tools.jackson.databind.JsonNode)}.
     */
    public List<ToolCallback> toolCallbacks() {
        try {
            return tools().stream()
                    .map(descriptor -> (ToolCallback) new PluginToolCallback(descriptor, this::dispatchTool))
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("Plugin-Tools nicht über den Sidecar lesbar: " + e.getMessage(), e);
        }
    }

    /** Dispatcher-Brücke: serialisiert Plugin-Tool-Aufrufe als JSON-RPC an den Sidecar. */
    private String dispatchTool(String name, String args) {
        try {
            JsonNode result = callTool(name, objectMapper.readTree(args));
            return objectMapper.writeValueAsString(result);
        } catch (IOException e) {
            throw new IllegalStateException("Plugin-Tool '" + name + "' fehlgeschlagen: " + e.getMessage(), e);
        }
    }

    @Override
    public HookOutcome dispatch(String event, String name, Map<String, Object> ctx) {
        // Fast-Path: ohne geladene Plugins und ohne laufenden Sidecar keinen Prozess
        // hochziehen (die meisten JClaw-Stages emittieren ohnehin nur, wenn Plugins da
        // sind). before_install läuft vor dem ersten Load und ist davon ausgenommen.
        if (loadedIds.isEmpty() && bridge == null && !"before_install".equals(event)) {
            return HookOutcome.proceed();
        }
        try {
            JsonNode ctxNode = objectMapper.readTree(objectMapper.writeValueAsString(ctx));
            JsonNode result = bridge().callHook(event, name, ctxNode);
            if (result.path("blocked").asBoolean(false)) {
                return HookOutcome.block(result.path("message").asString(event + "-Hook hat blockiert."));
            }
            return HookOutcome.proceed();
        } catch (IOException e) {
            // Hooks sind additive Lifecycle-Beobachtung: Sidecar-Fehler dürfen den
            // Kern-Ablauf nicht kippen (fail-open; Blocking kommt nur von Hooks selbst).
            log.warn("plugin.callHook '{}' fehlgeschlagen ({}, {}); Dispatcher fährt fort.",
                    event, e.getClass().getSimpleName(), e.getMessage());
            return HookOutcome.proceed();
        }
    }

    /** Gateway-Lifecycle (OpenClaw {@code gateway_start}/{@code gateway_stop}): beobachtet. */
    @EventListener(ApplicationReadyEvent.class)
    public void onGatewayStart() {
        dispatch("gateway_start", Map.<String, Object>of("type", "jclaw",
                "pid", ProcessHandle.current().pid(), "startAt", Instant.now().toString()));
    }

    @EventListener(ContextClosedEvent.class)
    public void onGatewayStop() {
        dispatch("gateway_stop", Map.<String, Object>of("type", "jclaw",
                "pid", ProcessHandle.current().pid()));
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

        List<PluginChannelRegistration> channels = new ArrayList<>();
        for (JsonNode channel : receipt.path("channels")) {
            channels.add(new PluginChannelRegistration(
                    channel.path("name").asString(),
                    channel.path("description").asString()));
        }

        List<PluginHookRegistration> hooks = new ArrayList<>();
        for (JsonNode hook : receipt.path("hooks")) {
            hooks.add(new PluginHookRegistration(
                    hook.path("event").asString(),
                    hook.path("priority").asInt(0)));
        }

        return new PluginLoadReceipt(id, name, tools, commands, channels, hooks);
    }

    /** Quittung von {@code plugin.load}: was das Plugin zur Laufzeit registriert hat. */
    public record PluginLoadReceipt(String id, String name,
                                    List<PluginToolRegistration> tools,
                                    List<String> commands,
                                    List<PluginChannelRegistration> channels,
                                    List<PluginHookRegistration> hooks) {
    }

    /** Ein vom Plugin registriertes Tool (inkl. JSON-Schema der Parameter). */
    public record PluginToolRegistration(String name, String description, JsonNode parameters) {
    }

    /** Ein vom Plugin registrierter Empfangs-Channel ({@code name} + {@code description}). */
    public record PluginChannelRegistration(String name, String description) {
    }

    /** Eine vom Plugin registrierte Hook-Registrierung ({@code event} + {@code priority}). */
    public record PluginHookRegistration(String event, int priority) {
    }
}