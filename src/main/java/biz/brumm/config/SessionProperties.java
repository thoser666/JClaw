package biz.brumm.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Set;

@ConfigurationProperties(prefix = "jclaw.session")
public record SessionProperties(String resetMode, int resetAtHour, int resetIdleMinutes) {

    public SessionProperties {
        if (resetMode == null) {
            resetMode = "none";
        }
        if (!Set.of("none", "daily", "idle").contains(resetMode)) {
            throw new IllegalArgumentException("jclaw.session.reset-mode muss none, daily oder idle sein.");
        }
        if (resetAtHour < 0 || resetAtHour > 23) {
            throw new IllegalArgumentException("jclaw.session.reset-at-hour muss zwischen 0 und 23 liegen.");
        }
        if (resetIdleMinutes < 0) {
            throw new IllegalArgumentException("jclaw.session.reset-idle-minutes muss positiv sein.");
        }
    }
}
