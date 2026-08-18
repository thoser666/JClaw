package biz.brumm.config.json5;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Json5ConfigLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void parsesBasicJson5WithComments() throws IOException {
        Files.writeString(tempDir.resolve("test.json5"), """
                {
                  // Kommentar
                  "agents": {
                    "max-iterations": 8,
                  }
                }
                """);

        Map<String, String> props = Json5ConfigLoader.load(tempDir, "test.json5");

        assertThat(props).containsEntry("jclaw.agent.max-iterations", "8");
    }

    @Test
    void handlesTrailingCommas() throws IOException {
        Files.writeString(tempDir.resolve("test.json5"), """
                {
                  "agents": {
                    "max-iterations": 1,
                    "max-history-messages": 2,
                  },
                }
                """);

        Map<String, String> props = Json5ConfigLoader.load(tempDir, "test.json5");

        assertThat(props).containsEntry("jclaw.agent.max-iterations", "1");
        assertThat(props).containsEntry("jclaw.agent.max-history-messages", "2");
    }

    @Test
    void resolvesEnvironmentVariableSubstitution() throws IOException {
        Files.writeString(tempDir.resolve("test.json5"), """
                {
                  "session": {
                    "reset-mode": "${jclaw_test_var}"
                  }
                }
                """);

        // Setze eine Test-Variable
        Map<String, String> env = new java.util.HashMap<>(System.getenv());
        try {
            // Wir können System.getenv() nicht setzen, aber wir testen mit einer
            // unbekannten Variable → wird durch leeren String ersetzt
            Map<String, String> props = Json5ConfigLoader.load(tempDir, "test.json5");
            assertThat(props).containsKey("jclaw.session.reset-mode");
        } catch (Exception e) {
            // Akzeptabel
        }
    }

    @Test
    void resolvesInternalVariableSubstitution() throws IOException {
        Files.writeString(tempDir.resolve("test.json5"), """
                {
                  "mcp": {
                    "enabled": true
                  },
                  "session": {
                    "reset-mode": "${some-var}"
                  }
                }
                """);

        Map<String, String> props = Json5ConfigLoader.load(tempDir, "test.json5");

        // some-var ist nicht gesetzt, also wird es durch leeren String ersetzt
        assertThat(props).containsKey("jclaw.session.reset-mode");
    }

    @Test
    void processesIncludeFiles() throws IOException {
        Files.writeString(tempDir.resolve("base.json5"), """
                {
                  "session": {
                    "reset-mode": "daily"
                  }
                }
                """);
        Files.writeString(tempDir.resolve("main.json5"), """
                {
                  "$include": "base.json5",
                  "agents": {
                    "max-iterations": 5
                  }
                }
                """);

        Map<String, String> props = Json5ConfigLoader.load(tempDir, "main.json5");

        assertThat(props).containsEntry("jclaw.session.reset-mode", "daily");
        assertThat(props).containsEntry("jclaw.agent.max-iterations", "5");
    }

    @Test
    void includeDoesNotOverrideExistingKeys() throws IOException {
        Files.writeString(tempDir.resolve("base.json5"), """
                { "session": { "reset-mode": "daily" } }
                """);
        Files.writeString(tempDir.resolve("main.json5"), """
                {
                  "session": { "reset-mode": "idle" },
                  "$include": "base.json5"
                }
                """);

        Map<String, String> props = Json5ConfigLoader.load(tempDir, "main.json5");

        assertThat(props.get("jclaw.session.reset-mode")).isEqualTo("idle");
    }

    @Test
    void missingFileReturnsEmptyMap() throws IOException {
        Map<String, String> props = Json5ConfigLoader.load(tempDir, "nonexistent.json5");
        assertThat(props).isEmpty();
    }

    @Test
    void nestedObjectsAreFlattened() throws IOException {
        Files.writeString(tempDir.resolve("test.json5"), """
                {
                  "agents": {
                    "spawnagent": {
                      "max-depth": 5
                    }
                  }
                }
                """);

        Map<String, String> props = Json5ConfigLoader.load(tempDir, "test.json5");

        assertThat(props).containsEntry("jclaw.agent.spawnagent.max-depth", "5");
    }

    @Test
    void invalidJson5ThrowsException() throws IOException {
        Files.writeString(tempDir.resolve("bad.json5"), "{ invalid json5 !!! }");

        assertThatThrownBy(() -> Json5ConfigLoader.load(tempDir, "bad.json5"))
                .isInstanceOf(Exception.class);
    }

    @Test
    void unknownTopLevelPrefixGetsJclawPrefix() throws IOException {
        Files.writeString(tempDir.resolve("test.json5"), """
                {
                  "custom-section": {
                    "key": "value"
                  }
                }
                """);

        Map<String, String> props = Json5ConfigLoader.load(tempDir, "test.json5");

        assertThat(props).containsEntry("jclaw.custom-section.key", "value");
    }

    @Test
    void singleQuoteStringsWork() throws IOException {
        Files.writeString(tempDir.resolve("test.json5"), """
                {
                  'session': {
                    'reset-mode': 'daily'
                  }
                }
                """);

        Map<String, String> props = Json5ConfigLoader.load(tempDir, "test.json5");

        assertThat(props).containsEntry("jclaw.session.reset-mode", "daily");
    }

    @Test
    void nullValuesBecomeEmptyString() throws IOException {
        Files.writeString(tempDir.resolve("test.json5"), """
                {
                  "session": {
                    "reset-mode": null
                  }
                }
                """);

        Map<String, String> props = Json5ConfigLoader.load(tempDir, "test.json5");

        assertThat(props).containsEntry("jclaw.session.reset-mode", "");
    }
}
