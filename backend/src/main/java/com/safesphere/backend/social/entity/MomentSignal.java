package com.safesphere.backend.social.entity;

import com.safesphere.backend.social.entity.ResponseEnums.SignalKind;
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
 * Phase N5 (V36): one person's Same or Say more on one Say (N-88, N-89). A row exists while the signal is on; taking it
 * back deletes it. Nothing is counted in the database: the author gets names, others a band (ResponsePolicy).
 */
@Entity
@Table(name = "moment_signals",
        uniqueConstraints = @UniqueConstraint(name = "uk_moment_signals", columnNames = {"moment_id", "user_id", "kind"}))
public class MomentSignal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "moment_id", nullable = false, updatable = false)
    private Long momentId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 8)
    private SignalKind kind;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** Required by JPA. */
    protected MomentSignal() {
    }

    public MomentSignal(long momentId, long userId, SignalKind kind, LocalDateTime now) {
        this.momentId = momentId;
        this.userId = userId;
        this.kind = kind;
        this.createdAt = now.truncatedTo(ChronoUnit.MICROS);
    }

    public Long getId() {
        return id;
    }

    public Long getMomentId() {
        return momentId;
    }

    public Long getUserId() {
        return userId;
    }

    public SignalKind getKind() {
        return kind;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
