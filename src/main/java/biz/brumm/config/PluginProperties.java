package biz.brumm.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "jclaw.agent.plugins")
public record PluginProperties(String dir) {

    public PluginProperties {
        dir = (dir == null || dir.isBlank()) ? "./plugins" : dir;
    }
}
