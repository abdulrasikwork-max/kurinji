package com.safesphere.backend.moderation.entity;

import com.safesphere.backend.social.entity.ContentModeration.ModerationReason;
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

    @Column(name = "moment_id", updatable = false)
    private Long momentId;

    @Column(name = "message_id", updatable = false)
    private Long messageId;

    @Column(name = "person_id", updatable = false)
    private Long personId;

    @Enumerated(EnumType.STRING)
    // Updatable ONLY through [becomeAboutMe] (N4-C): a report is otherwise never changed.
    @Column(nullable = false, length = 20)
    private ModerationReason reason;

    @Column(length = 200)
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
        this.momentId = target.getMomentId();
        this.personId = target.getPersonId();
        this.messageId = target.getMessageId();
        this.reason = reason;
        this.note = note;
        this.createdAt = now.truncatedTo(ChronoUnit.MICROS);
        this.countsFrom = countsFrom.truncatedTo(ChronoUnit.MICROS);
    }

    public void resolve() {
        this.resolved = true;
    }

    /**
     * N4-C (N-85): the reporter says the moment is about them. Their open report (one per person per moment,
     * `uk_content_reports_moment`) becomes their "This is about me" request: reason ABOUT_ME and no note (the
     * V35 CHECK allows no note on ABOUT_ME). False when the report is resolved or already an about-me request.
     */
    public boolean becomeAboutMe() {
        if (resolved || reason == ModerationReason.ABOUT_ME) return false;
        this.reason = ModerationReason.ABOUT_ME;
        this.note = null;
        return true;
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

    public Long getMessageId() {
        return messageId;
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
