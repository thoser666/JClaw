package biz.brumm.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpToolPropertiesTest {

    @Test
    void acceptsDefaultsWithoutValidationError() {
        assertThatCode(() -> new McpToolProperties(false, null, null)).doesNotThrowAnyException();
    }

    @Test
    void effectiveTimeoutFallsBackToSixtySeconds() {
        McpToolProperties defaults = new McpToolProperties(false, null, null);

        assertThat(defaults.effectiveRequestTimeout()).isEqualTo(Duration.ofSeconds(60));
        assertThat(defaults.servers()).isEmpty();
    }

    @Test
    void effectiveTimeoutUsesConfiguredValue() {
        McpToolProperties configured = new McpToolProperties(true, Duration.ofSeconds(10), Map.of());

        assertThat(configured.effectiveRequestTimeout()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void serverAcceptsHttpTransport() {
        McpToolProperties.Server server = new McpToolProperties.Server("http://localhost:8080", null, null, null, null);

        assertThat(server.url()).isEqualTo("http://localhost:8080");
        assertThat(server.args()).isEmpty();
        assertThat(server.env()).isEmpty();
    }

    @Test
    void serverAcceptsStdioTransport() {
        McpToolProperties.Server server = new McpToolProperties.Server(
                null, "npx", List.of("-y", "mcp-server"), Map.of("DEBUG", "1"), null);

        assertThat(server.command()).isEqualTo("npx");
        assertThat(server.args()).containsExactly("-y", "mcp-server");
        assertThat(server.env()).containsEntry("DEBUG", "1");
    }

    @Test
    void serverRejectsMissingTransport() {
        assertThatThrownBy(() -> new McpToolProperties.Server(null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("genau eines von 'url' oder 'command'");
    }

    @Test
    void serverRejectsAmbiguousTransport() {
        assertThatThrownBy(() -> new McpToolProperties.Server(
                "http://localhost:8080", "npx", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("genau eines von 'url' oder 'command'");
    }
}
