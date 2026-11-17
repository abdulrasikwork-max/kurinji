package com.safesphere.backend.config;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The app.social.* settings: profiles, connections and the photo files (profile photos and the photos of moments).
 * Every value has a safe default. The limits are kept in memory per person (AttemptLimiter), like every other limit in
 * this backend. The limits of moments are in MomentProperties (app.moments.*). The settings of posts, places,
 * perspectives, Discover, video and the 72-hour account probation left with that code in Phase N4-B3.
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
 * @param mediaPerDay          profile photos a person may upload per day
 * @param mediaDir             folder of the photo files (never served statically)
 * @param maxMediaBytes        largest photo upload accepted (profile photo, moment photo)
 * @param unattachedMediaLifetime a moment photo that was uploaded and never attached is deleted after this
 */
@ConfigurationProperties(prefix = "app.social")
public record SocialProperties(Integer lookupsPerHour, Duration handleChangeCooldown, Duration handleReservation,
                               Integer requestsPerDay, Integer maxPendingOutgoing, Integer maxConnections,
                               Integer invitesPerDay, Integer maxActiveInvites, Duration inviteLifetime,
                               Integer redeemsPerHour, Integer maxTrackedKeys, Integer mediaPerDay, Path mediaDir,
                               Integer maxMediaBytes, Duration unattachedMediaLifetime) {

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
        if (mediaPerDay == null) mediaPerDay = 30;
        if (mediaDir == null) mediaDir = Path.of("social-media-store");
        if (maxMediaBytes == null) maxMediaBytes = 5 * 1024 * 1024;
        if (unattachedMediaLifetime == null) unattachedMediaLifetime = Duration.ofHours(24);
        if (lookupsPerHour < 1 || requestsPerDay < 1 || maxPendingOutgoing < 1 || maxConnections < 1
                || invitesPerDay < 1 || maxActiveInvites < 1 || redeemsPerHour < 1 || maxTrackedKeys < 1
                || handleChangeCooldown.isNegative() || handleReservation.isNegative()
                || inviteLifetime.compareTo(Duration.ofMinutes(1)) < 0 || inviteLifetime.compareTo(Duration.ofDays(30)) > 0
                || mediaPerDay < 1 || maxMediaBytes < 1024 || maxMediaBytes > 5 * 1024 * 1024
                || unattachedMediaLifetime.compareTo(Duration.ofMinutes(1)) < 0) {
            throw new IllegalStateException("app.social.* values are invalid");
        }
    }
}
