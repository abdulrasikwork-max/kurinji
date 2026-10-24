package com.safesphere.backend.moderation.entity;

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
 * Phase 13D (V23): one person's report about a post, a perspective or a person, attached to the case that was open when
 * it arrived. Never returned to anyone but its reporter (their own list) and, as a case pseudonym, to moderators.
 * [countsFrom] = when it starts to count (the end of a new account's probation, M5); [resolved] = a moderator decided.
 */
@Entity
@Table(name = "content_reports")
public class ContentReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, updatable = false, length = 36, unique = true)
    private String publicId;

    @Column(name = "case_id", nullable = false, updatable = false)
    private Long caseId;

    @Column(name = "reporter_id", nullable = false, updatable = false)
    private Long reporterId;

    @Column(name = "post_id", updatable = false)
    private Long postId;

    @Column(name = "comment_id", updatable = false)
    private Long commentId;

    @Column(name = "person_id", updatable = false)
    private Long personId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private ModerationReason reason;

    @Column(updatable = false, length = 200)
    private String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "counts_from", nullable = false, updatable = false)
    private LocalDateTime countsFrom;

    @Column(nullable = false)
    private boolean resolved;

    /** Required by JPA. */
    protected ContentReport() {
    }

    public ContentReport(ContentCase target, long reporterId, ModerationReason reason, String note, LocalDateTime now,
                         LocalDateTime countsFrom) {
        this.publicId = UUID.randomUUID().toString();
        this.caseId = target.getId();
        this.reporterId = reporterId;
        this.postId = target.getPostId();
        this.commentId = target.getCommentId();
        this.personId = target.getPersonId();
        this.reason = reason;
        this.note = note;
        this.createdAt = now.truncatedTo(ChronoUnit.MICROS);
        this.countsFrom = countsFrom.truncatedTo(ChronoUnit.MICROS);
    }

    public void resolve() {
        this.resolved = true;
    }

    public Long getId() {
        return id;
    }

    public String getPublicId() {
        return publicId;
    }

    public Long getCaseId() {
        return caseId;
    }

    public Long getReporterId() {
        return reporterId;
    }

    public ModerationReason getReason() {
        return reason;
    }

    public String getNote() {
        return note;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getCountsFrom() {
        return countsFrom;
    }

    public boolean isResolved() {
        return resolved;
    }
}
