package biz.brumm.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spiegel-Tests (782-784) der Plugin-Laufzeit-Konfiguration: Defaults der Install-Provenance
 * (P4-09) sind fail-closed (trusted = bundled/catalog, allow leer), eine explizit leere
 * trusted-sources-Liste wird nicht durch Defaults überschrieben (strengster Modus).
 */
class PluginRuntimePropertiesTest {

    @Test
    void appliesDefaultsForMissingConfiguration() {
        PluginRuntimeProperties properties = new PluginRuntimeProperties(true, 0, null, null);

        assertThat(properties.callTimeoutMillis())
                .isEqualTo(PluginRuntimeProperties.DEFAULT_CALL_TIMEOUT_MILLIS);
        assertThat(properties.trustedSources())
                .isEqualTo(PluginRuntimeProperties.DEFAULT_TRUSTED_SOURCES);
        assertThat(properties.allow()).isEmpty();
    }

    @Test
    void emptyTrustedSourcesAreNotReplacedByDefaults() {
        PluginRuntimeProperties properties = new PluginRuntimeProperties(true, 15_000, List.of(), List.of());

        assertThat(properties.trustedSources()).isEmpty();
    }

    @Test
    void keepsConfiguredValues() {
        PluginRuntimeProperties properties =
                new PluginRuntimeProperties(true, 9_999, List.of("bundled", "local"), List.of("acme/trusted"));

        assertThat(properties.callTimeoutMillis()).isEqualTo(9_999);
        assertThat(properties.trustedSources()).containsExactly("bundled", "local");
        assertThat(properties.allow()).containsExactly("acme/trusted");
    }
}