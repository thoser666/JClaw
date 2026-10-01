package biz.brumm.infrastructure.adapter.out.plugin;

import biz.brumm.config.PluginRuntimeProperties;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spiegel-Tests (773-781) des Plugin-Install-Provenance-Gates (P4-09, fail-closed).
 * OpenClaw-Semantik: vertrauenswürdige Quellen (bundled/catalog) laden ohne Freigabe; beliebige
 * Quellen brauchen trusted-sources oder ein allow-Pin (plugins.allow / --force); ungültige
 * Provenance (provenance-invalid) wird nie automatisch vertraut.
 */
class PluginInstallProvenanceGuardTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void trustedBundledSourceLoadsWithoutPin() throws IOException {
        Plugin plugin = pluginWithRecord("acme/bundled", "{\"source\":\"bundled\"}");
        PluginInstallProvenanceGuard guard = guard(List.of("bundled", "catalog"), List.of());

        assertThat(guard.checkLoad(plugin)).isEmpty();
    }

    @Test
    void trustedCatalogSourceLoads() throws IOException {
        Plugin plugin = pluginWithRecord("acme/catalog", "{\"source\":\"catalog\"}");
        PluginInstallProvenanceGuard guard = guard(List.of("bundled", "catalog"), List.of());

        assertThat(guard.checkLoad(plugin)).isEmpty();
    }

    @Test
    void arbitrarySourceIsBlockedWithoutTrust() throws IOException {
        Plugin plugin = pluginWithRecord("acme/local", "{\"source\":\"local\"}");
        PluginInstallProvenanceGuard guard = guard(List.of("bundled", "catalog"), List.of());

        Optional<PluginInstallProvenanceGuard.ProvenanceBlock> block = guard.checkLoad(plugin);

        assertThat(block).isPresent();
        assertThat(block.get().pluginId()).isEqualTo("acme/local");
        assertThat(block.get().message())
                .contains("local")
                .contains("plugins.allow");
    }

    @Test
    void arbitrarySourceLoadsWhenPluginIdIsPinnedInAllow() throws IOException {
        Plugin plugin = pluginWithRecord("acme/local", "{\"source\":\"local\"}");
        PluginInstallProvenanceGuard guard = guard(List.of("bundled", "catalog"), List.of("acme/local"));

        assertThat(guard.checkLoad(plugin)).isEmpty();
    }

    @Test
    void arbitrarySourceLoadsWhenSourceTrustedViaConfig() throws IOException {
        Plugin plugin = pluginWithRecord("acme/local", "{\"source\":\"local\"}");
        PluginInstallProvenanceGuard guard = guard(List.of("bundled", "catalog", "local"), List.of());

        assertThat(guard.checkLoad(plugin)).isEmpty();
    }

    @Test
    void pluginWithoutInstallProvenanceIsBlocked() throws IOException {
        Plugin plugin = pluginWithoutRecord("acme/noprov");
        PluginInstallProvenanceGuard guard = guard(List.of("bundled", "catalog"), List.of());

        Optional<PluginInstallProvenanceGuard.ProvenanceBlock> block = guard.checkLoad(plugin);

        assertThat(block).isPresent();
        assertThat(block.get().message()).contains("Install-Provenance");
    }

    @Test
    void pluginWithoutInstallProvenanceLoadsWhenPinnedInAllow() throws IOException {
        Plugin plugin = pluginWithoutRecord("acme/pinned");
        PluginInstallProvenanceGuard guard = guard(List.of("bundled", "catalog"), List.of("acme/pinned"));

        assertThat(guard.checkLoad(plugin)).isEmpty();
    }

    @Test
    void invalidProvenanceIsNeverTrustedEvenWhenPinned() throws IOException {
        Plugin plugin = pluginWithRecord("acme/invalid", "{}");
        PluginInstallProvenanceGuard guard = guard(List.of("bundled", "catalog"), List.of("acme/invalid"));

        Optional<PluginInstallProvenanceGuard.ProvenanceBlock> block = guard.checkLoad(plugin);

        assertThat(block).isPresent();
        assertThat(block.get().message()).contains("provenance-invalid");
    }

    @Test
    void unreadableProvenanceRecordIsBlocked() throws IOException {
        Plugin plugin = pluginWithRecord("acme/defekt", "kein-json");
        PluginInstallProvenanceGuard guard = guard(List.of("bundled", "catalog"), List.of("acme/defekt"));

        Optional<PluginInstallProvenanceGuard.ProvenanceBlock> block = guard.checkLoad(plugin);

        assertThat(block).isPresent();
        assertThat(block.get().message()).contains("provenance-invalid");
    }

    private PluginInstallProvenanceGuard guard(List<String> trustedSources, List<String> allow) {
        return new PluginInstallProvenanceGuard(objectMapper,
                new PluginRuntimeProperties(true, 15_000, trustedSources, allow));
    }

    private Plugin pluginWithRecord(String id, String recordJson) throws IOException {
        Path baseDir = tempDir.resolve(id);
        Files.createDirectories(baseDir);
        Path record = baseDir.resolve(".jclaw/install.json");
        Files.createDirectories(record.getParent());
        Files.writeString(record, recordJson, StandardCharsets.UTF_8);
        return plugin(id, baseDir);
    }

    private Plugin pluginWithoutRecord(String id) throws IOException {
        return plugin(id, Files.createDirectories(tempDir.resolve(id)));
    }

    private Plugin plugin(String id, Path baseDir) {
        return new Plugin(id, id, "1.0.0", "Test", PluginType.OPENCLAW, baseDir.toString(), true, "");
    }
}