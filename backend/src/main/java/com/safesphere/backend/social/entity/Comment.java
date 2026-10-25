package com.safesphere.backend.social.entity;

import com.safesphere.backend.social.entity.PostEnums.CommentKind;
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
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Phase 13B (V21): a Perspective on a post. One level of replies ([parentId]). The post's author can hide it (its writer
 * still sees it, marked "Hidden by the author", Q5); on an Ask the asker can mark an ANSWER "This helped".
 */
@Entity
@Table(name = "comments", uniqueConstraints = @UniqueConstraint(name = "uk_comments_public_id", columnNames = "public_id"))
public class Comment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, updatable = false, length = 36)
    private String publicId;

    @Column(name = "post_id", nullable = false, updatable = false)
    private Long postId;

    @Column(name = "author_id", nullable = false, updatable = false)
    private Long authorId;

    @Column(name = "parent_id", updatable = false)
    private Long parentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 9)
    private CommentKind kind;

    @Column(nullable = false, updatable = false, length = 1000)
    private String body;

    @Column(name = "hidden_by_author_at")
    private LocalDateTime hiddenByAuthorAt;

    @Column(name = "helped_at")
    private LocalDateTime helpedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // Phase 13D (V23): moderation (written by the moderation package) and Q6 approval by the post's author.
    @Enumerated(EnumType.STRING)
    @Column(name = "moderation", nullable = false, length = 9)
    private ModerationState moderation = ModerationState.NONE;

    @Enumerated(EnumType.STRING)
    @Column(name = "moderation_reason", length = 20)
    private ModerationReason moderationReason;

    @Column(name = "moderation_decision", length = 36)
    private String moderationDecision;

    @Column(name = "awaiting_approval", nullable = false)
    private boolean awaitingApproval;

    // Phase 13E (V24): see Post.
    @Enumerated(EnumType.STRING)
    @Column(name = "moderation_source", length = 9)
    private ModerationSource moderationSource;

    @Column(name = "moderation_spans", length = 200)
    private String moderationSpans;

    @Column(name = "strong_language", nullable = false)
    private boolean strongLanguage;

    /** Required by JPA. */
    protected Comment() {
    }

    public Comment(long postId, long authorId, Long parentId, CommentKind kind, String body, LocalDateTime createdAt) {
        this.publicId = UUID.randomUUID().toString();
        this.postId = postId;
        this.authorId = authorId;
        this.parentId = parentId;
        this.kind = kind;
        this.body = body;
        this.createdAt = createdAt.truncatedTo(ChronoUnit.MICROS);
    }

    /** Phase 13D: see Post.moderate. */
    public void moderate(ModerationState state, ModerationReason reason, String decision) {
        this.moderation = state;
        this.moderationReason = state == ModerationState.NONE ? null : reason;
        this.moderationDecision = state == ModerationState.NONE ? null : decision;
        this.moderationSource = ModerationSource.of(state, decision);
        this.moderationSpans = null;
    }

    /** Phase 13E: see Post.moderateBySystem. */
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

    public void setAwaitingApproval(boolean awaitingApproval) {
        this.awaitingApproval = awaitingApproval;
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

    public boolean isAwaitingApproval() {
        return awaitingApproval;
    }

    public void hide(LocalDateTime now) {
        if (hiddenByAuthorAt == null) hiddenByAuthorAt = now.truncatedTo(ChronoUnit.MICROS);
    }

    public void unhide() {
        hiddenByAuthorAt = null;
    }

    public void markHelped(LocalDateTime now) {
        if (helpedAt == null) helpedAt = now.truncatedTo(ChronoUnit.MICROS);
    }

    public void unmarkHelped() {
        helpedAt = null;
    }

    public boolean isHidden() {
        return hiddenByAuthorAt != null;
    }

    public boolean isHelped() {
        return helpedAt != null;
    }

    public Long getId() {
        return id;
    }

    public String getPublicId() {
        return publicId;
    }

    public Long getPostId() {
        return postId;
    }

    public Long getAuthorId() {
        return authorId;
    }

    public Long getParentId() {
        return parentId;
    }

    public CommentKind getKind() {
        return kind;
    }

    public String getBody() {
        return body;
    }

    public LocalDateTime getHiddenByAuthorAt() {
        return hiddenByAuthorAt;
    }

    public LocalDateTime getHelpedAt() {
        return helpedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
