package biz.brumm.domain.service;

import biz.brumm.config.GuardrailProperties;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Secret-Egress-Host-Binding (P4-09, fail-closed): bekannte Secrets dürfen als Bestandteil
 * eines ausgehenden Requests (URL, Header, Body der Agent-Web-Tools) nur an die exakt
 * gebundenen Zielhosts gesendet werden.
 *
 * <p>Reagiert auf OpenClaw 2026.6.34/8.1 (Secret egress host binding): Ein Secret ohne
 * Bindung wird <strong>nie</strong> egresst; ein Request an einen nicht gebundenen Host wird
 * vor jedem Netzwerkzugriff abgewiesen. Hosts werden kleingeschrieben und exakt verglichen
 * (keine Wildcards, keine Suffix-Matches, Ports werden nicht berücksichtigt). Bei deaktiviertem
 * Guardrail ({@code jclaw.security.guardrail.enabled=false}) wird nichts blockiert.
 */
@Service
public class SecretEgressGuard {

    private final GuardrailProperties properties;
    private final CredentialLeakGuard credentialLeakGuard;

    public SecretEgressGuard(GuardrailProperties properties, CredentialLeakGuard credentialLeakGuard) {
        this.properties = properties;
        this.credentialLeakGuard = credentialLeakGuard;
    }

    public boolean isEnabled() {
        return credentialLeakGuard.isEnabled();
    }

    /** Prüft eine ausgehende URL (Konvenienz: ohne Header/Body). */
    public EgressBlock checkEgress(String url) {
        return checkEgress(url, Map.of(), null);
    }

    /**
     * Prüft einen ausgehenden Request (URL und optional Header/Body) gegen die Host-Bindings.
     * Gibt {@code null} zurück, wenn der Egress erlaubt ist, sonst ein {@link EgressBlock}
     * mit Host, betroffenem Secret und den gebundenen Hosts (fail-closed).
     */
    public EgressBlock checkEgress(String url, Map<String, String> headers, String body) {
        if (!credentialLeakGuard.isEnabled()) {
            return null;
        }
        String host = hostOf(url);
        if (host == null) {
            return null;
        }
        String payload = payload(url, headers, body);
        for (String secret : credentialLeakGuard.knownSecrets()) {
            if (payload == null || !payload.contains(secret)) {
                continue;
            }
            List<String> allowedHosts = allowedHosts(secret);
            boolean bound = allowedHosts.stream()
                    .map(h -> h.toLowerCase(Locale.ROOT))
                    .anyMatch(host::equals);
            if (!bound) {
                return new EgressBlock(host, secret, allowedHosts);
            }
        }
        return null;
    }

    /** Extrahiert den Zielhost einer URL (kleingeschrieben, ohne Port); {@code null}, wenn nicht erkennbar. */
    static String hostOf(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(url.strip());
            String host = uri.getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String payload(String url, Map<String, String> headers, String body) {
        StringBuilder sb = new StringBuilder();
        if (url != null) {
            sb.append(url);
        }
        if (headers != null) {
            headers.forEach((key, value) -> sb.append('\n').append(key).append(':').append(value));
        }
        if (body != null) {
            sb.append('\n').append(body);
        }
        return sb.toString();
    }

    private List<String> allowedHosts(String secret) {
        for (GuardrailProperties.HostBinding binding : properties.bindings()) {
            if (binding.secret().equals(secret)) {
                return binding.allowedHosts();
            }
        }
        return List.of();
    }

    /** Beschreibt einen abgewiesenen Egress (fail-closed): Zielhost, betroffenes Secret und gebundene Hosts. */
    public record EgressBlock(String host, String secret, List<String> allowedHosts) {

        public String message() {
            if (allowedHosts.isEmpty()) {
                return "Egress gesperrt (fail-closed): Secret '" + secret + "' ist an keinen Host gebunden und darf "
                        + "das Netzwerk nicht verlassen (Ziel: " + host + "). Bindung über "
                        + "jclaw.security.guardrail.bindings einrichten.";
            }
            return "Egress gesperrt (fail-closed): Host '" + host + "' ist für Secret '" + secret
                    + "' nicht freigegeben (gebundene Hosts: " + String.join(", ", allowedHosts) + ").";
        }
    }
}