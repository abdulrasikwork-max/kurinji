package com.safesphere.backend.social.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * Phase 17E (D-3, V28): a post one person saved for later (a private bookmark; the 13C Cocoon items moved here).
 * Returned only to the person who saved it; nobody, not even the author, learns that or how often a post was saved.
 */
@Entity
@Table(name = "saved_posts",
        uniqueConstraints = @UniqueConstraint(name = "uk_saved_posts_user_post", columnNames = {"user_id", "post_id"}))
public class SavedPost {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "post_id", nullable = false, updatable = false)
    private Long postId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** Required by JPA. */
    protected SavedPost() {
    }

    public SavedPost(long userId, long postId, LocalDateTime now) {
        this.userId = userId;
        this.postId = postId;
        this.createdAt = now.truncatedTo(ChronoUnit.MICROS);
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getPostId() {
        return postId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
