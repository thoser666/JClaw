package biz.brumm.config.json5;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Json5ConfigReloadServiceTest {

    @TempDir
    Path tempDir;

    private ConfigurableEnvironment environment;
    private Json5ConfigReloadService reloadService;

    @BeforeEach
    void setUp() {
        environment = new StandardEnvironment();
        reloadService = new Json5ConfigReloadService(environment, tempDir, "openclaw.json");
    }

    @Test
    void reloadUpdatesEnvironmentWithNewProperties() throws IOException, Json5ConfigValidationException {
        Files.writeString(tempDir.resolve("openclaw.json"), """
                {
                  "agents": {
                    "max-iterations": 12
                  }
                }
                """);

        boolean result = reloadService.reload();

        assertThat(result).isTrue();
        assertThat(environment.getProperty("jclaw.agent.max-iterations")).isEqualTo("12");
    }

    @Test
    void reloadRemovesOldPropertySourceAndAddsNew() throws IOException, Json5ConfigValidationException {
        // Erst initial laden
        Files.writeString(tempDir.resolve("openclaw.json"), """
                {
                  "agents": {
                    "max-iterations": 8
                  }
                }
                """);
        reloadService.reload();
        assertThat(environment.getProperty("jclaw.agent.max-iterations")).isEqualTo("8");

        // Ändern
        Files.writeString(tempDir.resolve("openclaw.json"), """
                {
                  "agents": {
                    "max-iterations": 16
                  }
                }
                """);
        reloadService.reload();

        assertThat(environment.getProperty("jclaw.agent.max-iterations")).isEqualTo("16");
    }

    @Test
    void reloadReturnsFalseWhenFileNotFound() throws IOException {
        boolean result = reloadService.reload();
        assertThat(result).isFalse();
    }

    @Test
    void reloadThrowsOnInvalidConfig() throws IOException {
        Files.writeString(tempDir.resolve("openclaw.json"), """
                {
                  "session": {
                    "reset-mode": "weekly"
                  }
                }
                """);

        assertThatThrownBy(() -> reloadService.reload())
                .isInstanceOf(Json5ConfigValidationException.class)
                .hasMessageContaining("weekly");
    }

    @Test
    void reloadKeepsExistingPropertiesWhenNewConfigMissing() throws IOException, Json5ConfigValidationException {
        // Initial laden mit idle
        Files.writeString(tempDir.resolve("openclaw.json"), """
                {
                  "session": {
                    "reset-mode": "idle"
                  }
                }
                """);
        reloadService.reload();
        assertThat(environment.getProperty("jclaw.session.reset-mode")).isEqualTo("idle");

        // Neu laden mit daily
        Files.writeString(tempDir.resolve("openclaw.json"), """
                {
                  "session": {
                    "reset-mode": "daily"
                  }
                }
                """);
        reloadService.reload();

        assertThat(environment.getProperty("jclaw.session.reset-mode")).isEqualTo("daily");
    }
}
