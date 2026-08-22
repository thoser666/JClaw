package biz.brumm.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Set;

@ConfigurationProperties(prefix = "jclaw.auth")
public record AuthProperties(boolean enabled, Set<String> publicPaths) {

    public AuthProperties {
        if (publicPaths == null) {
            publicPaths = Set.of();
        }
    }

    public static AuthProperties disabled() {
        return new AuthProperties(false, Set.of());
    }
}
