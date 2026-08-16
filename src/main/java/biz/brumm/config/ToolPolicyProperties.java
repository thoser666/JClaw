package biz.brumm.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Tool-Policy-Konfiguration (OpenClaw: {@code tools.allow}, Deny-by-Default-Listen).
 * <p>
 * {@code allow} = Allowliste der Tool-Namen (leer = alle Tools erlaubt), {@code deny} =
 * Denyliste (leer = kein Tool gesperrt). Deny schlägt immer Allow.
 */
@ConfigurationProperties(prefix = "jclaw.agent.tools")
public record ToolPolicyProperties(List<String> allow, List<String> deny) {

    public ToolPolicyProperties {
        allow = normalize(allow);
        deny = normalize(deny);
    }

    private static List<String> normalize(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .toList();
    }
}
