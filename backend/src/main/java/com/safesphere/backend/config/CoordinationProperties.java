package com.safesphere.backend.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The app.moderation.coordination.* settings (Phase 9C, coord-1). Every value has a safe default; invalid values stop
 * the server at start-up, like the other app.* settings.
 *
 * @param mode           OFF = Phase 7 behaviour exactly; SHADOW = the rules run and only a count is logged (the
 *                       default, so moderator workload can be seen before anything changes); ACTIVE = a review case is
 *                       opened per new group, within the budget
 * @param interval       the check runs at most this often (it reads every visible signal)
 * @param maxCasesPerDay ACTIVE only: no new coordination case while this many COORDINATION cases (any rule) were opened
 *                       in the last 24 h, so a flood of groups cannot bury the moderators' queue
 */
@ConfigurationProperties(prefix = "app.moderation.coordination")
public record CoordinationProperties(Mode mode, Duration interval, Integer maxCasesPerDay) {

    public enum Mode { OFF, SHADOW, ACTIVE }

    public CoordinationProperties {
        if (mode == null) mode = Mode.SHADOW;
        if (interval == null) interval = Duration.ofHours(1);
        if (maxCasesPerDay == null) maxCasesPerDay = 5;
        if (interval.isNegative() || maxCasesPerDay < 1 || maxCasesPerDay > 100) {
            throw new IllegalStateException("app.moderation.coordination.* values are invalid");
        }
    }
}
