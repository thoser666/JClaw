package biz.brumm.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Konfiguration der Security-Guardrails (P4-09).
 *
 * @param enabled  Deny-by-Default: erst mit {@code true} werden Secrets redigiert und
 *                 Secret-Egress-Host-Bindings durchgesetzt.
 * @param secrets  Zusätzliche, explizit bekannte Secrets/Token (optional).
 * @param bindings Secret-Egress-Host-Bindung (fail-closed): jedes Secret darf im Outbound
 *                 nur an die exakt gebundenen Hosts (Default: keine Bindung = nie egressen).
 */
@ConfigurationProperties(prefix = "jclaw.security.guardrail")
public record GuardrailProperties(boolean enabled, List<String> secrets, List<HostBinding> bindings) {

    public GuardrailProperties {
        secrets = (secrets == null) ? List.of() : List.copyOf(secrets);
        bindings = (bindings == null) ? List.of() : List.copyOf(bindings);
    }

    /**
     * Bindung eines Secrets an exakt erlaubte Zielhosts (kleingeschrieben, exakt verglichen;
     * keine Wildcards oder Suffix-Matches, Ports werden nicht berücksichtigt).
     */
    public record HostBinding(String secret, List<String> allowedHosts) {

        public HostBinding {
            allowedHosts = (allowedHosts == null) ? List.of() : List.copyOf(allowedHosts);
        }
    }
}