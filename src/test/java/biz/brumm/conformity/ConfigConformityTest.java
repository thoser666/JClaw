package biz.brumm.conformity;

import biz.brumm.config.json5.Json5ConfigLoader;
import biz.brumm.config.json5.Json5ConfigValidationException;
import biz.brumm.config.json5.Json5ConfigValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P4-05: Konfig-Konformitätstest (Paritäts-Testsuite).
 * <p>
 * Prüft, dass eine OpenClaw-referenzkonforme JSON5-Konfiguration (Kommentare, Trailing
 * Commas, OpenClaw-Kurzschlüssel inkl. Paritätssektionen wie {@code channels}, {@code security},
 * {@code auth}, {@code memory}) geladen, korrekt auf Spring-Properties gemappt und valide ist —
 * und dass die strikte Validierung (unbekannte Sektionen, ungültige Werte) weiterhin greift.
 */
class ConfigConformityTest {

    @TempDir
    Path tempDir;

    @Test
    void referenceConfigLoadsAndPassesValidation() throws IOException {
        writeReferenceFixture();

        Map<String, String> raw = Json5ConfigLoader.loadRaw(tempDir, "openclaw.json");

        assertThatCode(() -> Json5ConfigValidator.validate(raw))
                .describedAs("Referenz-OpenClaw-Konfiguration muss durch den JSON5-Validator akzeptiert werden.")
                .doesNotThrowAnyException();
    }

    @Test
    void referenceConfigMapsToSpringProperties() throws IOException {
        writeReferenceFixture();

        Map<String, String> props = Json5ConfigLoader.load(tempDir, "openclaw.json");

        assertThat(props)
                .describedAs("OpenClaw-Kurzschlüssel müssen auf jclaw.*-Properties gemappt werden.")
                .containsEntry("jclaw.agent.max-iterations", "8")
                .containsEntry("jclaw.session.reset-mode", "idle")
                .containsEntry("jclaw.mcp.enabled", "false")
                .containsEntry("jclaw.channels.enabled", "false")
                .containsEntry("jclaw.security.guardrail.enabled", "false")
                .containsEntry("jclaw.auth.enabled", "false")
                .containsEntry("jclaw.memory.vault.dir", "./vault")
                .containsEntry("jclaw.background.enabled", "false");
    }

    @Test
    void unknownTopLevelSectionStillRejected() {
        assertThatThrownBy(() -> Json5ConfigValidator.validate(Map.of("unknown-section.key", "value")))
                .isInstanceOf(Json5ConfigValidationException.class)
                .hasMessageContaining("unknown-section");
    }

    @Test
    void invalidResetModeStillRejected() {
        assertThatThrownBy(() -> Json5ConfigValidator.validate(Map.of("session.reset-mode", "weekly")))
                .isInstanceOf(Json5ConfigValidationException.class)
                .hasMessageContaining("reset-mode");
    }

    private void writeReferenceFixture() throws IOException {
        Path target = tempDir.resolve("openclaw.json");
        try (InputStream in = ConfigConformityTest.class.getResourceAsStream(
                "/fixtures/config/openclaw-reference.json5")) {
            if (in == null) {
                throw new IllegalStateException("Referenz-Fixture nicht gefunden: /fixtures/config/openclaw-reference.json5");
            }
            Files.writeString(target, new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}