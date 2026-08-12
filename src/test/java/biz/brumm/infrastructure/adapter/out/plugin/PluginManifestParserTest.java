package biz.brumm.infrastructure.adapter.out.plugin;

import biz.brumm.domain.model.Plugin;
import biz.brumm.domain.model.PluginType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PluginManifestParserTest {

    private PluginManifestParser parser;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        parser = new PluginManifestParser(new ObjectMapper());
    }

    @Test
    void parsesValidOpenClawManifest() throws IOException {
        Path manifest = writeManifest("openclaw.plugin.json", """
                {
                  "id": "acme/demo",
                  "name": "Demo Plugin",
                  "version": "1.2.0",
                  "description": "Test-Plugin.",
                  "configSchema": { "type": "object" }
                }
                """);

        Plugin plugin = parser.parse(tempDir, manifest, PluginType.OPENCLAW);

        assertThat(plugin.id()).isEqualTo("acme/demo");
        assertThat(plugin.name()).isEqualTo("Demo Plugin");
        assertThat(plugin.version()).isEqualTo("1.2.0");
        assertThat(plugin.description()).isEqualTo("Test-Plugin.");
        assertThat(plugin.type()).isEqualTo(PluginType.OPENCLAW);
        assertThat(plugin.baseDir()).isEqualTo(tempDir.toString());
        assertThat(plugin.valid()).isTrue();
        assertThat(plugin.validationMessage()).isEmpty();
    }

    @Test
    void rejectsOpenClawManifestWithoutId() throws IOException {
        Path manifest = writeManifest("openclaw.plugin.json", """
                { "name": "Ohne Id" }
                """);

        Plugin plugin = parser.parse(tempDir, manifest, PluginType.OPENCLAW);

        assertThat(plugin.valid()).isFalse();
        assertThat(plugin.validationMessage()).contains("'id'");
    }

    @Test
    void rejectsOpenClawManifestWithNonObjectConfigSchema() throws IOException {
        Path manifest = writeManifest("openclaw.plugin.json", """
                { "id": "acme/demo", "configSchema": ["nicht", "objekt"] }
                """);

        Plugin plugin = parser.parse(tempDir, manifest, PluginType.OPENCLAW);

        assertThat(plugin.valid()).isFalse();
        assertThat(plugin.validationMessage()).contains("configSchema");
    }

    @Test
    void rejectsOpenClawManifestWithNonObjectMcpServers() throws IOException {
        Path manifest = writeManifest("openclaw.plugin.json", """
                { "id": "acme/demo", "mcpServers": "http://localhost:9999" }
                """);

        Plugin plugin = parser.parse(tempDir, manifest, PluginType.OPENCLAW);

        assertThat(plugin.valid()).isFalse();
        assertThat(plugin.validationMessage()).contains("mcpServers");
    }

    @Test
    void rejectsMalformedJson() throws IOException {
        Path manifest = writeManifest("openclaw.plugin.json", "{ kein json");

        Plugin plugin = parser.parse(tempDir, manifest, PluginType.OPENCLAW);

        assertThat(plugin.valid()).isFalse();
        assertThat(plugin.validationMessage()).contains("ungültiges JSON");
    }

    @Test
    void rejectsNonObjectManifest() throws IOException {
        Path manifest = writeManifest("openclaw.plugin.json", "[1, 2, 3]");

        Plugin plugin = parser.parse(tempDir, manifest, PluginType.OPENCLAW);

        assertThat(plugin.valid()).isFalse();
        assertThat(plugin.validationMessage()).contains("JSON-Objekt");
    }

    @Test
    void usesNameAsIdForForeignBundle() throws IOException {
        Path manifest = writeManifest("plugin.json", """
                { "name": "agent-plugin", "version": "1.0.0", "description": "..." }
                """);

        Plugin plugin = parser.parse(tempDir, manifest, PluginType.AGENT_PLUGINS);

        assertThat(plugin.id()).isEqualTo("agent-plugin");
        assertThat(plugin.valid()).isTrue();
    }

    @Test
    void rejectsForeignBundleWithoutNameOrVersion() throws IOException {
        Path manifest = writeManifest(".codex-plugin/plugin.json", """
                { "description": "Nur Beschreibung." }
                """);

        Plugin plugin = parser.parse(tempDir, manifest, PluginType.CODEX);

        assertThat(plugin.valid()).isFalse();
        assertThat(plugin.validationMessage()).contains("'name'", "'version'");
    }

    private Path writeManifest(String relativePath, String json) throws IOException {
        Path file = tempDir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, json, StandardCharsets.UTF_8);
        return file;
    }
}
