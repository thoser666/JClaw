package biz.brumm.infrastructure.adapter.out.plugin;

import biz.brumm.config.PluginProperties;
import biz.brumm.config.PluginRuntimeProperties;
import biz.brumm.domain.model.Plugin;
import biz.brumm.infrastructure.sidecar.NodeSidecarBridge;
import biz.brumm.infrastructure.sidecar.SidecarCallException;
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
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integrationstests der Plugin-Laufzeit (P4-01) gegen das echte Node.js-Runtime-Sidecar.
 * Übersprungen, wenn Node nicht verfügbar ist.
 */
class NodeSidecarPluginRuntimeTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @EnabledIf("nodeAvailable")
    void loadsPluginRegisteringToolAtRuntime() throws IOException {
        Path plugin = pluginDir("greet", "acme/greet", """
                module.exports = definePluginEntry({
                  id: 'acme/greet',
                  name: 'Greet Demo',
                  register(api) {
                    api.registerTool({
                      name: 'greet',
                      description: 'Begrüßt jemanden.',
                      parameters: {
                        type: 'object',
                        properties: { name: { type: 'string' } },
                        required: ['name']
                      },
                      execute(args) { return { greeting: 'Hallo ' + args.name }; }
                    });
                    api.registerCommand({
                      name: 'greet-help',
                      description: 'Slash-Command.',
                      execute() { return { ok: true }; }
                    });
                  }
                });
                """);
        Plugin pluginModel = plugin(tempDir, "acme/greet");

        try (NodeSidecarPluginRuntime runtime = runtime(tempDir)) {
            Optional<NodeSidecarPluginRuntime.PluginLoadReceipt> receipt = runtime.load(pluginModel);

            assertThat(receipt).isPresent();
            NodeSidecarPluginRuntime.PluginLoadReceipt loaded = receipt.orElseThrow();
            assertThat(loaded.id()).isEqualTo("acme/greet");
            assertThat(loaded.name()).isEqualTo("Greet Demo");
            assertThat(loaded.tools()).extracting(NodeSidecarPluginRuntime.PluginToolRegistration::name)
                    .containsExactly("greet");
            assertThat(loaded.tools().get(0).parameters().path("required").get(0).asString()).isEqualTo("name");
            assertThat(loaded.commands()).containsExactly("greet-help");
            assertThat(runtime.isLoaded("acme/greet")).isTrue();

            assertThat(runtime.tools()).extracting(t -> t.name()).contains("greet");

            ObjectNode arguments = objectMapper.createObjectNode().put("name", "Anna");
            JsonNode result = runtime.callTool("greet", arguments);
            assertThat(result.path("greeting").asString()).isEqualTo("Hallo Anna");
        }
    }

    @Test
    @EnabledIf("nodeAvailable")
    void resolvesEntryPointViaPackageJsonMain() throws IOException {
        Path pluginDir = Files.createDirectories(tempDir.resolve("main-plugin"));
        write(pluginDir, "openclaw.plugin.json", "{\"id\":\"acme/main\"}");
        write(pluginDir, "package.json", "{\"name\":\"main-plugin\",\"main\":\"src/entry.js\"}");
        write(pluginDir, "src/entry.js", """
                module.exports = definePluginEntry({
                  id: 'acme/main',
                  name: 'Main Demo',
                  register(api) {
                    api.registerTool({
                      name: 'main-hello',
                      description: 'Sagt Hallo.',
                      execute() { return { hello: 'main' }; }
                    });
                  }
                });
                """);

        Plugin pluginModel = plugin(tempDir, "acme/main");

        try (NodeSidecarPluginRuntime runtime = runtime(tempDir)) {
            Optional<NodeSidecarPluginRuntime.PluginLoadReceipt> receipt = runtime.load(pluginModel);

            assertThat(receipt).isPresent();
            assertThat(receipt.orElseThrow().tools())
                    .extracting(NodeSidecarPluginRuntime.PluginToolRegistration::name)
                    .containsExactly("main-hello");
            assertThat(runtime.callTool("main-hello", null).path("hello").asString()).isEqualTo("main");
        }
    }

    @Test
    @EnabledIf("nodeAvailable")
    void beforeToolCallHookCanBlockTheCall() throws IOException {
        Path pluginDir = pluginDir("hook", "acme/hook", """
                module.exports = definePluginEntry({
                  id: 'acme/hook',
                  name: 'Hook Demo',
                  register(api) {
                    api.registerTool({
                      name: 'greet',
                      description: 'Begrüßt jemanden.',
                      parameters: {
                        type: 'object',
                        properties: { name: { type: 'string' } },
                        required: ['name']
                      },
                      execute(args) { return { greeting: 'Hallo ' + args.name }; }
                    });
                    api.on('before_tool_call', (ctx) => {
                      if (ctx.arguments && ctx.arguments.name === 'block') {
                        throw new Error('Name ist blockiert.');
                      }
                    }, { matcher: 'greet', priority: 10 });
                  }
                });
                """);

        try (NodeSidecarPluginRuntime runtime = runtime(tempDir)) {
            Optional<NodeSidecarPluginRuntime.PluginLoadReceipt> receipt = runtime.load(plugin(tempDir, "acme/hook"));

            assertThat(receipt).isPresent();
            assertThat(receipt.orElseThrow().hooks())
                    .extracting(NodeSidecarPluginRuntime.PluginHookRegistration::event)
                    .containsExactly("before_tool_call");
            assertThat(receipt.orElseThrow().hooks().get(0).priority()).isEqualTo(10);

            assertThatThrownBy(() -> runtime.callTool("greet", objectMapper.createObjectNode().put("name", "block")))
                    .isInstanceOf(SidecarCallException.class)
                    .satisfies(e -> assertThat(((SidecarCallException) e).code())
                            .isEqualTo(NodeSidecarBridge.ERROR_HOOK_BLOCKED))
                    .hasMessageContaining("before_tool_call")
                    .hasMessageContaining("blockiert");

            JsonNode allowed = runtime.callTool("greet", objectMapper.createObjectNode().put("name", "Anna"));
            assertThat(allowed.path("greeting").asString()).isEqualTo("Hallo Anna");
        }
    }

    @Test
    @EnabledIf("nodeAvailable")
    void afterToolCallHookCanBlockTheResult() throws IOException {
        Path pluginDir = pluginDir("after", "acme/after", """
                module.exports = definePluginEntry({
                  id: 'acme/after',
                  name: 'After Demo',
                  register(api) {
                    api.registerTool({
                      name: 'greet',
                      description: 'Begrüßt jemanden.',
                      execute(args) { return { greeting: 'Hallo ' + args.name }; }
                    });
                    api.on('after_tool_call', (ctx) => {
                      if (ctx.result && ctx.result.greeting && ctx.result.greeting.includes('secret')) {
                        throw new Error('Ergebnis ist geheim.');
                      }
                    }, { matcher: 'greet' });
                  }
                });
                """);

        try (NodeSidecarPluginRuntime runtime = runtime(tempDir)) {
            runtime.load(plugin(tempDir, "acme/after"));

            assertThatThrownBy(() -> runtime.callTool("greet", objectMapper.createObjectNode().put("name", "secret")))
                    .isInstanceOf(SidecarCallException.class)
                    .satisfies(e -> assertThat(((SidecarCallException) e).code())
                            .isEqualTo(NodeSidecarBridge.ERROR_HOOK_BLOCKED))
                    .hasMessageContaining("after_tool_call");

            JsonNode allowed = runtime.callTool("greet", objectMapper.createObjectNode().put("name", "Anna"));
            assertThat(allowed.path("greeting").asString()).isEqualTo("Hallo Anna");
        }
    }

    @Test
    @EnabledIf("nodeAvailable")
    void supportsDefineChannelPluginEntry() throws IOException {
        Path pluginDir = pluginDir("chan", "acme/chan", """
                module.exports = defineChannelPluginEntry({
                  id: 'acme/chan',
                  name: 'Channel Demo',
                  register(api) {
                    api.registerTool({
                      name: 'chan-hello',
                      description: 'Channel-Tool.',
                      execute() { return { channel: 'hello' }; }
                    });
                  }
                });
                """);

        try (NodeSidecarPluginRuntime runtime = runtime(tempDir)) {
            Optional<NodeSidecarPluginRuntime.PluginLoadReceipt> receipt = runtime.load(plugin(tempDir, "acme/chan"));

            assertThat(receipt).isPresent();
            assertThat(receipt.orElseThrow().tools())
                    .extracting(NodeSidecarPluginRuntime.PluginToolRegistration::name)
                    .containsExactly("chan-hello");
            assertThat(runtime.callTool("chan-hello", null).path("channel").asString()).isEqualTo("hello");
        }
    }

    @Test
    @EnabledIf("nodeAvailable")
    void sourceWithoutEntryFailsValidation() throws IOException {
        Path pluginDir = pluginDir("bad", "acme/bad", "module.exports = { id: 'acme/bad', name: 'Bad' };");

        try (NodeSidecarPluginRuntime runtime = runtime(tempDir)) {
            assertThatThrownBy(() -> runtime.load(plugin(tempDir, "acme/bad")))
                    .isInstanceOf(SidecarCallException.class)
                    .satisfies(e -> assertThat(((SidecarCallException) e).code())
                            .isEqualTo(NodeSidecarBridge.ERROR_PLUGIN_INVALID))
                    .hasMessageContaining("definePluginEntry");
        }
    }

    @Test
    @EnabledIf("nodeAvailable")
    void missingEntryReturnsEmptyReceipt() throws IOException {
        Path pluginDir = Files.createDirectories(tempDir.resolve("noentry"));
        write(pluginDir, "openclaw.plugin.json", "{\"id\":\"acme/noentry\"}");

        try (NodeSidecarPluginRuntime runtime = runtime(tempDir)) {
            assertThat(runtime.load(plugin(tempDir, "acme/noentry"))).isEmpty();
            assertThat(runtime.isLoaded("acme/noentry")).isFalse();
        }
    }

    @Test
    @EnabledIf("nodeAvailable")
    void unloadRemovesPluginTools() throws IOException {
        Path pluginDir = pluginDir("unload", "acme/unload", """
                module.exports = definePluginEntry({
                  id: 'acme/unload',
                  name: 'Unload Demo',
                  register(api) {
                    api.registerTool({
                      name: 'temp-tool',
                      description: 'Verschwindet.',
                      execute() { return { gone: true }; }
                    });
                  }
                });
                """);

        try (NodeSidecarPluginRuntime runtime = runtime(tempDir)) {
            runtime.load(plugin(tempDir, "acme/unload"));
            assertThat(runtime.tools()).extracting(t -> t.name()).contains("temp-tool");

            assertThat(runtime.unload("acme/unload")).isTrue();

            assertThat(runtime.isLoaded("acme/unload")).isFalse();
            assertThat(runtime.tools()).extracting(t -> t.name()).doesNotContain("temp-tool");
        }
    }

    @Test
    @EnabledIf("nodeAvailable")
    void reloadReplacesPluginRegistrations() throws IOException {
        Path pluginDir = pluginDir("reload", "acme/reload", """
                module.exports = definePluginEntry({
                  id: 'acme/reload',
                  name: 'Reload',
                  register(api) {
                    api.registerTool({
                      name: 'wink',
                      description: 'Erste Version.',
                      execute() { return { eye: 'wink' }; }
                    });
                  }
                });
                """);

        try (NodeSidecarPluginRuntime runtime = runtime(tempDir)) {
            runtime.load(plugin(tempDir, "acme/reload"));
            assertThat(runtime.tools()).extracting(t -> t.name()).contains("wink").doesNotContain("flutter");

            write(pluginDir, "src/index.js", """
                    module.exports = definePluginEntry({
                      id: 'acme/reload',
                      name: 'Reload',
                      register(api) {
                        api.registerTool({
                          name: 'flutter',
                          description: 'Zweite Version.',
                          execute() { return { eye: 'flutter' }; }
                        });
                      }
                    });
                    """);

            runtime.load(plugin(tempDir, "acme/reload"));

            assertThat(runtime.tools()).extracting(t -> t.name()).contains("flutter").doesNotContain("wink");
            assertThat(runtime.callTool("flutter", null).path("eye").asString()).isEqualTo("flutter");
        }
    }

    @Test
    @EnabledIf("nodeAvailable")
    void loadAvailableLoadsOnlyValidOpenClawPluginsWithEntry() throws IOException {
        Path pluginsDir = tempDir.resolve("many");
        Files.createDirectories(pluginsDir);
        pluginDir(pluginsDir.resolve("one"), "acme/one", """
                module.exports = definePluginEntry({
                  id: 'acme/one', name: 'One',
                  register(api) { api.registerTool({ name: 'one-tool', description: 'Eins.', execute() { return { n: 1 }; } }); }
                });
                """);
        pluginDir(pluginsDir.resolve("two"), "acme/two", """
                module.exports = definePluginEntry({
                  id: 'acme/two', name: 'Two',
                  register(api) { api.registerTool({ name: 'two-tool', description: 'Zwei.', execute() { return { n: 2 }; } }); }
                });
                """);
        Path noEntry = Files.createDirectories(pluginsDir.resolve("three"));
        write(noEntry, "openclaw.plugin.json", "{\"id\":\"acme/three\"}");

        try (NodeSidecarPluginRuntime runtime = runtime(pluginsDir)) {
            List<NodeSidecarPluginRuntime.PluginLoadReceipt> loaded = runtime.loadAvailable();

            assertThat(loaded).extracting(NodeSidecarPluginRuntime.PluginLoadReceipt::id)
                    .containsExactlyInAnyOrder("acme/one", "acme/two");
            assertThat(runtime.tools()).extracting(t -> t.name())
                    .contains("one-tool", "two-tool")
                    .doesNotContain("three-tool");
        }
    }

    @Test
    @EnabledIf("nodeAvailable")
    void closeTerminatesTheSidecarProcess() throws IOException {
        NodeSidecarPluginRuntime runtime = runtime(tempDir);
        pluginDir("close", "acme/close", """
                module.exports = definePluginEntry({
                  id: 'acme/close', name: 'Close',
                  register(api) { api.registerTool({ name: 'x', description: 'X.', execute() { return { x: 1 }; } }); }
                });
                """);
        runtime.load(plugin(tempDir, "acme/close"));
        assertThat(runtime.active()).isTrue();

        runtime.close();

        assertThat(runtime.active()).isFalse();
        assertThatThrownBy(() -> runtime.callTool("x", null))
                .isInstanceOf(IOException.class);
    }

    @Test
    @EnabledIf("nodeAvailable")
    void unloadUnknownPluginIsSafe() throws IOException {
        try (NodeSidecarPluginRuntime runtime = runtime(tempDir)) {
            assertThat(runtime.unload("gibtsNicht")).isFalse();
            assertThat(runtime.isLoaded("gibtsNicht")).isFalse();
        }
    }

    private NodeSidecarPluginRuntime runtime(Path pluginsDir) {
        FileSystemPluginProvider provider = new FileSystemPluginProvider(
                new PluginProperties(pluginsDir.toString()), objectMapper);
        return new NodeSidecarPluginRuntime(provider, objectMapper,
                new PluginRuntimeProperties(true, 15_000));
    }

    private Path pluginDir(String name, String id, String entrySource) throws IOException {
        return pluginDir(tempDir.resolve(name), id, entrySource);
    }

    private Path pluginDir(Path dir, String id, String entrySource) throws IOException {
        Files.createDirectories(dir);
        write(dir, "openclaw.plugin.json", "{\"id\":\"" + id + "\"}");
        write(dir, "src/index.js", entrySource);
        return dir;
    }

    private Plugin plugin(Path root, String id) throws IOException {
        return new FileSystemPluginProvider(new PluginProperties(root.toString()), objectMapper).findAll()
                .stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow();
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