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
 * Phase 13A (V20): [muterId] stopped seeing [mutedId]. One-sided and private: the muted person is not affected and
 * never told. It takes effect on content from 13B on.
 */
@Entity
@Table(name = "mutes",
        uniqueConstraints = @UniqueConstraint(name = "uk_mutes_pair", columnNames = {"muter_id", "muted_id"}))
public class Mute {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "muter_id", nullable = false, updatable = false)
    private Long muterId;

    @Column(name = "muted_id", nullable = false, updatable = false)
    private Long mutedId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** Required by JPA. */
    protected Mute() {
    }

    public Mute(long muterId, long mutedId, LocalDateTime now) {
        if (muterId == mutedId) throw new IllegalArgumentException("Nobody can mute themselves");
        this.muterId = muterId;
        this.mutedId = mutedId;
        this.createdAt = now.truncatedTo(ChronoUnit.MICROS);
    }

    public Long getId() {
        return id;
    }

    public Long getMuterId() {
        return muterId;
    }

    public Long getMutedId() {
        return mutedId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
