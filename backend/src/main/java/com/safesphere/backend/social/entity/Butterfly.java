package com.safesphere.backend.social.entity;

import com.safesphere.backend.social.entity.PostEnums.ButterflyState;
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

/**
 * Phase 13C (V22): a butterfly = a Cocoon item (C1). "I want to experience this" on someone's Experience; later "Been".
 * Returned only to the person it belongs to; the post's author only ever learns a thresholded number (ButterflyTally).
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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 4)
    private ButterflyState state;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "been_at")
    private LocalDateTime beenAt;

    /** Required by JPA. */
    protected Butterfly() {
    }

    public Butterfly(long userId, long postId, LocalDateTime now) {
        this.userId = userId;
        this.postId = postId;
        this.state = ButterflyState.WANT;
        this.createdAt = now.truncatedTo(ChronoUnit.MICROS);
    }

    /** WANT clears the Been time; BEEN sets it once (marking Been again keeps the first time). */
    public void moveTo(ButterflyState next, LocalDateTime now) {
        if (next == ButterflyState.BEEN && state != ButterflyState.BEEN) {
            LocalDateTime at = now.truncatedTo(ChronoUnit.MICROS);
            beenAt = at.isBefore(createdAt) ? createdAt : at;
        } else if (next == ButterflyState.WANT) {
            beenAt = null;
        }
        state = next;
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

    public ButterflyState getState() {
        return state;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getBeenAt() {
        return beenAt;
    }
}
