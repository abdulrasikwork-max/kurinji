package com.safesphere.backend.journey.entity;

import com.safesphere.backend.escalation.entity.EscalationLevel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import java.time.LocalDateTime;

/**
 * A walk ("On my way", N6 D22; code name Safe Walk). Maps to "safe_journeys" (migration V5). Timestamps hold UTC and
 * are always passed in by the service (from the injectable Clock), so the deadline rules are testable and use the
 * SERVER's time.
 *
 * N6-B1: no location of any kind. The destination coordinates (V7) are no longer mapped (V37 drops them); the
 * last-location columns stay in the table for a possible later walk-alert phase but are never written or read (V37
 * clears old values).
 */
@Entity
@Table(name = "safe_journeys")
public class SafeJourney {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private JourneyStatus status;

    @Column(name = "destination_label", updatable = false, length = 100)
    private String destinationLabel;

    @Column(name = "check_in_interval_minutes", nullable = false, updatable = false)
    private Integer checkInIntervalMinutes;

    @Column(name = "escalation_level", nullable = false)
    private Integer escalationLevel;

    @Column(name = "next_check_in_due_at")
    private LocalDateTime nextCheckInDueAt;

    @Column(name = "started_at", nullable = false, updatable = false)
    private LocalDateTime startedAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** Required by JPA. */
    protected SafeJourney() {
    }

    /** A new walk starts ACTIVE at [now]; the first check-in is due one interval later. */
    public SafeJourney(Long userId, String destinationLabel, int checkInIntervalMinutes, LocalDateTime now) {
        this.userId = userId;
        this.destinationLabel = destinationLabel;
        this.checkInIntervalMinutes = checkInIntervalMinutes;
        this.status = JourneyStatus.ACTIVE;
        this.escalationLevel = EscalationLevel.NORMAL.code();
        this.startedAt = now;
        this.nextCheckInDueAt = now.plusMinutes(checkInIntervalMinutes);
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** "More time" / "I'm OK": next check-in one full interval from now. */
    public void checkIn(LocalDateTime now) {
        this.escalationLevel = EscalationLevel.NORMAL.code();
        this.nextCheckInDueAt = now.plusMinutes(checkInIntervalMinutes);
        this.updatedAt = now;
    }

    /** Only an SOS moves a walk off level 0 (to 3, then it ends). */
    public void escalateTo(EscalationLevel level, LocalDateTime now) {
        this.escalationLevel = level.code();
        this.updatedAt = now;
    }

    /** Ends an ACTIVE walk. The service checks it is still ACTIVE first. */
    public void end(JourneyStatus finalStatus, LocalDateTime now) {
        this.status = finalStatus;
        this.endedAt = now;
        this.nextCheckInDueAt = null;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public JourneyStatus getStatus() {
        return status;
    }

    public String getDestinationLabel() {
        return destinationLabel;
    }

    public Integer getCheckInIntervalMinutes() {
        return checkInIntervalMinutes;
    }

    public EscalationLevel getEscalationLevel() {
        return EscalationLevel.fromCode(escalationLevel);
    }

    public LocalDateTime getNextCheckInDueAt() {
        return nextCheckInDueAt;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public LocalDateTime getEndedAt() {
        return endedAt;
    }

    /** The label is the person's own words: left out so a stray log line never contains it. */
    @Override
    public String toString() {
        return "SafeJourney{id=" + id + ", status=" + status + "}";
    }
}
