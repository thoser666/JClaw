package biz.brumm.infrastructure.sidecar;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NodeSidecarBridgeTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    @EnabledIf("nodeAvailable")
    void handshakeProvidesReadyInfo() throws IOException {
        try (NodeSidecarBridge bridge = NodeSidecarBridge.start(objectMapper)) {
            JsonNode info = bridge.readyInfo();
            assertThat(info).isNotNull();
            assertThat(info.path("name").asString()).isEqualTo("jclaw-protocol-sidecar");
        }
    }

    @Test
    @EnabledIf("nodeAvailable")
    void pingReturnsTrue() throws IOException {
        try (NodeSidecarBridge bridge = NodeSidecarBridge.start(objectMapper)) {
            assertThat(bridge.ping()).isTrue();
        }
    }

    @Test
    @EnabledIf("nodeAvailable")
    void infoReportsNameVersionAndNodeRuntime() throws IOException {
        try (NodeSidecarBridge bridge = NodeSidecarBridge.start(objectMapper)) {
            JsonNode info = bridge.info();
            assertThat(info.path("name").asString()).isEqualTo("jclaw-protocol-sidecar");
            assertThat(info.path("node").asString()).startsWith("v");
        }
    }

    @Test
    @EnabledIf("nodeAvailable")
    void listToolsReportsRegisteredTools() throws IOException {
        try (NodeSidecarBridge bridge = NodeSidecarBridge.start(objectMapper)) {
            List<SidecarToolDescriptor> tools = bridge.listTools();

            assertThat(tools).extracting(SidecarToolDescriptor::name)
                    .containsExactly("add", "echo", "sleep");
            SidecarToolDescriptor add = tools.stream()
                    .filter(t -> t.name().equals("add"))
                    .findFirst().orElseThrow();
            assertThat(add.description()).contains("Summe");
            assertThat(add.parameters()).isNotNull();
        }
    }

    @Test
    @EnabledIf("nodeAvailable")
    void callToolReturnsToolResult() throws IOException {
        try (NodeSidecarBridge bridge = NodeSidecarBridge.start(objectMapper)) {
            ObjectNode arguments = objectMapper.createObjectNode().put("a", 2).put("b", 3);

            JsonNode result = bridge.callTool("add", arguments);

            assertThat(result.path("result").asInt()).isEqualTo(5);
        }
    }

    @Test
    @EnabledIf("nodeAvailable")
    void callToolUnknownToolRaisesToolNotFound() throws IOException {
        try (NodeSidecarBridge bridge = NodeSidecarBridge.start(objectMapper)) {
            assertThatThrownBy(() -> bridge.callTool("gibtsNicht", null))
                    .isInstanceOf(SidecarCallException.class)
                    .satisfies(e -> assertThat(((SidecarCallException) e).code())
                            .isEqualTo(NodeSidecarBridge.ERROR_TOOL_NOT_FOUND));
        }
    }

    @Test
    @EnabledIf("nodeAvailable")
    void callToolWithInvalidArgumentsRaisesToolExecutionError() throws IOException {
        try (NodeSidecarBridge bridge = NodeSidecarBridge.start(objectMapper)) {
            ObjectNode arguments = objectMapper.createObjectNode().put("a", "keine Zahl").put("b", 3);

            assertThatThrownBy(() -> bridge.callTool("add", arguments))
                    .isInstanceOf(SidecarCallException.class)
                    .satisfies(e -> assertThat(((SidecarCallException) e).code())
                            .isEqualTo(NodeSidecarBridge.ERROR_TOOL_EXECUTION))
                    .hasMessageContaining("müssen Zahlen sein");
        }
    }

    @Test
    @EnabledIf("nodeAvailable")
    void unknownMethodRaisesMethodNotFound() throws IOException {
        try (NodeSidecarBridge bridge = NodeSidecarBridge.start(objectMapper)) {
            assertThatThrownBy(() -> bridge.execute("gibtsNicht", null))
                    .isInstanceOf(SidecarCallException.class)
                    .satisfies(e -> assertThat(((SidecarCallException) e).code())
                            .isEqualTo(NodeSidecarBridge.ERROR_METHOD_NOT_FOUND));
        }
    }

    @Test
    @EnabledIf("nodeAvailable")
    void callToolSlowerThanCallTimeoutRaisesTimeout() throws IOException {
        try (NodeSidecarBridge bridge = NodeSidecarBridge.start(NodeSidecarBridge.defaultScript(), objectMapper, 300, 5000)) {
            ObjectNode arguments = objectMapper.createObjectNode().put("ms", 2000);

            assertThatThrownBy(() -> bridge.callTool("sleep", arguments))
                    .isInstanceOf(SidecarTimeoutException.class)
                    .hasMessageContaining("300 ms");
        }
    }

    @Test
    @EnabledIf("nodeAvailable")
    void restartStartsFreshProcess() throws IOException {
        try (NodeSidecarBridge bridge = NodeSidecarBridge.start(objectMapper)) {
            long firstPid = bridge.pid();
            assertThat(bridge.ping()).isTrue();

            bridge.restart();

            assertThat(bridge.pid()).isNotEqualTo(firstPid);
            assertThat(bridge.processAlive()).isTrue();
            assertThat(bridge.ping()).isTrue();
        }
    }

    @Test
    @EnabledIf("nodeAvailable")
    void closeTerminatesSidecarProcess() throws IOException {
        NodeSidecarBridge bridge = NodeSidecarBridge.start(objectMapper);
        assertThat(bridge.processAlive()).isTrue();

        bridge.close();

        assertThat(bridge.processAlive()).isFalse();
    }

    @Test
    @EnabledIf("nodeAvailable")
    void callAfterCloseFails() throws IOException {
        NodeSidecarBridge bridge = NodeSidecarBridge.start(objectMapper);
        bridge.close();

        assertThatThrownBy(() -> bridge.callTool("add", objectMapper.createObjectNode().put("a", 1).put("b", 1)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("geschlossen");
    }

    @Test
    @EnabledIf("nodeAvailable")
    void restartAfterCloseFails() throws IOException {
        NodeSidecarBridge bridge = NodeSidecarBridge.start(objectMapper);
        bridge.close();

        assertThatThrownBy(bridge::restart)
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Neustart");
    }

    @Test
    @EnabledIf("nodeAvailable")
    void concurrentCallsWithinLimitAllSucceed() throws Exception {
        try (NodeSidecarBridge bridge = NodeSidecarBridge.start(NodeSidecarBridge.defaultScript(), objectMapper, 5_000, 5_000, 4, 5_000)) {
            ExecutorService pool = Executors.newFixedThreadPool(4);
            try {
                List<Future<JsonNode>> futures = pool.invokeAll(List.of(
                        () -> add(bridge, 1, 1), () -> add(bridge, 10, 20),
                        () -> add(bridge, 100, 200), () -> add(bridge, 7, 3)));
                assertThat(futures).allSatisfy(f -> {
                    try {
                        assertThat(f.get(10, TimeUnit.SECONDS).path("result").asInt()).isPositive();
                    } catch (Exception e) {
                        throw new AssertionError("Parallel-Aufruf fehlgeschlagen", e);
                    }
                });
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    @EnabledIf("nodeAvailable")
    void callBeyondConcurrencyLimitIsRejectedWithBusyError() throws Exception {
        try (NodeSidecarBridge bridge = NodeSidecarBridge.start(NodeSidecarBridge.defaultScript(), objectMapper, 5_000, 5_000, 2, 200)) {
            ExecutorService pool = Executors.newFixedThreadPool(4);
            try {
                Future<JsonNode> blockerA = pool.submit(() -> sleep(bridge, 800));
                Future<JsonNode> blockerB = pool.submit(() -> sleep(bridge, 800));
                Thread.sleep(300); // beide Slots sicher durch die Blocker belegt

                // Beide Slots sind belegt; ein dritter Aufruf wird nach 200 ms Wartefenster abgewiesen.
                assertThatThrownBy(() -> bridge.callTool("sleep", objectMapper.createObjectNode().put("ms", 10)))
                        .isInstanceOf(SidecarCallException.class)
                        .satisfies(e -> assertThat(((SidecarCallException) e).code())
                                .isEqualTo(NodeSidecarBridge.ERROR_BUSY));

                assertThat(blockerA.get(10, TimeUnit.SECONDS).path("sleptMs").asInt()).isEqualTo(800);
                assertThat(blockerB.get(10, TimeUnit.SECONDS).path("sleptMs").asInt()).isEqualTo(800);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    @EnabledIf("nodeAvailable")
    void startFailureEmbedsStderrInTimeoutMessage() throws IOException {
        // Script mit Startfehler (fehlendes "Modul"): Node beendet sich mit stderr-Ausgabe,
        // ohne jemals sidecar.ready zu senden. Die stderr-Diagnose muss in der Timeout-Meldung stecken.
        String script = "console.error('Modul nicht gefunden: acme-broken'); process.exit(1);";

        assertThatThrownBy(() -> NodeSidecarBridge.start(script, objectMapper, 5_000, 1_000))
                .isInstanceOf(SidecarTimeoutException.class)
                .hasMessageContaining("Modul nicht gefunden: acme-broken");
    }

    @Test
    @EnabledIf("nodeAvailable")
    void stderrIsDrainedWithoutCorruptingProtocolStream() throws IOException {
        // Sidecar, der stderr nur zum Loggen nutzt (nie als Antwort geparst): Handshake und
        // Tool-Aufrufe müssen normal funktionieren, obwohl stderr-Ausgaben auftreten.
        String script = "const readline=require('readline');console.error('Warnung: Plugin lädt asynchron');"
                + "const rl=readline.createInterface({input:process.stdin});"
                + "rl.on('line',l=>{const q=JSON.parse(l);const args=(q.params&&q.params.arguments)||{};"
                + "process.stdout.write(JSON.stringify({jsonrpc:'2.0',id:q.id,result:{result:(args.a||0)+(args.b||0)}})+'\\n');});"
                + "setImmediate(()=>process.stdout.write(JSON.stringify("
                + "{jsonrpc:'2.0',method:'sidecar.ready',params:{name:'stderr-sidecar'}})+'\\n'));";

        try (NodeSidecarBridge bridge = NodeSidecarBridge.start(script, objectMapper, 5_000, 5_000)) {
            JsonNode info = bridge.readyInfo();
            assertThat(info.path("name").asString()).isEqualTo("stderr-sidecar");
            ObjectNode arguments = objectMapper.createObjectNode().put("a", 2).put("b", 3);
            assertThat(bridge.callTool("add", arguments).path("result").asInt()).isEqualTo(5);
        }
    }

    @Test
    @EnabledIf("nodeAvailable")
    void oversizedResponseIsRejectedWithResponseTooLargeError() throws Exception {
        // Ein Sidecar, der je Tool eine kleine oder eine überdimensionierte Antwort liefert; Cap ist klein gesetzt,
        // sodass die Handshake-Zeile und normale Antworten darunter bleiben, die große Antwort aber überschritten wird.
        String script = "const readline=require('readline');"
                + "const rl=readline.createInterface({input:process.stdin});"
                + "rl.on('line',l=>{const q=JSON.parse(l);if(q.method==='tool.call'){"
                + "const name=q.params&&q.params.name;"
                + "const result=name==='big'?('X'.repeat(1000)):('klein');"
                + "process.stdout.write(JSON.stringify({jsonrpc:'2.0',id:q.id,result:{result}})+'\\n');}});"
                + "setImmediate(()=>process.stdout.write(JSON.stringify("
                + "{jsonrpc:'2.0',method:'sidecar.ready',params:{name:'cap-sidecar'}})+'\\n'));";

        try (NodeSidecarBridge bridge = NodeSidecarBridge.start(script, objectMapper, 5_000, 5_000, 4, 5_000, 200)) {
            // Normale Antwort (Zeile unter dem Cap) funktioniert weiterhin.
            assertThat(bridge.callTool("add", objectMapper.createObjectNode().put("a", 1).put("b", 2)).path("result").asString())
                    .isEqualTo("klein");

            // Überdimensionierte Antwort (Zeile > 200 Zeichen) wird als ERROR_RESPONSE_TOO_LARGE abgewiesen.
            assertThatThrownBy(() -> bridge.callTool("big", null))
                    .isInstanceOf(SidecarCallException.class)
                    .satisfies(e -> assertThat(((SidecarCallException) e).code())
                            .isEqualTo(NodeSidecarBridge.ERROR_RESPONSE_TOO_LARGE));

            // Die Bridge bleibt nach dem Abweisen einer zu großen Antwort funktionsfähig.
            assertThat(bridge.callTool("add", objectMapper.createObjectNode().put("a", 3).put("b", 4)).path("result").asString())
                    .isEqualTo("klein");
        }
    }

    @Test
    @EnabledIf("nodeAvailable")
    void oversizedNotificationLineIsDroppedWithoutBreakingBridge() throws Exception {
        // Eine überdimensionierte Zeile ohne Request-Id (riesige Notification) wird verworfen, ohne den
        // Protokollbetrieb zu stören; die id-basierte Antwort erreicht den Aufrufer normal.
        String script = "const readline=require('readline');"
                + "const rl=readline.createInterface({input:process.stdin});"
                + "rl.on('line',l=>{const q=JSON.parse(l);if(q.method==='tool.call'){"
                + "process.stdout.write(JSON.stringify({jsonrpc:'2.0',id:q.id,result:{result:'ok'}})+'\\n');}});"
                + "setImmediate(()=>{"
                + "process.stdout.write(JSON.stringify({jsonrpc:'2.0',method:'sidecar.ready',params:{name:'noise-sidecar'}})+'\\n');"
                + "process.stdout.write('{\"jsonrpc\":\"2.0\",\"method\":\"log\",\"params\":{\"chunk\":\"'+'Y'.repeat(1000)+'\"}}\\n');});";

        try (NodeSidecarBridge bridge = NodeSidecarBridge.start(script, objectMapper, 5_000, 5_000, 4, 5_000, 200)) {
            ObjectNode arguments = objectMapper.createObjectNode().put("a", 1).put("b", 2);
            assertThat(bridge.callTool("add", arguments).path("result").asString()).isEqualTo("ok");
        }
    }

    // Spiegel-Test 942 — loadPluginBundle (P4-01 npm/TypeScript-Bundles) registriert ein Bundle
    // direkt über das Plugin-Sidecar (entryPath + baseDir statt Inline-Source).
    @Test
    @EnabledIf("nodeAvailable")
    void loadPluginBundleRegistersBundleFromRealPluginSidecar() throws IOException {
        Path plugin = Files.createDirectories(tempDir.resolve("bridge-bundle"));
        write(plugin, "src/index.js", """
                module.exports = definePluginEntry({
                  id: 'acme/bridge-bundle',
                  name: 'Bridge Bundle',
                  register(api) {
                    api.registerTool({
                      name: 'bridge-tool',
                      description: 'Vom Bundle registriert.',
                      execute() { return { ok: true }; }
                    });
                  }
                });
                """);

        try (NodeSidecarBridge bridge = NodeSidecarBridge.start(NodeSidecarBridge.pluginScript(), objectMapper)) {
            JsonNode receipt = bridge.loadPluginBundle("acme/bridge-bundle",
                    plugin.resolve("src/index.js"), plugin);

            assertThat(receipt.path("id").asString()).isEqualTo("acme/bridge-bundle");
            assertThat(receipt.path("name").asString()).isEqualTo("Bridge Bundle");
            assertThat(receipt.path("tools").get(0).path("name").asString()).isEqualTo("bridge-tool");

            assertThat(bridge.callTool("bridge-tool", null).path("ok").asBoolean()).isTrue();
        }
    }

    // --- plugin.callHook (P4-01 Folgearbeit „Voll-Hook-Katalog") ---

    @Test
    @EnabledIf("nodeAvailable")
    void callHookUnknownEventReportsUnblockedEmptyCount() throws IOException {
        try (NodeSidecarBridge bridge = NodeSidecarBridge.start(NodeSidecarBridge.pluginScript(), objectMapper)) {
            JsonNode result = bridge.callHook("gibtsNicht", null, null);

            assertThat(result.path("blocked").asBoolean(false)).isFalse();
            assertThat(result.path("count").asInt()).isZero();
            assertThat(result.path("event").asString()).isEqualTo("gibtsNicht");
        }
    }

    @Test
    @EnabledIf("nodeAvailable")
    void callHookMissingEventRaisesInvalidParams() throws IOException {
        try (NodeSidecarBridge bridge = NodeSidecarBridge.start(NodeSidecarBridge.pluginScript(), objectMapper)) {
            assertThatThrownBy(() -> bridge.callHook(null, null, null))
                    .isInstanceOf(SidecarCallException.class)
                    .satisfies(e -> assertThat(((SidecarCallException) e).code())
                            .isEqualTo(NodeSidecarBridge.ERROR_INVALID_PARAMS))
                    .hasMessageContaining("event");
        }
    }

    @Test
    @EnabledIf("nodeAvailable")
    void callHookBlockingHookThrowsBlockedDecision() throws IOException {
        Path plugin = Files.createDirectories(tempDir.resolve("hook-block-throw"));
        write(plugin, "src/index.js", """
                module.exports = definePluginEntry({
                  id: 'acme/hook-block-throw',
                  name: 'Hook Block Throw',
                  register(api) {
                    api.on('before_agent_run', (ctx) => {
                      throw new Error('Kein Agent-Lauf.');
                    });
                  }
                });
                """);

        try (NodeSidecarBridge bridge = NodeSidecarBridge.start(NodeSidecarBridge.pluginScript(), objectMapper)) {
            bridge.loadPluginBundle("acme/hook-block-throw", plugin.resolve("src/index.js"), plugin);

            JsonNode result = bridge.callHook("before_agent_run", null,
                    objectMapper.createObjectNode().put("agent", "jclaw"));

            assertThat(result.path("blocked").asBoolean(false)).isTrue();
            assertThat(result.path("count").asInt()).isEqualTo(1);
            assertThat(result.path("message").asString()).contains("Kein Agent-Lauf");
        }
    }

    @Test
    @EnabledIf("nodeAvailable")
    void callHookBlockReturnValueCarriesMessage() throws IOException {
        Path plugin = Files.createDirectories(tempDir.resolve("hook-block-return"));
        write(plugin, "src/index.js", """
                module.exports = definePluginEntry({
                  id: 'acme/hook-block-return',
                  name: 'Hook Block Return',
                  register(api) {
                    api.on('message_sending', (ctx) => ({
                      block: true,
                      message: 'Moment, noch nicht senden.'
                    }));
                  }
                });
                """);

        try (NodeSidecarBridge bridge = NodeSidecarBridge.start(NodeSidecarBridge.pluginScript(), objectMapper)) {
            bridge.loadPluginBundle("acme/hook-block-return", plugin.resolve("src/index.js"), plugin);

            JsonNode result = bridge.callHook("message_sending", null, objectMapper.createObjectNode());

            assertThat(result.path("blocked").asBoolean(false)).isTrue();
            assertThat(result.path("count").asInt()).isEqualTo(1);
            assertThat(result.path("message").asString()).isEqualTo("Moment, noch nicht senden.");
        }
    }

    @Test
    @EnabledIf("nodeAvailable")
    void callHookRespectsNameMatcherAndPriorityOrder() throws IOException {
        Path plugin = Files.createDirectories(tempDir.resolve("hook-matcher"));
        write(plugin, "src/index.js", """
                module.exports = definePluginEntry({
                  id: 'acme/hook-matcher',
                  name: 'Hook Matcher',
                  register(api) {
                    const hits = [];
                    api.on('session_start', (ctx) => { hits.push('p10'); }, { priority: 10 });
                    api.on('session_start', (ctx) => { hits.push('p1'); }, { priority: 1 });
                    api.on('session_start', (ctx) => { hits.push('p5-scoped'); }, { matcher: 'gold', priority: 5 });
                    api.registerTool({
                      name: 'hook-hits',
                      description: 'Liefert die Hook-Reihenfolge.',
                      execute() { return { hits }; }
                    });
                    api.registerTool({
                      name: 'hook-reset',
                      description: 'Leert die Hook-Reihenfolge.',
                      execute() { hits.length = 0; return { ok: true }; }
                    });
                  }
                });
                """);

        try (NodeSidecarBridge bridge = NodeSidecarBridge.start(NodeSidecarBridge.pluginScript(), objectMapper)) {
            bridge.loadPluginBundle("acme/hook-matcher", plugin.resolve("src/index.js"), plugin);

            JsonNode scoped = bridge.callHook("session_start", "gold", null);
            assertThat(scoped.path("blocked").asBoolean(false)).isFalse();
            assertThat(scoped.path("count").asInt()).isEqualTo(3);
            assertThat(bridge.callTool("hook-hits", null).path("hits").toString())
                    .isEqualTo("[\"p10\",\"p5-scoped\",\"p1\"]");

            bridge.callTool("hook-reset", null);
            JsonNode other = bridge.callHook("session_start", "silber", null);
            assertThat(other.path("count").asInt()).isEqualTo(2);
            assertThat(bridge.callTool("hook-hits", null).path("hits").toString())
                    .isEqualTo("[\"p10\",\"p1\"]");
        }
    }

    @Test
    @EnabledIf("nodeAvailable")
    void callHookSlowerThanHookTimeoutIsTreatedAsBlocked() throws IOException {
        Path plugin = Files.createDirectories(tempDir.resolve("hook-timeout"));
        write(plugin, "src/index.js", """
                module.exports = definePluginEntry({
                  id: 'acme/hook-timeout',
                  name: 'Hook Timeout',
                  register(api) {
                    api.on('before_prompt_build', (ctx) => {
                      return new Promise((resolve) => setTimeout(() => resolve({ ok: true }), 1000));
                    }, { timeoutMs: 150 });
                  }
                });
                """);

        try (NodeSidecarBridge bridge = NodeSidecarBridge.start(NodeSidecarBridge.pluginScript(), objectMapper)) {
            bridge.loadPluginBundle("acme/hook-timeout", plugin.resolve("src/index.js"), plugin);

            JsonNode result = bridge.callHook("before_prompt_build", null, objectMapper.createObjectNode());

            assertThat(result.path("blocked").asBoolean(false)).isTrue();
            assertThat(result.path("count").asInt()).isEqualTo(1);
            assertThat(result.path("message").asString()).contains("Timeout");
        }
    }

    private static JsonNode add(NodeSidecarBridge bridge, int a, int b) throws Exception {
        return bridge.callTool("add", new ObjectMapper().createObjectNode().put("a", a).put("b", b));
    }

    private static JsonNode sleep(NodeSidecarBridge bridge, int ms) throws Exception {
        return bridge.callTool("sleep", new ObjectMapper().createObjectNode().put("ms", ms));
    }

    private void write(Path root, String relativePath, String content) throws IOException {
        Path file = root.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static boolean nodeAvailable() {
        try {
            Process process = new ProcessBuilder("node", "--version").start();
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            return finished && process.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
