package biz.brumm.domain.service;

import biz.brumm.config.GuardrailProperties;
import biz.brumm.domain.model.Channel;
import biz.brumm.domain.port.out.ChannelStore;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Credential-Leak-Guardrail (P4-09): entfernt bekannte Secrets aus Anzeigen und
 * Outbound-Nachrichten.
 *
 * <p>Reagiert auf OpenClaw #32970: Token-/Secret-Teile sollen nie in
 * Session-Anzeigen oder Outbound-Inhalten auftauchen. Bekannte Secrets kommen aus
 * der Konfiguration ({@link GuardrailProperties#secrets()}) und aus allen
 * Channel-Konfigurationen (Values unter secret-tragenden Keys).
 */
@Service
public class CredentialLeakGuard {

    /** Platzhalter, der anstelle eines bekannten Secrets eingesetzt wird. */
    public static final String REDACTED = "[REDACTED]";

    /** Config-Keys, deren Werte als Geheimnisse behandelt werden. */
    private static final Set<String> SENSITIVE_CONFIG_KEYS = Set.of(
            "token", "privateKey", "password", "webhookUrl", "verifyToken",
            "nickservPassword", "accessToken", "apiKey", "appSecret", "clientSecret");

    private final GuardrailProperties properties;
    private final ChannelStore channelStore;
    private volatile List<String> knownSecrets = List.of();

    public CredentialLeakGuard(GuardrailProperties properties, ChannelStore channelStore) {
        this.properties = properties;
        this.channelStore = channelStore;
        refresh();
    }

    /**
     * Frischt die Liste bekannter Secrets auf (explizit konfigurierte + aller
     * Channel-Config-Secrets). Wird beim Start und nach Channel-Änderungen aufgerufen.
     */
    public void refresh() {
        Set<String> secrets = new LinkedHashSet<>(properties.secrets());
        if (channelStore != null) {
            for (Channel channel : channelStore.findAllChannels()) {
                if (channel.config() == null) {
                    continue;
                }
                channel.config().forEach((key, value) -> {
                    if (SENSITIVE_CONFIG_KEYS.contains(key) && value instanceof String s && !s.isBlank()) {
                        secrets.add(s);
                    }
                });
            }
        }
        knownSecrets = List.copyOf(secrets);
    }

    public boolean isEnabled() {
        return properties.enabled();
    }

    /** Liefert die aktuell bekannten Secrets (explizit konfigurierte + Channel-Config-Secrets). */
    public List<String> knownSecrets() {
        return knownSecrets;
    }

    /**
     * Ersetzt alle bekannten Secrets im Text durch {@link #REDACTED}.
     * Bei deaktiviertem Guardrail oder leerem Text unveraendert.
     */
    public String redact(String text) {
        if (!properties.enabled() || text == null || text.isBlank() || knownSecrets.isEmpty()) {
            return text;
        }
        String result = text;
        for (String secret : knownSecrets) {
            result = result.replaceAll(Pattern.quote(secret), REDACTED);
        }
        return result;
    }

    /**
     * Gibt eine Kopie der Config zurueck, in der Werte unter secret-tragenden
     * Keys vollstaendig durch {@link #REDACTED} ersetzt sind und alle weiteren
     * String-Werte redigiert wurden.
     */
    public Map<String, Object> sanitizeConfig(Map<String, Object> config) {
        if (!properties.enabled() || config == null) {
            return config;
        }
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        config.forEach((key, value) -> {
            if (SENSITIVE_CONFIG_KEYS.contains(key)) {
                result.put(key, REDACTED);
            } else if (value instanceof String s) {
                result.put(key, redact(s));
            } else {
                result.put(key, value);
            }
        });
        return result;
    }
}