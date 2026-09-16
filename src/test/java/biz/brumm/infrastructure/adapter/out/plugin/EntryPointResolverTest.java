package biz.brumm.infrastructure.adapter.out.plugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class EntryPointResolverTest {

    @TempDir
    Path tempDir;

    private final EntryPointResolver resolver = new EntryPointResolver();

    @Test
    void resolvesMainFromPackageJson() throws IOException {
        Path plugin = Files.createDirectory(tempDir.resolve("plugin"));
        write(plugin, "package.json", "{\"name\":\"p\",\"main\":\"src/entry.js\"}");
        write(plugin, "src/entry.js", "module.exports = {};");

        assertThat(resolver.resolve(plugin))
                .hasValueSatisfying(path -> assertThat(path.getFileName().toString()).isEqualTo("entry.js"));
    }

    @Test
    void fallsBackToSrcIndexJsWithoutPackageJsonMain() throws IOException {
        Path plugin = Files.createDirectory(tempDir.resolve("plugin"));
        write(plugin, "package.json", "{\"name\":\"p\"}");
        write(plugin, "src/index.js", "module.exports = {};");

        assertThat(resolver.resolve(plugin))
                .hasValueSatisfying(path -> assertThat(path.getFileName().toString()).isEqualTo("index.js"));
    }

    @Test
    void fallsBackForIndexJsAndMainJs() throws IOException {
        Path plugin = Files.createDirectory(tempDir.resolve("plugin"));
        write(plugin, "index.js", "module.exports = {};");

        assertThat(resolver.resolve(plugin)).isPresent();

        Path plugin2 = Files.createDirectory(tempDir.resolve("plugin2"));
        write(plugin2, "main.js", "module.exports = {};");

        assertThat(resolver.resolve(plugin2))
                .hasValueSatisfying(path -> assertThat(path.getFileName().toString()).isEqualTo("main.js"));
    }

    @Test
    void ignoresMainPointingOutsidePluginDir() throws IOException {
        Path outside = tempDir.resolve("outside.js");
        Files.writeString(outside, "module.exports = {};", StandardCharsets.UTF_8);
        Path plugin = Files.createDirectory(tempDir.resolve("plugin"));
        write(plugin, "package.json", "{\"name\":\"p\",\"main\":\"../outside.js\"}");
        write(plugin, "src/index.js", "module.exports = {};");

        assertThat(resolver.resolve(plugin))
                .hasValueSatisfying(path -> assertThat(path.getFileName().toString()).isEqualTo("index.js"));
    }

    @Test
    void ignoresMainPointingToMissingFile() throws IOException {
        Path plugin = Files.createDirectory(tempDir.resolve("plugin"));
        write(plugin, "package.json", "{\"name\":\"p\",\"main\":\"src/absent.js\"}");
        write(plugin, "index.js", "module.exports = {};");

        assertThat(resolver.resolve(plugin))
                .hasValueSatisfying(path -> assertThat(path.getFileName().toString()).isEqualTo("index.js"));
    }

    @Test
    void returnsEmptyWithoutAnyEntry() throws IOException {
        Path plugin = Files.createDirectory(tempDir.resolve("plugin"));
        write(plugin, "openclaw.plugin.json", "{\"id\":\"acme/x\"}");

        assertThat(resolver.resolve(plugin)).isEmpty();
    }

    private void write(Path root, String relativePath, String content) throws IOException {
        Path file = root.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}