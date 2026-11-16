package com.safesphere.backend.community.entity;

import com.safesphere.backend.community.entity.CommunityEnums.EndReason;
import com.safesphere.backend.community.entity.CommunityEnums.Method;
import com.safesphere.backend.community.entity.CommunityEnums.Programme;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * Phase N3 (V34): a person's membership of a community, one per person. The roll number is only its HMAC
 * ([rollKey]); one account per roll number per community (uk_memberships_roll). Active while [endedAt] is null and
 * [endsAt] is in the future; an ended row stays (so the roll number stays taken) and is re-used if the same person is
 * verified again (for example for a PG programme after the UG one).
 */
@Entity
@Table(name = "memberships", uniqueConstraints = {
        @UniqueConstraint(name = "uk_memberships_user", columnNames = "user_id"),
        @UniqueConstraint(name = "uk_memberships_roll", columnNames = {"community_id", "roll_key"})})
public class Membership {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "community_id", nullable = false)
    private Long communityId;

    @Column(name = "roll_key", nullable = false, length = 64)
    private String rollKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Programme programme;

    @Column(name = "graduation_year", nullable = false)
    private int graduationYear;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Method method;

    /** Who approved (no cascade: a deciding account becomes a tombstone when deleted, like moderators). */
    @Column(name = "verified_by", nullable = false)
    private Long verifiedBy;

    @Column(name = "verified_at", nullable = false)
    private LocalDateTime verifiedAt;

    @Column(name = "ends_at", nullable = false)
    private LocalDateTime endsAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "end_reason", length = 10)
    private EndReason endReason;

    @Column(nullable = false)
    private boolean verifier;

    @Column(name = "verifier_since")
    private LocalDateTime verifierSince;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected Membership() {
    }

    public Membership(long userId, long communityId, String rollKey, Programme programme, int graduationYear,
                      Method method, long verifiedBy, LocalDateTime now, LocalDateTime endsAt) {
        this.userId = userId;
        verify(communityId, rollKey, programme, graduationYear, method, verifiedBy, now, endsAt);
    }

    /** A (new or renewed) verification: the row becomes active with these facts; any end and staff flag are cleared. */
    public void verify(long communityId, String rollKey, Programme programme, int graduationYear, Method method,
                       long verifiedBy, LocalDateTime now, LocalDateTime endsAt) {
        LocalDateTime at = now.truncatedTo(ChronoUnit.MICROS);
        this.communityId = communityId;
        this.rollKey = rollKey;
        this.programme = programme;
        this.graduationYear = graduationYear;
        this.method = method;
        this.verifiedBy = verifiedBy;
        this.verifiedAt = at;
        this.endsAt = endsAt.truncatedTo(ChronoUnit.MICROS);
        this.endedAt = null;
        this.endReason = null;
        this.verifier = false;
        this.verifierSince = null;
        this.updatedAt = at;
    }

    public boolean isActive(LocalDateTime now) {
        return endedAt == null && endsAt.isAfter(now);
    }

    /** Ends the membership (an admin, a ban, or the graduation job). Never moves an earlier end. */
    public void end(EndReason reason, LocalDateTime now) {
        LocalDateTime at = now.truncatedTo(ChronoUnit.MICROS);
        if (endedAt == null) {
            this.endedAt = at.isBefore(verifiedAt) ? verifiedAt : at;
            this.endReason = reason;
        } else if (reason == EndReason.BANNED) {
            this.endReason = EndReason.BANNED; // a ban after graduation still shows as a ban
        }
        this.verifier = false;
        this.verifierSince = null;
        this.updatedAt = at.isBefore(verifiedAt) ? verifiedAt : at;
    }

    public void makeVerifier(boolean on, LocalDateTime now) {
        LocalDateTime at = now.truncatedTo(ChronoUnit.MICROS);
        if (on && !verifier) {
            this.verifier = true;
            this.verifierSince = at;
        } else if (!on) {
            this.verifier = false;
            this.verifierSince = null;
        }
        this.updatedAt = at.isBefore(verifiedAt) ? verifiedAt : at;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getCommunityId() {
        return communityId;
    }

    public String getRollKey() {
        return rollKey;
    }

    public Programme getProgramme() {
        return programme;
    }

    public int getGraduationYear() {
        return graduationYear;
    }

    public Method getMethod() {
        return method;
    }

    public Long getVerifiedBy() {
        return verifiedBy;
    }

    public LocalDateTime getVerifiedAt() {
        return verifiedAt;
    }

    public LocalDateTime getEndsAt() {
        return endsAt;
    }

    public LocalDateTime getEndedAt() {
        return endedAt;
    }

    public EndReason getEndReason() {
        return endReason;
    }

    public boolean isVerifier() {
        return verifier;
    }

    public LocalDateTime getVerifierSince() {
        return verifierSince;
    }
}
