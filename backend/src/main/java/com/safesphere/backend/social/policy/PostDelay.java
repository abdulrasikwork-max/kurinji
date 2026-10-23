package com.safesphere.backend.social.policy;

import com.safesphere.backend.social.entity.PostEnums.Precision;
import java.time.Duration;
import java.util.random.RandomGenerator;

/**
 * Phase 13B (Q1): an EXACT-place post appears to everyone but its author after a random delay (default 1-3 hours), for
 * every audience. It turns "posted from here" into "was here earlier": the main stalking risk of experience posts. An
 * area, a city or no place reveals too little to need it. Pure (the random source is passed in).
 */
public final class PostDelay {

    private PostDelay() {
    }

    public static Duration delay(Precision precision, Duration min, Duration max, RandomGenerator random) {
        if (precision != Precision.EXACT) return Duration.ZERO;
        long spanSeconds = max.minus(min).toSeconds();
        long extra = spanSeconds <= 0 ? 0 : random.nextLong(spanSeconds + 1);
        return min.plusSeconds(extra);
    }
}
