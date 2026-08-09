package biz.brumm.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "jclaw.agent.skills")
public record SkillProperties(String dir, List<String> enabled) {

    public SkillProperties {
        dir = (dir == null || dir.isBlank()) ? "./skills" : dir;
        enabled = (enabled == null) ? List.of() : List.copyOf(enabled);
    }
}
