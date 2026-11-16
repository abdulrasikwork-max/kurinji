package com.safesphere.backend.social.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * Phase N4 (V35, N-49): the question pinned on a community's Today for one India calendar day, written by an admin. One
 * per community per day (uk_daily_questions_day). Answers are ordinary moments that carry its id; nothing here counts
 * them. [createdBy] is for the admins' own list only and is cleared when that account is deleted.
 */
@Entity
@Table(name = "daily_questions", uniqueConstraints = {
        @UniqueConstraint(name = "uk_daily_questions_public_id", columnNames = "public_id"),
        @UniqueConstraint(name = "uk_daily_questions_day", columnNames = {"community_id", "question_day"})})
public class DailyQuestion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, updatable = false, length = 36)
    private String publicId;

    @Column(name = "community_id", nullable = false, updatable = false)
    private Long communityId;

    @Column(name = "question_day", nullable = false, updatable = false)
    private LocalDate questionDay;

    @Column(nullable = false, updatable = false, length = 140)
    private String body;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** Required by JPA. */
    protected DailyQuestion() {
    }

    public DailyQuestion(long communityId, LocalDate day, String body, long createdBy, LocalDateTime createdAt) {
        this.publicId = UUID.randomUUID().toString();
        this.communityId = communityId;
        this.questionDay = day;
        this.body = body;
        this.createdBy = createdBy;
        this.createdAt = createdAt.truncatedTo(ChronoUnit.MICROS);
    }

    public Long getId() {
        return id;
    }

    public String getPublicId() {
        return publicId;
    }

    public Long getCommunityId() {
        return communityId;
    }

    public LocalDate getQuestionDay() {
        return questionDay;
    }

    public String getBody() {
        return body;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
