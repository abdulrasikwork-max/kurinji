package com.safesphere.backend.community.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * Private standing of one contributor (Phase 7, V13): COUNTERS only, no places and no times of contributions, halved
 * every 90 days (TrustService.decay). Never returned by any endpoint; TrustPolicy turns it into a tier that only
 * changes how much the person's contributions weigh. [restrictedUntil] (UTC) is set only by a moderator.
 */
@Entity
@Table(name = "contributor_standing")
public class ContributorStanding {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false)
    private int corroborated;

    @Column(nullable = false)
    private int refuted;

    @Column(nullable = false)
    private int strikes;

    @Column(name = "flags_upheld", nullable = false)
    private int flagsUpheld;

    @Column(name = "flags_rejected", nullable = false)
    private int flagsRejected;

    @Column(name = "restricted_until")
    private LocalDateTime restrictedUntil;

    @Column(name = "decayed_at", nullable = false)
    private LocalDateTime decayedAt;

    /** Required by JPA. */
    protected ContributorStanding() {
    }

    public ContributorStanding(long userId, LocalDateTime now) {
        this.userId = userId;
        this.decayedAt = now;
    }

    public Long getUserId() {
        return userId;
    }

    public int getCorroborated() {
        return corroborated;
    }

    public int getRefuted() {
        return refuted;
    }

    public int getStrikes() {
        return strikes;
    }

    public int getFlagsUpheld() {
        return flagsUpheld;
    }

    public int getFlagsRejected() {
        return flagsRejected;
    }

    public LocalDateTime getRestrictedUntil() {
        return restrictedUntil;
    }

    public LocalDateTime getDecayedAt() {
        return decayedAt;
    }

    public boolean isRestricted(LocalDateTime now) {
        return restrictedUntil != null && restrictedUntil.isAfter(now);
    }

    public void addCorroborated() {
        corroborated++;
    }

    public void addRefuted() {
        refuted++;
    }

    public void addStrike() {
        strikes++;
    }

    public void removeStrike() {
        if (strikes > 0) strikes--;
    }

    public void addFlagUpheld() {
        flagsUpheld++;
    }

    public void addFlagRejected() {
        flagsRejected++;
    }

    public void restrictUntil(LocalDateTime until) {
        this.restrictedUntil = until;
    }

    public void liftRestriction() {
        this.restrictedUntil = null;
    }

    /** Halves every counter (integer division) — a 90-day half-life, so old behaviour fades both ways. */
    public void decay(LocalDateTime now) {
        corroborated /= 2;
        refuted /= 2;
        strikes /= 2;
        flagsUpheld /= 2;
        flagsRejected /= 2;
        decayedAt = now;
    }
}
