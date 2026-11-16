package com.safesphere.backend.social.entity;

import com.safesphere.backend.social.entity.MomentEnums.Audience;
import com.safesphere.backend.social.entity.MomentEnums.Kind;
import com.safesphere.backend.social.entity.PostEnums.ModerationReason;
import com.safesphere.backend.social.entity.PostEnums.ModerationSource;
import com.safesphere.backend.social.entity.PostEnums.ModerationState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Phase N4 (V35): a moment, the one thing people share in IRL: a Say or a Who's in? Every field was checked by
 * MomentPolicy before it reaches here; a moment is never edited (N-54).
 *
 * Life (N-47): [visibleAt] is when others could first see it and [expiresAt] is 24 hours later. Both stay null while
 * the moment has never been visible (held from the start), so the 24 hours begin when it is released ([publish]), never
 * while it waits. After [expiresAt] it leaves Today; it stays on the profile only while [keptAt] is set (N-48).
 *
 * Nothing here is a place: [planWhere] is the author's own words (N-50). Exact times are stored for ordering; others
 * only ever receive rough times (MomentTimes).
 */
@Entity
@Table(name = "moments",
        uniqueConstraints = @UniqueConstraint(name = "uk_moments_public_id", columnNames = "public_id"),
        indexes = {@Index(name = "idx_moments_today", columnList = "community_id, visible_at, id"),
                @Index(name = "idx_moments_author", columnList = "author_id, created_at"),
                @Index(name = "idx_moments_expires", columnList = "expires_at")})
public class Moment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, updatable = false, length = 36)
    private String publicId;

    @Column(name = "author_id", nullable = false, updatable = false)
    private Long authorId;

    /** The author's community when the moment was shared (Today is one community's wall). */
    @Column(name = "community_id", nullable = false, updatable = false)
    private Long communityId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 8)
    private Kind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 9)
    private Audience audience;

    /** Up to 500 characters; null only next to a photo. */
    @Column(updatable = false, length = 500)
    private String body;

    /** WHOS_IN only: when, within 24 hours of [createdAt]. Shown exactly (the author chose to say it). */
    @Column(name = "plan_at", updatable = false)
    private LocalDateTime planAt;

    /** WHOS_IN only: where, in the author's own words. Plain text; never a place record, never coordinates. */
    @Column(name = "plan_where", updatable = false, length = 60)
    private String planWhere;

    /** The daily question this moment answers, if any (cleared when an admin deletes the question). */
    @Column(name = "question_id")
    private Long questionId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "visible_at")
    private LocalDateTime visibleAt;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "kept_at")
    private LocalDateTime keptAt;

    // Moderation: the same columns and meaning as on posts (V23, V24). Written by the moderation package only; social
    // reads them through the one Visibility rule.
    @Enumerated(EnumType.STRING)
    @Column(name = "moderation", nullable = false, length = 9)
    private ModerationState moderation = ModerationState.NONE;

    @Enumerated(EnumType.STRING)
    @Column(name = "moderation_reason", length = 20)
    private ModerationReason moderationReason;

    @Column(name = "moderation_decision", length = 36)
    private String moderationDecision;

    @Enumerated(EnumType.STRING)
    @Column(name = "moderation_source", length = 9)
    private ModerationSource moderationSource;

    @Column(name = "moderation_spans", length = 200)
    private String moderationSpans;

    @Column(name = "strong_language", nullable = false)
    private boolean strongLanguage;

    /** An open moderation case needs this moment: it is not purged meanwhile (N-47). Never shown. */
    @Column(name = "in_review", nullable = false)
    private boolean inReview;

    /** Required by JPA. */
    protected Moment() {
    }

    /** A new moment that nobody else can see yet: [publish] starts its 24 hours. */
    public Moment(long authorId, long communityId, Kind kind, Audience audience, String body, LocalDateTime createdAt) {
        this.publicId = UUID.randomUUID().toString();
        this.authorId = authorId;
        this.communityId = communityId;
        this.kind = kind;
        this.audience = audience;
        this.body = body;
        this.createdAt = createdAt.truncatedTo(ChronoUnit.MICROS);
    }

    /** The plan of a Who's in? (checked by MomentPolicy: the kind, the 24-hour window, the length of [where]). */
    public void plan(LocalDateTime at, String where) {
        if (kind != Kind.WHOS_IN && (at != null || where != null)) {
            throw new IllegalStateException("Only a Who's in? has a plan");
        }
        this.planAt = at == null ? null : at.truncatedTo(ChronoUnit.MICROS);
        this.planWhere = where;
    }

    public void answer(Long questionId) {
        this.questionId = questionId;
    }

    /**
     * Others can see it from [now]: its [life] starts here. Only the FIRST time counts (N-47: 24 hours from when others
     * can first see it); a moment hidden and shown again later keeps the end it already had.
     */
    public void publish(LocalDateTime now, Duration life) {
        if (visibleAt != null) return;
        LocalDateTime at = now.truncatedTo(ChronoUnit.MICROS);
        this.visibleAt = at.isBefore(createdAt) ? createdAt : at;
        this.expiresAt = this.visibleAt.plus(life);
    }

    /** Others could see it by [now] (it was published and that time has come). */
    public boolean hasStarted(LocalDateTime now) {
        return visibleAt != null && !visibleAt.isAfter(now);
    }

    /** Inside its 24 hours at [now]: started and not yet expired. */
    public boolean isLive(LocalDateTime now) {
        return hasStarted(now) && expiresAt.isAfter(now);
    }

    /** Its 24 hours are over at [now] (never true for a moment that was never visible). */
    public boolean hasEnded(LocalDateTime now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }

    public void keep(LocalDateTime now) {
        if (keptAt != null) return;
        LocalDateTime at = now.truncatedTo(ChronoUnit.MICROS);
        this.keptAt = visibleAt != null && at.isBefore(visibleAt) ? visibleAt : at;
    }

    public void unkeep() {
        this.keptAt = null;
    }

    public boolean isKept() {
        return keptAt != null;
    }

    /**
     * A moderation state set by reports (no decision) or by a moderator ([decision] = the audit row's public id). Written
     * by the moderation package only (V35 ck_moments_moderation_state).
     */
    public void moderate(ModerationState state, ModerationReason reason, String decision) {
        this.moderation = state;
        this.moderationReason = state == ModerationState.NONE ? null : reason;
        this.moderationDecision = state == ModerationState.NONE ? null : decision;
        this.moderationSource = ModerationSource.of(state, decision);
        this.moderationSpans = null;
    }

    /**
     * A state set by an automatic rule (COLLAPSED or HELD with a reason, or NONE when it found nothing); [spans] = the
     * flagged phrases as code-point offsets. Never REMOVED: only a human removes.
     */
    public void moderateBySystem(ModerationState state, ModerationReason reason, String spans) {
        if (state == ModerationState.REMOVED) throw new IllegalArgumentException("the system never removes");
        this.moderation = state;
        this.moderationReason = state == ModerationState.NONE ? null : reason;
        this.moderationDecision = null;
        this.moderationSource = state == ModerationState.NONE ? null : ModerationSource.SYSTEM;
        this.moderationSpans = state == ModerationState.NONE ? null : spans;
    }

    public void setStrongLanguage(boolean strongLanguage) {
        this.strongLanguage = strongLanguage;
    }

    public void setInReview(boolean inReview) {
        this.inReview = inReview;
    }

    public Long getId() {
        return id;
    }

    public String getPublicId() {
        return publicId;
    }

    public Long getAuthorId() {
        return authorId;
    }

    public Long getCommunityId() {
        return communityId;
    }

    public Kind getKind() {
        return kind;
    }

    public Audience getAudience() {
        return audience;
    }

    public String getBody() {
        return body;
    }

    public LocalDateTime getPlanAt() {
        return planAt;
    }

    public String getPlanWhere() {
        return planWhere;
    }

    public Long getQuestionId() {
        return questionId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getVisibleAt() {
        return visibleAt;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public LocalDateTime getKeptAt() {
        return keptAt;
    }

    public ModerationState getModeration() {
        return moderation;
    }

    public ModerationReason getModerationReason() {
        return moderationReason;
    }

    public String getModerationDecision() {
        return moderationDecision;
    }

    public ModerationSource getModerationSource() {
        return moderationSource;
    }

    public String getModerationSpans() {
        return moderationSpans;
    }

    public boolean isStrongLanguage() {
        return strongLanguage;
    }

    public boolean isInReview() {
        return inReview;
    }
}
