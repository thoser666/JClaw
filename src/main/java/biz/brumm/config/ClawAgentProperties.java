package biz.brumm.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "jclaw.agent")
public record ClawAgentProperties(int maxIterations, int maxHistoryMessages) {

    public ClawAgentProperties {
        if (maxIterations <= 0) {
            throw new IllegalArgumentException("jclaw.agent.max-iterations muss positiv sein.");
        }
        if (maxHistoryMessages <= 0) {
            throw new IllegalArgumentException("jclaw.agent.max-history-messages muss positiv sein.");
        }
    }
}
