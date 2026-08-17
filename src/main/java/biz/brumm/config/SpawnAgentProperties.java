package biz.brumm.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "jclaw.agent.spawnagent")
public record SpawnAgentProperties(boolean enabled, int maxDepth) {

    public static final int DEFAULT_MAX_DEPTH = 3;

    public SpawnAgentProperties {
        if (maxDepth < 0) {
            throw new IllegalArgumentException("jclaw.agent.spawnagent.max-depth darf nicht negativ sein.");
        }
    }

    public int effectiveMaxDepth() {
        return maxDepth <= 0 ? DEFAULT_MAX_DEPTH : maxDepth;
    }
}
