package com.safesphere.backend.social.entity;

import com.safesphere.backend.social.entity.PostEnums.Audience;
import com.safesphere.backend.social.entity.PostEnums.City;
import com.safesphere.backend.social.entity.PostEnums.CommentPolicy;
import com.safesphere.backend.social.entity.PostEnums.Daypart;
import com.safesphere.backend.social.entity.PostEnums.ModerationReason;
import com.safesphere.backend.social.entity.PostEnums.ModerationSource;
import com.safesphere.backend.social.entity.PostEnums.ModerationState;
import com.safesphere.backend.social.entity.PostEnums.PostType;
import com.safesphere.backend.social.entity.PostEnums.Precision;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Phase 13B (V21): an Experience or an Ask. Every field was checked by PostPolicy before it reaches here. Exact times are
 * stored for ordering; others only ever receive bands (TimeBands). [visibleFrom] is when others may see it (Q1).
 */
@Entity
@Table(name = "posts", uniqueConstraints = @UniqueConstraint(name = "uk_posts_public_id", columnNames = "public_id"))
public class Post {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, updatable = false, length = 36)
    private String publicId;

    @Column(name = "author_id", nullable = false, updatable = false)
    private Long authorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "post_type", nullable = false, updatable = false, length = 10)
    private PostType type;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    @Column(length = 300)
    private String tip;

    @Column(length = 20)
    private String category;

    @Column(name = "place_id")
    private Long placeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "location_precision", nullable = false, length = 5)
    private Precision precision;

    @Column(name = "area_cell", length = 6)
    private String areaCell;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private City city;

    @Column(name = "experienced_month")
    private LocalDate experiencedMonth;

    @Enumerated(EnumType.STRING)
    @Column(length = 9)
    private Daypart daypart;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 11)
    private Audience audience;

    @Enumerated(EnumType.STRING)
    @Column(name = "comment_policy", nullable = false, length = 11)
    private CommentPolicy commentPolicy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "visible_from", nullable = false, updatable = false)
    private LocalDateTime visibleFrom;

    @Column(name = "edited_at")
    private LocalDateTime editedAt;

    // Phase 13D (V23): moderation. Written by the moderation package; social only reads them (the one Visibility rule).
    @Enumerated(EnumType.STRING)
    @Column(name = "moderation", nullable = false, length = 9)
    private ModerationState moderation = ModerationState.NONE;

    @Enumerated(EnumType.STRING)
    @Column(name = "moderation_reason", length = 20)
    private ModerationReason moderationReason;

    @Column(name = "moderation_decision", length = 36)
    private String moderationDecision;

    // Phase 13E (V24): who set the state, the flagged phrases of an automatic state, and strong language (anyone sees it).
    @Enumerated(EnumType.STRING)
    @Column(name = "moderation_source", length = 9)
    private ModerationSource moderationSource;

    @Column(name = "moderation_spans", length = 200)
    private String moderationSpans;

    @Column(name = "strong_language", nullable = false)
    private boolean strongLanguage;

    /** A case about this post is open: an edit keeps the earlier text for the moderator (Q7). Never shown. */
    @Column(name = "in_review", nullable = false)
    private boolean inReview;

    /** Q6: perspectives from people not connected with the author wait for the author's approval. */
    @Column(name = "approve_first", nullable = false)
    private boolean approveFirst;

    /** Required by JPA. */
    protected Post() {
    }

    public Post(long authorId, PostType type, LocalDateTime createdAt, LocalDateTime visibleFrom) {
        this.publicId = UUID.randomUUID().toString();
        this.authorId = authorId;
        this.type = type;
        this.createdAt = createdAt.truncatedTo(ChronoUnit.MICROS);
        this.visibleFrom = visibleFrom.truncatedTo(ChronoUnit.MICROS);
    }

    /** The text and "when" fields (checked by PostPolicy). */
    public void setContent(String body, String tip, String category, LocalDate experiencedMonth, Daypart daypart) {
        this.body = body;
        this.tip = tip;
        this.category = category;
        this.experiencedMonth = experiencedMonth;
        this.daypart = daypart;
    }

    /** Where (checked by PostPolicy): a place for EXACT/AREA, the place's area for AREA, a city for CITY. */
    public void setLocation(Precision precision, Long placeId, String areaCell, City city) {
        this.precision = precision;
        this.placeId = placeId;
        this.areaCell = areaCell;
        this.city = city;
    }

    /** Who may see and comment (ONLY_ME forces NOBODY; checked by PostPolicy). */
    public void setSharing(Audience audience, CommentPolicy commentPolicy) {
        this.audience = audience;
        this.commentPolicy = commentPolicy;
    }

    public void markEdited(LocalDateTime now) {
        this.editedAt = now.truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * Phase 13D: a moderation state. [reason] and [decision] (the audit row's public id) are both null only for a
     * collapse by independent reports and for NONE (V23 ck_posts_moderation_state).
     */
    public void moderate(ModerationState state, ModerationReason reason, String decision) {
        this.moderation = state;
        this.moderationReason = state == ModerationState.NONE ? null : reason;
        this.moderationDecision = state == ModerationState.NONE ? null : decision;
        this.moderationSource = ModerationSource.of(state, decision);
        this.moderationSpans = null;
    }

    /**
     * Phase 13E: a state set by the automatic check (COLLAPSED or HELD with a reason, or NONE when it found nothing).
     * [spans] = the flagged phrases as code-point offsets "start-end,…" (null when none). Written by the moderation
     * package only, and only over NONE or an earlier SYSTEM state (never over reports or a moderator).
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

    public ModerationSource getModerationSource() {
        return moderationSource;
    }

    public String getModerationSpans() {
        return moderationSpans;
    }

    public boolean isStrongLanguage() {
        return strongLanguage;
    }

    public void setInReview(boolean inReview) {
        this.inReview = inReview;
    }

    public void setApproveFirst(boolean approveFirst) {
        this.approveFirst = approveFirst;
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

    public boolean isInReview() {
        return inReview;
    }

    public boolean isApproveFirst() {
        return approveFirst;
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

    public PostType getType() {
        return type;
    }

    public String getBody() {
        return body;
    }

    public String getTip() {
        return tip;
    }

    public String getCategory() {
        return category;
    }

    public Long getPlaceId() {
        return placeId;
    }

    public Precision getPrecision() {
        return precision;
    }

    public String getAreaCell() {
        return areaCell;
    }

    public City getCity() {
        return city;
    }

    public LocalDate getExperiencedMonth() {
        return experiencedMonth;
    }

    public Daypart getDaypart() {
        return daypart;
    }

    public Audience getAudience() {
        return audience;
    }

    public CommentPolicy getCommentPolicy() {
        return commentPolicy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getVisibleFrom() {
        return visibleFrom;
    }

    public LocalDateTime getEditedAt() {
        return editedAt;
    }
}
