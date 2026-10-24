package com.safesphere.backend.config;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The app.social.* settings (Phase 13A: profiles and connections; Phase 13B: places, posts, photos, comments). Every
 * value has a safe default. The limits are kept in memory per person (AttemptLimiter), like every other limit in this
 * backend.
 *
 * @param lookupsPerHour       profile look-ups (by handle or id) per person per hour (stops scraping the member list)
 * @param handleChangeCooldown a handle can change at most once in this period
 * @param handleReservation    a handle someone left stays reserved for them this long
 * @param requestsPerDay       connection requests a person may send per day
 * @param maxPendingOutgoing   requests a person may have waiting at once
 * @param maxConnections       connections one person may have
 * @param invitesPerDay        invite codes a person may create per day
 * @param maxActiveInvites     unused, unexpired invite codes a person may hold at once
 * @param inviteLifetime       how long an invite code works
 * @param redeemsPerHour       invite redemption attempts per person per hour (codes are 128-bit; this stops noise)
 * @param maxTrackedKeys       most people the in-memory limiters remember at once (memory bound)
 * @param placesPerDay         13B: places a person may create per day
 * @param placeLookupsPerHour  13B: nearby-place look-ups per person per hour
 * @param postsPerDay          13B: posts a person may share per day
 * @param mediaPerDay          13B: photos a person may upload per day
 * @param commentsPerDay       13B: perspectives a person may write per day
 * @param exactDelayMin        13B (Q1): shortest random delay before an exact-place post is shown to others
 * @param exactDelayMax        13B (Q1): longest such delay
 * @param mediaDir             13B: folder of the photo files (never served statically)
 * @param maxMediaBytes        13B: largest photo upload accepted
 * @param unattachedMediaLifetime 13B: an uploaded photo never attached to a post is deleted after this
 * @param butterfliesPerDay   13C: butterfly changes (save, Been, remove) a person may make per day
 * @param discoverReadsPerHour 13C: Edition, connections, place and category pages a person may load per hour
 * @param placeSearchesPerHour 13C: place-name searches per person per hour
 * @param probation            13D (M16): how long a new account is on probation (its reports do not count yet, lower
 *                             limits, no links)
 * @param probationPostsPerDay 13D: posts a person on probation may share per day
 * @param probationCommentsPerDay 13D: perspectives a person on probation may write per day
 */
@ConfigurationProperties(prefix = "app.social")
public record SocialProperties(Integer lookupsPerHour, Duration handleChangeCooldown, Duration handleReservation,
                               Integer requestsPerDay, Integer maxPendingOutgoing, Integer maxConnections,
                               Integer invitesPerDay, Integer maxActiveInvites, Duration inviteLifetime,
                               Integer redeemsPerHour, Integer maxTrackedKeys,
                               Integer placesPerDay, Integer placeLookupsPerHour, Integer postsPerDay,
                               Integer mediaPerDay, Integer commentsPerDay, Duration exactDelayMin,
                               Duration exactDelayMax, Path mediaDir, Integer maxMediaBytes,
                               Duration unattachedMediaLifetime, Integer butterfliesPerDay,
                               Integer discoverReadsPerHour, Integer placeSearchesPerHour, Duration probation,
                               Integer probationPostsPerDay, Integer probationCommentsPerDay) {

    public SocialProperties {
        if (lookupsPerHour == null) lookupsPerHour = 120;
        if (handleChangeCooldown == null) handleChangeCooldown = Duration.ofDays(30);
        if (handleReservation == null) handleReservation = Duration.ofDays(30);
        if (requestsPerDay == null) requestsPerDay = 30;
        if (maxPendingOutgoing == null) maxPendingOutgoing = 50;
        if (maxConnections == null) maxConnections = 1000;
        if (invitesPerDay == null) invitesPerDay = 10;
        if (maxActiveInvites == null) maxActiveInvites = 5;
        if (inviteLifetime == null) inviteLifetime = Duration.ofDays(7);
        if (redeemsPerHour == null) redeemsPerHour = 10;
        if (maxTrackedKeys == null) maxTrackedKeys = 100_000;
        if (placesPerDay == null) placesPerDay = 10;
        if (placeLookupsPerHour == null) placeLookupsPerHour = 60;
        if (postsPerDay == null) postsPerDay = 20;
        if (mediaPerDay == null) mediaPerDay = 30;
        if (commentsPerDay == null) commentsPerDay = 100;
        if (exactDelayMin == null) exactDelayMin = Duration.ofHours(1);
        if (exactDelayMax == null) exactDelayMax = Duration.ofHours(3);
        if (mediaDir == null) mediaDir = Path.of("social-media-store");
        if (maxMediaBytes == null) maxMediaBytes = 5 * 1024 * 1024;
        if (unattachedMediaLifetime == null) unattachedMediaLifetime = Duration.ofHours(24);
        if (butterfliesPerDay == null) butterfliesPerDay = 200;
        if (discoverReadsPerHour == null) discoverReadsPerHour = 240;
        if (placeSearchesPerHour == null) placeSearchesPerHour = 120;
        if (probation == null) probation = Duration.ofHours(72);
        if (probationPostsPerDay == null) probationPostsPerDay = 3;
        if (probationCommentsPerDay == null) probationCommentsPerDay = 10;
        if (probation.isNegative() || probationPostsPerDay < 1 || probationCommentsPerDay < 1) {
            throw new IllegalStateException("app.social.* values are invalid");
        }
        if (lookupsPerHour < 1 || requestsPerDay < 1 || maxPendingOutgoing < 1 || maxConnections < 1
                || invitesPerDay < 1 || maxActiveInvites < 1 || redeemsPerHour < 1 || maxTrackedKeys < 1
                || handleChangeCooldown.isNegative() || handleReservation.isNegative()
                || inviteLifetime.compareTo(Duration.ofMinutes(1)) < 0 || inviteLifetime.compareTo(Duration.ofDays(30)) > 0
                || placesPerDay < 1 || placeLookupsPerHour < 1 || postsPerDay < 1 || mediaPerDay < 1 || commentsPerDay < 1
                || exactDelayMin.isNegative() || exactDelayMax.compareTo(exactDelayMin) < 0
                || maxMediaBytes < 1024 || maxMediaBytes > 5 * 1024 * 1024
                || unattachedMediaLifetime.compareTo(Duration.ofMinutes(1)) < 0
                || butterfliesPerDay < 1 || discoverReadsPerHour < 1 || placeSearchesPerHour < 1) {
            throw new IllegalStateException("app.social.* values are invalid");
        }
    }
}
