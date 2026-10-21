package com.safesphere.backend.evidence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/** One wrong account password given to a recovery endpoint (V11). Counted for the lockout. */
@Entity
@Table(name = "evidence_recovery_failures")
public class RecoveryFailure {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private LocalDateTime occurredAt;

    /** Required by JPA. */
    protected RecoveryFailure() {
    }

    public RecoveryFailure(long userId, LocalDateTime occurredAt) {
        this.userId = userId;
        this.occurredAt = occurredAt;
    }

    public Long getId() {
        return id;
    }
}
