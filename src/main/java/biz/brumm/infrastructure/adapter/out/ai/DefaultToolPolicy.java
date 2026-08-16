package biz.brumm.infrastructure.adapter.out.ai;

import biz.brumm.config.ToolPolicyProperties;
import biz.brumm.domain.port.out.ToolPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Standard-Implementierung der {@link ToolPolicy} auf Basis der
 * {@code jclaw.agent.tools.*}-Konfiguration (Allow-/Denyliste, Deny schlägt Allow).
 */
@Component
public class DefaultToolPolicy implements ToolPolicy {

    private static final Logger log = LoggerFactory.getLogger(DefaultToolPolicy.class);

    private final ToolPolicyProperties properties;

    public DefaultToolPolicy(ToolPolicyProperties properties) {
        this.properties = properties;
        logActivePolicies();
    }

    @Override
    public boolean isToolEnabled(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return false;
        }
        if (properties.deny().contains(toolName)) {
            return false;
        }
        return properties.allow().isEmpty() || properties.allow().contains(toolName);
    }

    private void logActivePolicies() {
        if (!properties.allow().isEmpty()) {
            log.info("Tool-Allowliste aktiv: {}", String.join(", ", properties.allow()));
        }
        if (!properties.deny().isEmpty()) {
            log.info("Tool-Denyliste aktiv: {}", String.join(", ", properties.deny()));
        }
    }
}
