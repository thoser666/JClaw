package biz.brumm.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Konfiguration des Credential-Leak-Guardrails (P4-09).
 *
 * @param enabled Deny-by-Default: erst mit {@code true} werden Secrets redigiert.
 * @param secrets Zusätzliche, explizit bekannte Secrets/Token (optional).
 */
@ConfigurationProperties(prefix = "jclaw.security.guardrail")
public record GuardrailProperties(boolean enabled, List<String> secrets) {

    public GuardrailProperties {
        secrets = (secrets == null) ? List.of() : List.copyOf(secrets);
    }
}