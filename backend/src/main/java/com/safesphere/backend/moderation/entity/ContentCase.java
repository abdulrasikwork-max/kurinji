package com.safesphere.backend.moderation.entity;

import com.safesphere.backend.moderation.entity.ModerationEnums.CaseOpenedBy;
import com.safesphere.backend.moderation.entity.ModerationEnums.CaseStatus;
import com.safesphere.backend.moderation.entity.ModerationEnums.ContentOutcome;
import com.safesphere.backend.moderation.entity.ModerationEnums.TargetKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Phase 13D (V23): one review case about one post, perspective or person; the sibling of the Phase 7 signal case
 * (ModerationCase). At most one OPEN case per target (generated marker + unique keys in MySQL; the service checks
 * first). Deleted with its target. [version] makes a second moderator acting at the same moment fail with 409.
 */
@Entity
@Table(name = "content_cases")
public class ContentCase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, updatable = false, length = 36, unique = true)
    private String publicId;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_kind", nullable = false, updatable = false, length = 7)
    private TargetKind targetKind;

    /** Phase 13E (V24): why it was opened (reports, a hold by the automatic check, or the author asking for a review). */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 14)
    private CaseOpenedBy origin = CaseOpenedBy.REPORTS;

    @Column(name = "post_id", updatable = false)
    private Long postId;

    @Column(name = "comment_id", updatable = false)
    private Long commentId;

    @Column(name = "person_id", updatable = false)
    private Long personId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 6)
    private CaseStatus status = CaseStatus.OPEN;

    @Enumerated(EnumType.STRING)
    @Column(length = 12)
    private ContentOutcome outcome;

    @Column(name = "opened_at", nullable = false, updatable = false)
    private LocalDateTime openedAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Version
    @Column(nullable = false)
    private long version;

    /** Required by JPA. */
    protected ContentCase() {
    }

    private ContentCase(TargetKind kind, Long postId, Long commentId, Long personId, LocalDateTime now) {
        this.publicId = UUID.randomUUID().toString();
        this.targetKind = kind;
        this.postId = postId;
        this.commentId = commentId;
        this.personId = personId;
        this.openedAt = now;
    }

    public static ContentCase aboutPost(long postId, LocalDateTime now) {
        return new ContentCase(TargetKind.POST, postId, null, null, now);
    }

    public static ContentCase aboutComment(long commentId, LocalDateTime now) {
        return new ContentCase(TargetKind.COMMENT, null, commentId, null, now);
    }

    public static ContentCase aboutPerson(long personId, LocalDateTime now) {
        return new ContentCase(TargetKind.PERSON, null, null, personId, now);
    }

    /** Phase 13E: a case opened by the automatic check or by the author's request (reports may join it later). */
    public ContentCase openedBy(CaseOpenedBy origin) {
        this.origin = origin;
        return this;
    }

    public CaseOpenedBy getOrigin() {
        return origin;
    }

    public void close(ContentOutcome outcome, LocalDateTime now) {
        this.status = CaseStatus.CLOSED;
        this.outcome = outcome;
        this.closedAt = now;
    }

    public boolean isOpen() {
        return status == CaseStatus.OPEN;
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

    public Long getPersonId() {
        return personId;
    }

    public CaseStatus getStatus() {
        return status;
    }

    public ContentOutcome getOutcome() {
        return outcome;
    }

    public LocalDateTime getOpenedAt() {
        return openedAt;
    }

    public LocalDateTime getClosedAt() {
        return closedAt;
    }
}
