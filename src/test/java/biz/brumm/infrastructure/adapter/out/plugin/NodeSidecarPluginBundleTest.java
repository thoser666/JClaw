package biz.brumm.infrastructure.adapter.out.plugin;

import biz.brumm.infrastructure.sidecar.NodeSidecarBridge;
import biz.brumm.infrastructure.sidecar.SidecarCallException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integrationstests des Bundle-Lademodus (P4-01 npm/TypeScript-Bundles) gegen das echte
 * Node.js-Runtime-Sidecar: hermetischer {@code require}-Scope (relative Module,
 * {@code node_modules}, Builtins, Escape-Rückweisungen) und TypeScript-Type-Stripping
 * im Sidecar ({@code .ts}/{@code .mts}/{@code .cts}, erasable Syntax).
 * Übersprungen, wenn Node nicht verfügbar ist.
 * <p>
 * Spiegel-Test 925-941.
 */
class NodeSidecarPluginBundleTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // Spiegel-Test 925 — Bundle-Entry lädt seine relative Helper-Datei über require('./…').
    @Test
    @EnabledIf("nodeAvailable")
    void loadsBundleEntryWithRelativeHelperModule() throws IOException {
        Path plugin = Files.createDirectories(tempDir.resolve("helper"));
        write(plugin, "src/index.js", """
                const { bang } = require('./bang.js');
                module.exports = definePluginEntry({
                  id: 'acme/helper',
                  name: 'Helper Demo',
                  register(api) {
                    api.registerTool({
                      name: 'echo',
                      description: 'Gibt den Namen mit Ausrufezeichen zurück.',
                      execute(args) { return { echoed: bang(args.name) }; }
                    });
                  }
                });
                """);
        write(plugin, "src/bang.js", "module.exports = { bang: (v) => (v || '') + '!' };");

        try (NodeSidecarBridge bridge = bridge()) {
            JsonNode receipt = loadBundle(bridge, plugin, "src/index.js");

            assertThat(receipt.path("id").asString()).isEqualTo("acme/bundle");
            assertThat(receipt.path("name").asString()).isEqualTo("Helper Demo");
            assertThat(receipt.path("tools").get(0).path("name").asString()).isEqualTo("echo");

            JsonNode result = bridge.callTool("echo", objectMapper.createObjectNode().put("name", "Hallo"));
            assertThat(result.path("echoed").asString()).isEqualTo("Hallo!");
        }
    }

    // Spiegel-Test 926 — Bundles dürfen JSON-Dateien über require('./config.json') laden.
    @Test
    @EnabledIf("nodeAvailable")
    void loadsBundleEntryRequiringJsonModule() throws IOException {
        Path plugin = Files.createDirectories(tempDir.resolve("jsonmod"));
        write(plugin, "src/index.js", """
                const config = require('./config.json');
                module.exports = definePluginEntry({
                  id: 'acme/json',
                  name: 'JSON Demo',
                  register(api) {
                    api.registerTool({
                      name: 'token',
                      description: 'Liefert das konfigurierte Token.',
                      execute() { return { token: config.token }; }
                    });
                  }
                });
                """);
        write(plugin, "src/config.json", "{\"token\":\"t-42\"}");

        try (NodeSidecarBridge bridge = bridge()) {
            JsonNode receipt = loadBundle(bridge, plugin, "src/index.js");
            assertThat(receipt.path("tools").get(0).path("name").asString()).isEqualTo("token");

            assertThat(bridge.callTool("token", null).path("token").asString()).isEqualTo("t-42");
        }
    }

    // Spiegel-Test 927 — npm-Specifier werden über node_modules aufgelöst (package.json → main).
    @Test
    @EnabledIf("nodeAvailable")
    void loadsBundleEntryRequiringNodeModulesPackage() throws IOException {
        Path plugin = Files.createDirectories(tempDir.resolve("npm-pkg"));
        write(plugin, "src/index.js", """
                const loud = require('loud');
                module.exports = definePluginEntry({
                  id: 'acme/npmpkg',
                  name: 'npm Demo',
                  register(api) {
                    api.registerTool({
                      name: 'shout',
                      description: 'Schreit einen Text.',
                      execute(args) { return { out: loud.shout(args.text) }; }
                    });
                  }
                });
                """);
        write(plugin, "node_modules/loud/package.json", "{\"name\":\"loud\",\"main\":\"index.js\"}");
        write(plugin, "node_modules/loud/index.js", "module.exports = { shout: (s) => String(s).toUpperCase() };");

        try (NodeSidecarBridge bridge = bridge()) {
            loadBundle(bridge, plugin, "src/index.js");

            assertThat(bridge.callTool("shout", objectMapper.createObjectNode().put("text", "ruhig"))
                    .path("out").asString()).isEqualTo("RUHIG");
        }
    }

    // Spiegel-Test 928 — Scoped npm-Packages (@scope/pkg) werden ebenfalls aufgelöst.
    @Test
    @EnabledIf("nodeAvailable")
    void loadsBundleEntryRequiringScopedPackage() throws IOException {
        Path plugin = Files.createDirectories(tempDir.resolve("scoped"));
        write(plugin, "src/index.js", """
                const { five } = require('@acme/util');
                module.exports = definePluginEntry({
                  id: 'acme/scoped',
                  name: 'Scoped Demo',
                  register(api) {
                    api.registerTool({
                      name: 'lucky',
                      description: 'Liefert fünf.',
                      execute() { return { n: five() }; }
                    });
                  }
                });
                """);
        write(plugin, "node_modules/@acme/util/index.js", "module.exports = { five: () => 5 };");

        try (NodeSidecarBridge bridge = bridge()) {
            loadBundle(bridge, plugin, "src/index.js");

            assertThat(bridge.callTool("lucky", null).path("n").asInt()).isEqualTo(5);
        }
    }

    // Spiegel-Test 929 — require('./lib') ohne Endung löst auf lib/index.js auf.
    @Test
    @EnabledIf("nodeAvailable")
    void loadsBundleEntryRequiringDirectoryIndex() throws IOException {
        Path plugin = Files.createDirectories(tempDir.resolve("dirindex"));
        write(plugin, "src/index.js", """
                const { squash } = require('./lib');
                module.exports = definePluginEntry({
                  id: 'acme/dirindex',
                  name: 'Dir Index',
                  register(api) {
                    api.registerTool({
                      name: 'mul',
                      description: 'Multipliziert.',
                      execute(args) { return { product: squash(args.a, args.b) }; }
                    });
                  }
                });
                """);
        write(plugin, "src/lib/index.js", "module.exports = { squash: (a, b) => a * b };");

        try (NodeSidecarBridge bridge = bridge()) {
            loadBundle(bridge, plugin, "src/index.js");

            assertThat(bridge.callTool("mul", objectMapper.createObjectNode().put("a", 6).put("b", 7))
                    .path("product").asInt()).isEqualTo(42);
        }
    }

    // Spiegel-Test 930 — Node-Builtins bleiben über den hermetischen Scope erreichbar.
    @Test
    @EnabledIf("nodeAvailable")
    void loadsBundleEntryRequiringNodeBuiltin() throws IOException {
        Path plugin = Files.createDirectories(tempDir.resolve("builtin"));
        write(plugin, "src/index.js", """
                const path = require('path');
                module.exports = definePluginEntry({
                  id: 'acme/builtin',
                  name: 'Builtin Demo',
                  register(api) {
                    api.registerTool({
                      name: 'join',
                      description: 'Joins Pfadteile.',
                      execute() { return { joined: path.join('a', 'b') }; }
                    });
                  }
                });
                """);

        try (NodeSidecarBridge bridge = bridge()) {
            loadBundle(bridge, plugin, "src/index.js");

            String joined = bridge.callTool("join", null).path("joined").asString();
            assertThat(joined).isIn("a" + System.getProperty("file.separator") + "b",
                    "a/b");
        }
    }

    // Spiegel-Test 931 — TypeScript-Entry (erasable Syntax, Typ-Annotationen) wird gestrippt.
    @Test
    @EnabledIf("nodeAvailable")
    void loadsTypeScriptEntryWithTypeStripping() throws IOException {
        Path plugin = Files.createDirectories(tempDir.resolve("tsentry"));
        write(plugin, "src/index.ts", """
                const add = (a: number, b: number): number => a + b;
                module.exports = definePluginEntry({
                  id: 'acme/tsentry',
                  name: 'TS Entry',
                  register(api) {
                    api.registerTool({
                      name: 'sum',
                      description: 'Addiert zwei Zahlen.',
                      execute(args) { return { sum: add(args.a, args.b) }; }
                    });
                  }
                });
                """);

        try (NodeSidecarBridge bridge = bridge()) {
            JsonNode receipt = loadBundle(bridge, plugin, "src/index.ts");
            assertThat(receipt.path("name").asString()).isEqualTo("TS Entry");

            assertThat(bridge.callTool("sum", objectMapper.createObjectNode().put("a", 19).put("b", 23))
                    .path("sum").asInt()).isEqualTo(42);
        }
    }

    // Spiegel-Test 932 — TypeScript-Entry darf einen TypeScript-Helper einbinden.
    @Test
    @EnabledIf("nodeAvailable")
    void typeScriptEntryRequiresTypeScriptHelper() throws IOException {
        Path plugin = Files.createDirectories(tempDir.resolve("tshelper"));
        write(plugin, "src/index.ts", """
                const { squared } = require('./math');
                module.exports = definePluginEntry({
                  id: 'acme/tshelper',
                  name: 'TS Helper',
                  register(api) {
                    api.registerTool({
                      name: 'square',
                      description: 'Quadriert.',
                      execute(args) { return { result: squared(args.n) }; }
                    });
                  }
                });
                """);
        write(plugin, "src/math.ts", "const squared = (n: number): number => n * n;\nmodule.exports = { squared };");

        try (NodeSidecarBridge bridge = bridge()) {
            loadBundle(bridge, plugin, "src/index.ts");

            assertThat(bridge.callTool("square", objectMapper.createObjectNode().put("n", 9))
                    .path("result").asInt()).isEqualTo(81);
        }
    }

    // Spiegel-Test 933 — Type-only Imports (`import type`) sind erasable und werden entfernt.
    @Test
    @EnabledIf("nodeAvailable")
    void typeScriptEntryWithTypeOnlyImportIsErasable() throws IOException {
        Path plugin = Files.createDirectories(tempDir.resolve("erasable"));
        write(plugin, "src/index.ts", """
                import type { SumOptions } from './options';
                module.exports = definePluginEntry({
                  id: 'acme/erasable',
                  name: 'Erasable',
                  register(api) {
                    api.registerTool({
                      name: 'total',
                      description: 'Addiert.',
                      execute(args: SumOptions) { return { total: args.a + args.b }; }
                    });
                  }
                });
                """);
        write(plugin, "src/options.ts", "export interface SumOptions { a: number; b: number; }");

        try (NodeSidecarBridge bridge = bridge()) {
            loadBundle(bridge, plugin, "src/index.ts");

            assertThat(bridge.callTool("total", objectMapper.createObjectNode().put("a", 40).put("b", 2))
                    .path("total").asInt()).isEqualTo(42);
        }
    }

    // Spiegel-Test 934 — Ein npm-Package im Plugin sagt package.json main → .ts und lädt.
    @Test
    @EnabledIf("nodeAvailable")
    void nodeModulesPackageWithTypeScriptMainIsLoadable() throws IOException {
        Path plugin = Files.createDirectories(tempDir.resolve("tsmain"));
        write(plugin, "src/index.js", """
                const { double } = require('tsmath');
                module.exports = definePluginEntry({
                  id: 'acme/tsmain',
                  name: 'TS Main',
                  register(api) {
                    api.registerTool({
                      name: 'times2',
                      description: 'Verdoppelt.',
                      execute(args) { return { result: double(args.n) }; }
                    });
                  }
                });
                """);
        write(plugin, "node_modules/tsmath/package.json", "{\"name\":\"tsmath\",\"main\":\"index.ts\"}");
        write(plugin, "node_modules/tsmath/index.ts",
                "const double = (n: number): number => n * 2;\nmodule.exports = { double };");

        try (NodeSidecarBridge bridge = bridge()) {
            loadBundle(bridge, plugin, "src/index.js");

            assertThat(bridge.callTool("times2", objectMapper.createObjectNode().put("n", 21))
                    .path("result").asInt()).isEqualTo(42);
        }
    }

    // Spiegel-Test 935 — require('../…') darf den Plugin-Ordner nicht verlassen (hermetische Grenze).
    @Test
    @EnabledIf("nodeAvailable")
    void relativeRequireEscapingPluginDirIsRefused() throws IOException {
        Path outside = tempDir.resolve("outside.js");
        Files.writeString(outside, "module.exports = {};", StandardCharsets.UTF_8);
        Path plugin = Files.createDirectories(tempDir.resolve("escape"));
        write(plugin, "src/index.js", """
                const o = require('../../outside.js');
                module.exports = definePluginEntry({ id: 'acme/escape', name: 'Escape', register() {} });
                """);

        try (NodeSidecarBridge bridge = bridge()) {
            assertThatThrownBy(() -> loadBundle(bridge, plugin, "src/index.js"))
                    .isInstanceOf(SidecarCallException.class)
                    .satisfies(e -> assertThat(((SidecarCallException) e).code())
                            .isEqualTo(NodeSidecarBridge.ERROR_PLUGIN_INVALID))
                    .hasMessageContaining("hermetisch");
        }
    }

    // Spiegel-Test 936 — Absoluter require-Pfad außerhalb des Plugin-Ordners ist abgewiesen.
    @Test
    @EnabledIf("nodeAvailable")
    void absoluteRequireOutsidePluginDirIsRefused() throws IOException {
        Path outside = tempDir.resolve("abs-outside.js");
        Files.writeString(outside, "module.exports = {};", StandardCharsets.UTF_8);
        Path plugin = Files.createDirectories(tempDir.resolve("absescape"));
        write(plugin, "src/index.js", """
                const o = require(%s);
                module.exports = definePluginEntry({ id: 'acme/absescape', name: 'Abs', register() {} });
                """.formatted("\"" + outside.toString().replace("\\", "\\\\") + "\""));

        try (NodeSidecarBridge bridge = bridge()) {
            assertThatThrownBy(() -> loadBundle(bridge, plugin, "src/index.js"))
                    .isInstanceOf(SidecarCallException.class)
                    .satisfies(e -> assertThat(((SidecarCallException) e).code())
                            .isEqualTo(NodeSidecarBridge.ERROR_PLUGIN_INVALID))
                    .hasMessageContaining("hermetisch");
        }
    }

    // Spiegel-Test 937 — Ein unbekannter npm-Specifier (kein node_modules-Treffer) ist abgewiesen.
    @Test
    @EnabledIf("nodeAvailable")
    void missingBareSpecifierIsRefused() throws IOException {
        Path plugin = Files.createDirectories(tempDir.resolve("missing"));
        write(plugin, "src/index.js", """
                const nothing = require('gibtsnicht');
                module.exports = definePluginEntry({ id: 'acme/missing', name: 'Missing', register() {} });
                """);

        try (NodeSidecarBridge bridge = bridge()) {
            assertThatThrownBy(() -> loadBundle(bridge, plugin, "src/index.js"))
                    .isInstanceOf(SidecarCallException.class)
                    .satisfies(e -> assertThat(((SidecarCallException) e).code())
                            .isEqualTo(NodeSidecarBridge.ERROR_PLUGIN_INVALID))
                    .hasMessageContaining("node_modules");
        }
    }

    // Spiegel-Test 938 — Nicht-erasable TypeScript-Syntax (enum) wird im Sidecar abgewiesen.
    @Test
    @EnabledIf("nodeAvailable")
    void nonErasableTypeScriptSyntaxIsRefused() throws IOException {
        Path plugin = Files.createDirectories(tempDir.resolve("enum"));
        write(plugin, "src/index.ts", """
                enum Color { Red, Green }
                const color: Color = Color.Red;
                module.exports = definePluginEntry({ id: 'acme/enum', name: 'Enum', register() {} });
                """);

        try (NodeSidecarBridge bridge = bridge()) {
            assertThatThrownBy(() -> loadBundle(bridge, plugin, "src/index.ts"))
                    .isInstanceOf(SidecarCallException.class)
                    .satisfies(e -> assertThat(((SidecarCallException) e).code())
                            .isEqualTo(NodeSidecarBridge.ERROR_PLUGIN_INVALID))
                    .hasMessageContaining("erasable");
        }
    }

    // Spiegel-Test 939 — Ein npm-Package lädt über sein eigenes node_modules seinen Helper.
    @Test
    @EnabledIf("nodeAvailable")
    void nodeModulesPackageWithOwnRelativeHelper() throws IOException {
        Path plugin = Files.createDirectories(tempDir.resolve("pkghelper"));
        write(plugin, "src/index.js", """
                const { wrap } = require('wordy');
                module.exports = definePluginEntry({
                  id: 'acme/pkghelper',
                  name: 'Pkg Helper',
                  register(api) {
                    api.registerTool({
                      name: 'paren',
                      description: 'Klammer um Text.',
                      execute(args) { return { out: wrap(args.text) }; }
                    });
                  }
                });
                """);
        write(plugin, "node_modules/wordy/package.json", "{\"name\":\"wordy\",\"main\":\"index.js\"}");
        write(plugin, "node_modules/wordy/index.js", "const { paren } = require('./fmt.js'); module.exports = { wrap: paren };");
        write(plugin, "node_modules/wordy/fmt.js", "module.exports = { paren: (s) => '(' + s + ')' };");

        try (NodeSidecarBridge bridge = bridge()) {
            loadBundle(bridge, plugin, "src/index.js");

            assertThat(bridge.callTool("paren", objectMapper.createObjectNode().put("text", "hi"))
                    .path("out").asString()).isEqualTo("(hi)");
        }
    }

    // Spiegel-Test 940 — Channel-Plugins laden im Bundle-Modus und empfangen Nachrichten.
    @Test
    @EnabledIf("nodeAvailable")
    void channelPluginBundleViaDefineChannelPluginEntry() throws IOException {
        Path plugin = Files.createDirectories(tempDir.resolve("chanbundle"));
        write(plugin, "src/index.js", """
                module.exports = defineChannelPluginEntry({
                  id: 'acme/chanbundle',
                  name: 'Channel Bundle',
                  register(api) {
                    api.registerChannel({
                      name: 'events',
                      description: 'Ereignisse empfangen.',
                      receive(message) { return { got: (message.text || '') + '!' }; }
                    });
                  }
                });
                """);

        try (NodeSidecarBridge bridge = bridge()) {
            JsonNode receipt = loadBundle(bridge, plugin, "src/index.js");
            assertThat(receipt.path("channels").get(0).path("name").asString()).isEqualTo("events");

            JsonNode delivered = bridge.deliverChannelMessage("events",
                    objectMapper.createObjectNode().put("text", "knock"));
            assertThat(delivered.path("got").asString()).isEqualTo("knock!");
        }
    }

    // Spiegel-Test 941 — npm-Package-Subpaths (require('pkg/lib/tool.js')) lösen direkt auf.
    @Test
    @EnabledIf("nodeAvailable")
    void bundleRequiresPackageSubpathFile() throws IOException {
        Path plugin = Files.createDirectories(tempDir.resolve("subpath"));
        write(plugin, "src/index.js", """
                const { cap } = require('pkg/lib/tool.js');
                module.exports = definePluginEntry({
                  id: 'acme/subpath',
                  name: 'Subpath',
                  register(api) {
                    api.registerTool({
                      name: 'cap',
                      description: 'Großschreibt das erste Zeichen.',
                      execute(args) { return { out: cap(args.text) }; }
                    });
                  }
                });
                """);
        write(plugin, "node_modules/pkg/lib/tool.js", "module.exports = { cap: (s) => String(s).charAt(0).toUpperCase() + String(s).slice(1) };");

        try (NodeSidecarBridge bridge = bridge()) {
            loadBundle(bridge, plugin, "src/index.js");

            assertThat(bridge.callTool("cap", objectMapper.createObjectNode().put("text", "hallo"))
                    .path("out").asString()).isEqualTo("Hallo");
        }
    }

    private NodeSidecarBridge bridge() throws IOException {
        return NodeSidecarBridge.start(NodeSidecarBridge.pluginScript(), objectMapper);
    }

    private JsonNode loadBundle(NodeSidecarBridge bridge, Path pluginDir, String entryRelative) throws IOException {
        return loadBundle(bridge, "acme/bundle", pluginDir, entryRelative);
    }

    private JsonNode loadBundle(NodeSidecarBridge bridge, String id, Path pluginDir, String entryRelative)
            throws IOException {
        return bridge.loadPluginBundle(id, pluginDir.resolve(entryRelative), pluginDir);
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