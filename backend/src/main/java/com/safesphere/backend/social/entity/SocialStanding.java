package com.safesphere.backend.social.entity;

import com.safesphere.backend.social.entity.PostEnums.ModerationReason;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * Phase 13D (V23): a person's current pause in the social features, set only by a moderator's decision (one row per
 * person, replaced by the next decision). In force while [restrictedUntil] is in the future. Social reads it before a
 * write; SOS, Safe Walk, the Safety Circle and the vault never do (SocialIsolationTest).
 */
@Entity
@Table(name = "social_standing")
public class SocialStanding {

    /** What a pause stops. Each level includes the ones before it. */
    public enum Restriction {
        /** No new perspectives. */
        COMMENTS_PAUSED,
        /** Also no new posts and no new places. */
        POSTING_PAUSED,
        /** Also no connection requests, invite codes or profile changes. */
        SUSPENDED;

        public boolean stopsComments() {
            return true;
        }

        public boolean stopsPosting() {
            return this != COMMENTS_PAUSED;
        }

        public boolean stopsConnecting() {
            return this == SUSPENDED;
        }
    }

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private Restriction restriction;

    @Column(name = "restricted_until", nullable = false)
    private LocalDateTime restrictedUntil;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ModerationReason reason;

    @Column(nullable = false, length = 36)
    private String decision;

    @Column(name = "decided_at", nullable = false)
    private LocalDateTime decidedAt;

    /** Required by JPA. */
    protected SocialStanding() {
    }

    public SocialStanding(long userId) {
        this.userId = userId;
    }

    /** A moderator's pause (the audit row [decision]). */
    public void pause(Restriction restriction, LocalDateTime until, ModerationReason reason, String decision,
                      LocalDateTime now) {
        this.restriction = restriction;
        this.restrictedUntil = until.truncatedTo(ChronoUnit.MICROS);
        this.reason = reason;
        this.decision = decision;
        this.decidedAt = now.truncatedTo(ChronoUnit.MICROS);
    }

    public boolean inForce(LocalDateTime now) {
        return restrictedUntil != null && restrictedUntil.isAfter(now);
    }

    public Long getUserId() {
        return userId;
    }

    public Restriction getRestriction() {
        return restriction;
    }

    public LocalDateTime getRestrictedUntil() {
        return restrictedUntil;
    }

    public ModerationReason getReason() {
        return reason;
    }

    public String getDecision() {
        return decision;
    }

    public LocalDateTime getDecidedAt() {
        return decidedAt;
    }
}
