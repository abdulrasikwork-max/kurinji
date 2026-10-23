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
 * Phase 13A (V20): [blockerId] blocked [blockedId]. Makes the two invisible to each other in both directions; private
 * to the blocker; the blocked person is never told.
 */
@Entity
@Table(name = "blocks",
        uniqueConstraints = @UniqueConstraint(name = "uk_blocks_pair", columnNames = {"blocker_id", "blocked_id"}))
public class Block {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "blocker_id", nullable = false, updatable = false)
    private Long blockerId;

    @Column(name = "blocked_id", nullable = false, updatable = false)
    private Long blockedId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** Required by JPA. */
    protected Block() {
    }

    public Block(long blockerId, long blockedId, LocalDateTime now) {
        if (blockerId == blockedId) throw new IllegalArgumentException("Nobody can block themselves");
        this.blockerId = blockerId;
        this.blockedId = blockedId;
        this.createdAt = now.truncatedTo(ChronoUnit.MICROS);
    }

    public Long getId() {
        return id;
    }

    public Long getBlockerId() {
        return blockerId;
    }

    public Long getBlockedId() {
        return blockedId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
