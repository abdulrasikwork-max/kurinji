package com.safesphere.backend.community.entity;

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

/**
 * One person's abuse flag on one place signal (Phase 7, V13). Never shown to anyone; moderators see counts per reason
 * only. [countsFrom] (UTC) = server time, or the end of a new account's probation. [resolved] = a moderator has
 * decided the case; the row stays so the same person cannot flag the same signal again.
 */
@Entity
@Table(name = "signal_flags",
        uniqueConstraints = @UniqueConstraint(name = "uk_signal_flags_signal_user", columnNames = {"signal_id", "user_id"}))
public class SignalFlag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "signal_id", nullable = false, updatable = false)
    private Long signalId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 30)
    private FlagReason reason;

    @Column(name = "counts_from", nullable = false, updatable = false)
    private LocalDateTime countsFrom;

    @Column(nullable = false)
    private boolean resolved = false;

    /** Required by JPA. */
    protected SignalFlag() {
    }

    public SignalFlag(long signalId, long userId, FlagReason reason, LocalDateTime countsFrom) {
        this.signalId = signalId;
        this.userId = userId;
        this.reason = reason;
        this.countsFrom = countsFrom;
    }

    public Long getId() {
        return id;
    }

    public Long getSignalId() {
        return signalId;
    }

    public Long getUserId() {
        return userId;
    }

    public FlagReason getReason() {
        return reason;
    }

    public LocalDateTime getCountsFrom() {
        return countsFrom;
    }

    public boolean isResolved() {
        return resolved;
    }

    public void resolve() {
        this.resolved = true;
    }
}
