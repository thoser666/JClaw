package biz.brumm.infrastructure.adapter.out.plugin;

import biz.brumm.config.PluginProperties;
import biz.brumm.domain.model.Plugin;
import biz.brumm.domain.model.PluginType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FileSystemPluginProviderTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsOpenClawPluginFromManifest() throws IOException {
        Path pluginDir = Files.createDirectory(tempDir.resolve("demo"));
        Files.writeString(pluginDir.resolve("openclaw.plugin.json"), """
                {
                  "id": "acme/demo",
                  "name": "Demo",
                  "version": "1.0.0",
                  "configSchema": { "type": "object" }
                }
                """, StandardCharsets.UTF_8);

        List<Plugin> plugins = provider().findAll();

        assertThat(plugins).hasSize(1);
        Plugin plugin = plugins.get(0);
        assertThat(plugin.id()).isEqualTo("acme/demo");
        assertThat(plugin.type()).isEqualTo(PluginType.OPENCLAW);
        assertThat(plugin.valid()).isTrue();
        assertThat(plugin.baseDir()).isEqualTo(pluginDir.toString());
    }

    @Test
    void detectsForeignBundleFormats() throws IOException {
        write(tempDir, "agent/plugin.json", "{\"name\":\"agent\",\"version\":\"1.0.0\"}");
        write(tempDir, "codex/.codex-plugin/plugin.json", "{\"name\":\"codex\",\"version\":\"2.0.0\"}");
        write(tempDir, "claude/.claude-plugin/plugin.json", "{\"name\":\"claude\",\"version\":\"3.0.0\"}");
        write(tempDir, "cursor/.cursor-plugin/plugin.json", "{\"name\":\"cursor\",\"version\":\"4.0.0\"}");

        List<Plugin> plugins = provider().findAll();

        assertThat(plugins).extracting(Plugin::id)
                .containsExactlyInAnyOrder("agent", "codex", "claude", "cursor");
        assertThat(plugins).extracting(Plugin::type)
                .containsExactlyInAnyOrder(PluginType.AGENT_PLUGINS, PluginType.CODEX,
                        PluginType.CLAUDE, PluginType.CURSOR);
    }

    @Test
    void skipsDirectoriesWithoutManifest() throws IOException {
        Files.createDirectory(tempDir.resolve("plain"));
        Files.writeString(tempDir.resolve("plain/readme.md"), "Nur Doku.", StandardCharsets.UTF_8);

        assertThat(provider().findAll()).isEmpty();
    }

    @Test
    void sortsPluginsById() throws IOException {
        write(tempDir, "zeta/openclaw.plugin.json", "{\"id\":\"zeta\"}");
        write(tempDir, "alpha/openclaw.plugin.json", "{\"id\":\"alpha\"}");

        List<Plugin> plugins = provider().findAll();

        assertThat(plugins).extracting(Plugin::id).containsExactly("alpha", "zeta");
    }

    @Test
    void returnsInvalidPluginForMalformedManifest() throws IOException {
        write(tempDir, "broken/openclaw.plugin.json", "{ kaputt");

        List<Plugin> plugins = provider().findAll();

        assertThat(plugins).hasSize(1);
        assertThat(plugins.get(0).valid()).isFalse();
        assertThat(plugins.get(0).validationMessage()).contains("ungültiges JSON");
    }

    @Test
    void returnsEmptyListForMissingDirectory() {
        assertThat(provider().findAll()).isEmpty();
    }

    private FileSystemPluginProvider provider() {
        return new FileSystemPluginProvider(new PluginProperties(tempDir.toString()), new ObjectMapper());
    }

    private void write(Path root, String relativePath, String json) throws IOException {
        Path file = root.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, json, StandardCharsets.UTF_8);
    }
}
