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
 * Phase 17E (D-3, V28): a butterfly = one person's reaction to someone else's Experience ("this inspires me"). Was the
 * Cocoon item in 13C; saving is now {@link SavedPost}. The row is returned only to the person who reacted; the post's
 * author only ever learns a thresholded number (ButterflyTally). Nobody else learns anything.
 */
@Entity
@Table(name = "butterflies",
        uniqueConstraints = @UniqueConstraint(name = "uk_butterflies_user_post", columnNames = {"user_id", "post_id"}))
public class Butterfly {

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
    protected Butterfly() {
    }

    public Butterfly(long userId, long postId, LocalDateTime now) {
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
