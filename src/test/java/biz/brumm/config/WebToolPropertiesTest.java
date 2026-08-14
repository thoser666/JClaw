package biz.brumm.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebToolPropertiesTest {

    @Test
    void acceptsDefaultsWithoutValidationError() {
        assertThatCode(() -> new WebToolProperties(false, null, null, null, null, null))
                .doesNotThrowAnyException();
    }

    @Test
    void effectiveValuesFallBackToDefaults() {
        WebToolProperties defaults = new WebToolProperties(false, null, null, null, null, null);

        assertThat(defaults.allowedDomains()).isEmpty();
        assertThat(defaults.effectiveFetchTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(defaults.effectiveMaxFetchBytes()).isEqualTo(200_000);
        assertThat(defaults.effectiveMaxSearchResults()).isEqualTo(5);
        assertThat(defaults.effectiveSearchEndpoint()).isEqualTo("https://api.duckduckgo.com");
    }

    @Test
    void effectiveValuesUseConfiguredValues() {
        WebToolProperties configured = new WebToolProperties(
                true, List.of("example.com", "docs.spring.io"), "https://localhost:9999/search",
                20, 50_000, 3);

        assertThat(configured.allowedDomains()).containsExactly("example.com", "docs.spring.io");
        assertThat(configured.effectiveFetchTimeout()).isEqualTo(Duration.ofSeconds(20));
        assertThat(configured.effectiveMaxFetchBytes()).isEqualTo(50_000);
        assertThat(configured.effectiveMaxSearchResults()).isEqualTo(3);
        assertThat(configured.effectiveSearchEndpoint()).isEqualTo("https://localhost:9999/search");
    }

    @Test
    void nullAllowedDomainsNormalizesToEmptyList() {
        WebToolProperties properties = new WebToolProperties(true, null, null, null, null, null);

        assertThat(properties.allowedDomains()).isEmpty();
        assertThat(properties.allowedDomains()).isUnmodifiable();
    }

    @Test
    void rejectsNonPositiveFetchTimeout() {
        assertThatThrownBy(() -> new WebToolProperties(true, null, null, 0, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fetch-timeout-seconds");
    }

    @Test
    void rejectsNonPositiveMaxFetchBytes() {
        assertThatThrownBy(() -> new WebToolProperties(true, null, null, null, 0, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-fetch-bytes");
    }

    @Test
    void rejectsNonPositiveMaxSearchResults() {
        assertThatThrownBy(() -> new WebToolProperties(true, null, null, null, null, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-search-results");
    }

    @Test
    void blankSearchEndpointFallsBackToDefault() {
        WebToolProperties properties = new WebToolProperties(true, null, "   ", null, null, null);

        assertThat(properties.effectiveSearchEndpoint()).isEqualTo("https://api.duckduckgo.com");
    }
}
