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
 * One person's answer to one place signal (V12). [countsFrom] (UTC) is the only time kept: server time plus a random
 * publication delay (plus what is left of a new account's probation). The real moment of the report is not stored.
 * [userId] is never returned to anyone else.
 */
@Entity
@Table(name = "signal_contributions",
        uniqueConstraints = @UniqueConstraint(name = "uk_signal_contributions_signal_user", columnNames = {"signal_id", "user_id"}))
public class SignalContribution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "signal_id", nullable = false, updatable = false)
    private Long signalId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private ContributionKind kind;

    @Column(name = "counts_from", nullable = false, updatable = false)
    private LocalDateTime countsFrom;

    /** Phase 7 (V13): this REPORT has been credited to its reporter's standing (independent support appeared). */
    @Column(nullable = false)
    private boolean credited = false;

    /** Required by JPA. */
    protected SignalContribution() {
    }

    public SignalContribution(long signalId, long userId, ContributionKind kind, LocalDateTime countsFrom) {
        this.signalId = signalId;
        this.userId = userId;
        this.kind = kind;
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

    public ContributionKind getKind() {
        return kind;
    }

    public LocalDateTime getCountsFrom() {
        return countsFrom;
    }

    public boolean isCredited() {
        return credited;
    }

    public void markCredited() {
        this.credited = true;
    }
}
