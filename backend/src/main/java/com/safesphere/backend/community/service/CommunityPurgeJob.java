package com.safesphere.backend.community.service;

import com.safesphere.backend.auth.service.AuthThrottle;
import com.safesphere.backend.evaluation.service.WeeklySnapshot;
import com.safesphere.backend.intelligence.service.AreaModelShadow;
import com.safesphere.backend.intelligence.service.PatternService;
import com.safesphere.backend.intelligence.service.RegionQueryLimiter;
import com.safesphere.backend.moderation.service.ModerationService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every 15 minutes (Phase 6, extended in Phase 7): deletes expired and retired place signals (minimum retention),
 * credits independently corroborated reports, runs moderation upkeep (flags that started counting, re-reports of
 * removed signals, coordination, retention of cases and the audit log), halves standing counters older than 90 days,
 * records pattern episodes and forgets faded area patterns (Phase 8C), and forgets finished rate-limit windows. Reads never depend on it: the policies hide anything expired at once.
 * Phase 9D: it also lets lone reports join the same signal one block over, and runs the rules-2 shadow comparison
 * (counts only, at most hourly). Phase 9B: once a week it takes the evaluation snapshot (system-wide counts only).
 * It is the server's only scheduled task and it never sends alerts (no delivery channel exists).
 */
@Component
@ConditionalOnProperty(name = "app.community.purge-enabled", havingValue = "true", matchIfMissing = true)
public class CommunityPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(CommunityPurgeJob.class);

    private final CommunityService service;
    private final ModerationService moderation;
    private final TrustService trust;
    private final AuthThrottle authThrottle;
    private final Clock clock;
    private final PatternService patterns;
    private final RegionQueryLimiter regionLimiter;
    private final AreaModelShadow shadow;
    private final WeeklySnapshot snapshot;

    public CommunityPurgeJob(CommunityService service, ModerationService moderation, TrustService trust,
                             AuthThrottle authThrottle, Clock clock, PatternService patterns,
                             RegionQueryLimiter regionLimiter, AreaModelShadow shadow, WeeklySnapshot snapshot) {
        this.shadow = shadow;
        this.snapshot = snapshot;
        this.patterns = patterns;
        this.regionLimiter = regionLimiter;
        this.service = service;
        this.moderation = moderation;
        this.trust = trust;
        this.authThrottle = authThrottle;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.community.purge-interval:PT15M}", initialDelayString = "PT1M")
    public void purge() {
        CommunityService.Cleanup cleanup = service.purge();
        int removed = cleanup.removed();
        int moderated = moderation.maintain();
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        shadow.run(now); // Phase 9D: rules-2 in SHADOW only counts (its own log line); it changes nothing
        // Phase 9B: once a week, system-wide counts of what anyone could see; also the counters' retention. Never throws.
        int countersDeleted = snapshot.run(now).deleted();
        int decayed = trust.decay(now);
        int forgotten = patterns.forgetFaded();
        regionLimiter.evictExpired();
        authThrottle.evictExpired();
        // Counts only: never cells, categories or accounts.
        if (removed > 0 || cleanup.joined() > 0 || moderated > 0 || decayed > 0 || forgotten > 0 || countersDeleted > 0) {
            log.info("Community clean-up: {} rows removed, {} lone report(s) joined a neighbour, {} moderation changes, "
                    + "{} standings decayed, {} area patterns forgotten, {} old evaluation counters deleted", removed,
                    cleanup.joined(), moderated, decayed, forgotten, countersDeleted);
        }
    }
}
