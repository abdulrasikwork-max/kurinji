package com.safesphere.backend.moderation.assessment;

import com.safesphere.backend.moderation.assessment.DecisionPolicy.Action;
import com.safesphere.backend.moderation.assessment.DecisionPolicy.Rule;
import com.safesphere.backend.moderation.entity.ModerationEnums.TargetKind;
import com.safesphere.backend.social.entity.PostEnums.ModerationReason;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Phase 13E (V24): the automatic check of one post or perspective. One row per target, replaced when its words change.
 * NO TEXT: codes, severities, code-point offsets and versions only, and never a self-harm signal (V24 CHECK). Deleted
 * with its content (cascade), else after app.moderation.ai.retention unless the content has an open case or appeal.
 */
@Entity
@Table(name = "content_assessments")
public class ContentAssessment {

    public enum Status { PENDING, DONE, UNAVAILABLE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, updatable = false, length = 36, unique = true)
    private String publicId;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_kind", nullable = false, updatable = false, length = 7)
    private TargetKind targetKind;

    @Column(name = "post_id", updatable = false, unique = true)
    private Long postId;

    @Column(name = "comment_id", updatable = false, unique = true)
    private Long commentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 11)
    private Status status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at")
    private LocalDateTime nextAttemptAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 17)
    private Action action;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 18)
    private Rule rule;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private ModerationReason reason;

    @Column(length = 400)
    private String evidence;

    @Column(length = 200)
    private String spans;

    @Column(name = "strong_language", nullable = false)
    private boolean strongLanguage;

    @Column(name = "normaliser_version", length = 16)
    private String normaliserVersion;

    @Column(name = "lexicon_version", length = 16)
    private String lexiconVersion;

    @Column(name = "model_version", length = 16)
    private String modelVersion;

    @Column(name = "policy_version", nullable = false, length = 16)
    private String policyVersion;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "checked_at")
    private LocalDateTime checkedAt;

    /** Required by JPA. */
    protected ContentAssessment() {
    }

    private ContentAssessment(TargetKind kind, Long postId, Long commentId) {
        this.publicId = UUID.randomUUID().toString();
        this.targetKind = kind;
        this.postId = postId;
        this.commentId = commentId;
    }

    public static ContentAssessment ofPost(long postId) {
        return new ContentAssessment(TargetKind.POST, postId, null);
    }

    public static ContentAssessment ofComment(long commentId) {
        return new ContentAssessment(TargetKind.COMMENT, null, commentId);
    }

    /** A finished check (the service answered). */
    public void done(DecisionPolicy.Decision decision, String evidence, String spans, boolean strongLanguage,
                     Evidence.Answer answer, LocalDateTime now) {
        boolean wasPending = status == Status.PENDING && createdAt != null;
        this.status = Status.DONE;
        this.attempts = 0;
        this.nextAttemptAt = null;
        set(decision, evidence, spans, strongLanguage);
        this.normaliserVersion = answer.normaliserVersion();
        this.lexiconVersion = answer.lexiconVersion();
        this.modelVersion = answer.modelVersion();
        this.createdAt = wasPending ? createdAt : micros(now);   // retention counts from the first try of THIS text
        this.checkedAt = micros(now);
    }

    /** The service could not answer: try again at [next] (a first failure sets the creation time). */
    public void pending(DecisionPolicy.Decision decision, int attempts, LocalDateTime next, LocalDateTime now) {
        this.status = Status.PENDING;
        this.attempts = Math.min(attempts, 100);
        this.nextAttemptAt = micros(next);
        set(decision, null, null, false);
        this.normaliserVersion = null;
        this.lexiconVersion = null;
        this.modelVersion = null;
        this.createdAt = micros(now);
        this.checkedAt = null;
    }

    /** Pending again after an edit or a failed retry, keeping when it first went pending. */
    public void retryLater(int attempts, LocalDateTime next) {
        this.attempts = Math.min(attempts, 100);
        this.nextAttemptAt = micros(next);
    }

    /** A new account's content that waited for an unavailable check was published (it is still checked later). */
    public void released() {
        this.action = Action.PUBLISH;
        this.reason = null;
    }

    /** Retried for long enough: given up (the content stays as it is: published, or released after its wait). */
    public void giveUp() {
        this.status = Status.UNAVAILABLE;
        this.nextAttemptAt = null;
    }

    private void set(DecisionPolicy.Decision decision, String evidence, String spans, boolean strongLanguage) {
        this.action = decision.action();
        this.rule = decision.rule();
        this.reason = decision.reason();
        this.evidence = evidence;
        this.spans = spans;
        this.strongLanguage = strongLanguage;
        this.policyVersion = DecisionPolicy.VERSION;
    }

    private static LocalDateTime micros(LocalDateTime time) {
        return time.truncatedTo(ChronoUnit.MICROS);
    }

    public Long getId() {
        return id;
    }

    public String getPublicId() {
        return publicId;
    }

    public TargetKind getTargetKind() {
        return targetKind;
    }

    public Long getPostId() {
        return postId;
    }

    public Long getCommentId() {
        return commentId;
    }

    public Status getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public LocalDateTime getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Action getAction() {
        return action;
    }

    public Rule getRule() {
        return rule;
    }

    public ModerationReason getReason() {
        return reason;
    }

    public String getEvidence() {
        return evidence;
    }

    public String getSpans() {
        return spans;
    }

    public boolean isStrongLanguage() {
        return strongLanguage;
    }

    public String getNormaliserVersion() {
        return normaliserVersion;
    }

    public String getLexiconVersion() {
        return lexiconVersion;
    }

    public String getModelVersion() {
        return modelVersion;
    }

    public String getPolicyVersion() {
        return policyVersion;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getCheckedAt() {
        return checkedAt;
    }
}
