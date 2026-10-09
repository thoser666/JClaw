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
 * im Sidecar ({@code .ts}/{@code .mts}/{@code .cts}, erasable Syntax). Ab Spiegel-Test
 * 952 zusätzlich echte ESM-Bundles ({@code import}/{@code export}) inkl. der
 * SDK-Subpath-Imports {@code openclaw/plugin-sdk(,/plugin-entry)} und
 * {@code openclaw-plugin(,/channel)}.
 * Übersprungen, wenn Node nicht verfügbar ist.
 * <p>
 * Spiegel-Test 925-961.
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

    // Spiegel-Test 952 — ESM-TypeScript-Bundle: import aus openclaw/plugin-sdk/plugin-entry, export default.
    @Test
    @EnabledIf("nodeAvailable")
    void esmEntryViaSdkSubpathImport() throws IOException {
        Path plugin = Files.createDirectories(tempDir.resolve("esmsdk"));
        write(plugin, "package.json", "{\"type\":\"module\"}");
        write(plugin, "src/index.ts", """
                import { definePluginEntry } from "openclaw/plugin-sdk/plugin-entry";
                export default definePluginEntry({
                  id: 'acme/esmsdk',
                  name: 'ESM SDK',
                  register(api) {
                    api.registerTool({
                      name: 'yell',
                      description: 'Schreit einen Text.',
                      execute(args) { return { out: String(args.text || '').toUpperCase() }; }
                    });
                  }
                });
                """);

        try (NodeSidecarBridge bridge = bridge()) {
            JsonNode receipt = loadBundle(bridge, plugin, "src/index.ts");
            assertThat(receipt.path("name").asString()).isEqualTo("ESM SDK");

            assertThat(bridge.callTool("yell", objectMapper.createObjectNode().put("text", "leise"))
                    .path("out").asString()).isEqualTo("LEISE");
        }
    }

    // Spiegel-Test 953 — .mts-Entry bindet einen ESM-Helper mit named exports ein (export const/function).
    @Test
    @EnabledIf("nodeAvailable")
    void mtsEntryWithEsmNamedExportHelper() throws IOException {
        Path plugin = Files.createDirectories(tempDir.resolve("mtsnamed"));
        write(plugin, "src/index.mts", """
                import { definePluginEntry } from "openclaw/plugin-sdk/plugin-entry";
                import { double, triple } from './math.mts';
                export default definePluginEntry({
                  id: 'acme/mtsnamed',
                  name: 'MTS Named',
                  register(api) {
                    api.registerTool({
                      name: 'scale',
                      description: 'Verdoppelt und verdreifacht.',
                      execute(args) {
                        const n = Number(args.n);
                        return { doubled: double(n), tripled: triple(n) };
                      }
                    });
                  }
                });
                """);
        write(plugin, "src/math.mts", """
                export const double = (n: number): number => n * 2;
                export function triple(n: number): number { return n * 3; }
                """);

        try (NodeSidecarBridge bridge = bridge()) {
            loadBundle(bridge, plugin, "src/index.mts");

            JsonNode result = bridge.callTool("scale", objectMapper.createObjectNode().put("n", 21));
            assertThat(result.path("doubled").asInt()).isEqualTo(42);
            assertThat(result.path("tripled").asInt()).isEqualTo(63);
        }
    }

    // Spiegel-Test 954 — ESM-Channel-Plugin lädt über openclaw-plugin/channel und empfängt Nachrichten.
    @Test
    @EnabledIf("nodeAvailable")
    void esmChannelPluginViaOpenclawPluginChannel() throws IOException {
        Path plugin = Files.createDirectories(tempDir.resolve("esmchan"));
        write(plugin, "src/channel.mts", """
                import { defineChannelPluginEntry } from "openclaw-plugin/channel";
                export default defineChannelPluginEntry({
                  id: 'acme/esmchan',
                  name: 'ESM Channel',
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
            JsonNode receipt = loadBundle(bridge, plugin, "src/channel.mts");
            assertThat(receipt.path("channels").get(0).path("name").asString()).isEqualTo("events");

            JsonNode delivered = bridge.deliverChannelMessage("events",
                    objectMapper.createObjectNode().put("text", "knock"));
            assertThat(delivered.path("got").asString()).isEqualTo("knock!");
        }
    }

    // Spiegel-Test 955 — .mjs-Entry (reines JS-ESM) mit Default-Export-Helper.
    @Test
    @EnabledIf("nodeAvailable")
    void mjsEntryWithEsmDefaultExportHelper() throws IOException {
        Path plugin = Files.createDirectories(tempDir.resolve("mjsmod"));
        write(plugin, "src/index.mjs", """
                import { definePluginEntry } from "openclaw/plugin-sdk/plugin-entry";
                import bang from './bang.mjs';
                export default definePluginEntry({
                  id: 'acme/mjsmod',
                  name: 'MJS Module',
                  register(api) {
                    api.registerTool({
                      name: 'boom',
                      description: 'Hängt Ausrufezeichen an.',
                      execute(args) { return { out: bang(args.text) }; }
                    });
                  }
                });
                """);
        write(plugin, "src/bang.mjs", "export default (v) => (v || '') + '!';");

        try (NodeSidecarBridge bridge = bridge()) {
            loadBundle(bridge, plugin, "src/index.mjs");

            assertThat(bridge.callTool("boom", objectMapper.createObjectNode().put("text", "ok"))
                    .path("out").asString()).isEqualTo("ok!");
        }
    }

    // Spiegel-Test 956 — CommonJS-Bundles dürfen die SDK-Boundary direkt per require nutzen.
    @Test
    @EnabledIf("nodeAvailable")
    void commonJsEntryRequiresSdkBoundary() throws IOException {
        Path plugin = Files.createDirectories(tempDir.resolve("cjs-sdk"));
        write(plugin, "src/index.js", """
                const { definePluginEntry } = require('openclaw/plugin-sdk');
                module.exports = definePluginEntry({
                  id: 'acme/cjssdk',
                  name: 'CJS SDK',
                  register(api) {
                    api.registerTool({
                      name: 'lower',
                      description: 'Klein.',
                      execute(args) { return { out: String(args.text || '').toLowerCase() }; }
                    });
                  }
                });
                """);

        try (NodeSidecarBridge bridge = bridge()) {
            loadBundle(bridge, plugin, "src/index.js");

            assertThat(bridge.callTool("lower", objectMapper.createObjectNode().put("text", "RUF"))
                    .path("out").asString()).isEqualTo("ruf");
        }
    }

    // Spiegel-Test 957 — ESM-Re-Export-Formen (export * / export { x } from) in Helpers.
    @Test
    @EnabledIf("nodeAvailable")
    void mtsHelperSupportsExportStarAndNamedReexport() throws IOException {
        Path plugin = Files.createDirectories(tempDir.resolve("rexport"));
        write(plugin, "src/index.mts", """
                import { upper, lower, lowerAlias } from './combined.mts';
                export default definePluginEntry({
                  id: 'acme/rexport',
                  name: 'Re-Export',
                  register(api) {
                    api.registerTool({
                      name: 'case',
                      description: 'Groß und klein.',
                      execute(args) {
                        const t = String(args.text);
                        return { out: upper(t) + ' ' + lower(t) + ' ' + lowerAlias(t) };
                      }
                    });
                  }
                });
                """);
        write(plugin, "src/upper.mts", "export const upper = (s) => String(s).toUpperCase();");
        write(plugin, "src/lower.mts", "export const lower = (s) => String(s).toLowerCase();");
        write(plugin, "src/combined.mts", """
                export * from './upper.mts';
                export { lower, lower as lowerAlias } from './lower.mts';
                """);

        try (NodeSidecarBridge bridge = bridge()) {
            loadBundle(bridge, plugin, "src/index.mts");

            assertThat(bridge.callTool("case", objectMapper.createObjectNode().put("text", "aBc"))
                    .path("out").asString()).isEqualTo("ABC abc abc");
        }
    }

    // Spiegel-Test 958 — Type-only Import + getypte Funktion in einem .mts-Bundle (Stripping vor Transform).
    @Test
    @EnabledIf("nodeAvailable")
    void mtsEntryWithTypeOnlyImportAndTypedFunction() throws IOException {
        Path plugin = Files.createDirectories(tempDir.resolve("generic"));
        write(plugin, "src/index.mts", """
                import { definePluginEntry } from "openclaw/plugin-sdk/plugin-entry";
                import type { Opts } from './opts.mts';
                function calc(o: Opts): number { return o.n * 2; }
                export default definePluginEntry({
                  id: 'acme/generic',
                  name: 'Generic',
                  register(api) {
                    api.registerTool({
                      name: 'gcalc',
                      description: 'Rechnet verdoppelt.',
                      execute(args) { return { result: calc(args) }; }
                    });
                  }
                });
                """);
        write(plugin, "src/opts.mts", "export interface Opts { n: number; }");

        try (NodeSidecarBridge bridge = bridge()) {
            loadBundle(bridge, plugin, "src/index.mts");

            assertThat(bridge.callTool("gcalc", objectMapper.createObjectNode().put("n", 21))
                    .path("result").asInt()).isEqualTo(42);
        }
    }

    // Spiegel-Test 959 — .js-Dateien unter package.json "type":"module" werden als ESM geladen.
    @Test
    @EnabledIf("nodeAvailable")
    void plainJsEsmModuleUnderTypeModulePackageJson() throws IOException {
        Path plugin = Files.createDirectories(tempDir.resolve("js-esm"));
        write(plugin, "package.json", "{\"type\":\"module\"}");
        write(plugin, "src/index.js", """
                import { definePluginEntry } from "openclaw/plugin-sdk/plugin-entry";
                import { bang } from './bang.js';
                export default definePluginEntry({
                  id: 'acme/jsesm',
                  name: 'JS ESM',
                  register(api) {
                    api.registerTool({
                      name: 'gotcha',
                      description: 'Hängt ein Ausrufezeichen an.',
                      execute(args) { return { out: bang(args.text) }; }
                    });
                  }
                });
                """);
        write(plugin, "src/bang.js", "export const bang = (v) => (v || '') + '!';");

        try (NodeSidecarBridge bridge = bridge()) {
            loadBundle(bridge, plugin, "src/index.js");

            assertThat(bridge.callTool("gotcha", objectMapper.createObjectNode().put("text", "hey"))
                    .path("out").asString()).isEqualTo("hey!");
        }
    }

    // Spiegel-Test 960 — import.meta bleibt nativer (im CJS-Kompilat unzulässiger) Syntax → ERROR_PLUGIN_INVALID.
    @Test
    @EnabledIf("nodeAvailable")
    void esmImportMetaIsRefusedInBundleMode() throws IOException {
        Path plugin = Files.createDirectories(tempDir.resolve("imeta"));
        write(plugin, "src/index.mjs", """
                export const url = import.meta.url;
                export default definePluginEntry({ id: 'acme/imeta', name: 'Meta', register() {} });
                """);

        try (NodeSidecarBridge bridge = bridge()) {
            assertThatThrownBy(() -> loadBundle(bridge, plugin, "src/index.mjs"))
                    .isInstanceOf(SidecarCallException.class)
                    .satisfies(e -> assertThat(((SidecarCallException) e).code())
                            .isEqualTo(NodeSidecarBridge.ERROR_PLUGIN_INVALID));
        }
    }

    // Spiegel-Test 961 — ESM-Eintrag ohne abschließende Semikola (ASI-robustes Import-Ende, export default bis EOF).
    @Test
    @EnabledIf("nodeAvailable")
    void esmEntryWithoutTrailingSemicolonsLoads() throws IOException {
        Path plugin = Files.createDirectories(tempDir.resolve("nosemi"));
        write(plugin, "src/index.mts", """
                import { definePluginEntry } from 'openclaw/plugin-sdk/plugin-entry'
                export default definePluginEntry({
                  id: 'acme/nosemi',
                  name: 'No Semicolon',
                  register(api) {
                    api.registerTool({
                      name: 'warn',
                      description: 'Hängt ein Warn-Zeichen an.',
                      execute(args) { return { out: String(args.text || '') + '!' }; }
                    });
                  }
                })
                """);

        try (NodeSidecarBridge bridge = bridge()) {
            JsonNode receipt = loadBundle(bridge, plugin, "src/index.mts");
            assertThat(receipt.path("name").asString()).isEqualTo("No Semicolon");

            assertThat(bridge.callTool("warn", objectMapper.createObjectNode().put("text", "Achtung"))
                    .path("out").asString()).isEqualTo("Achtung!");
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