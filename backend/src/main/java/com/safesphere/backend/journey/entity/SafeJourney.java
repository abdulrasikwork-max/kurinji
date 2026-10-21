package com.safesphere.backend.journey.entity;

import com.safesphere.backend.escalation.entity.EscalationLevel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A Safe Walk. Maps to "safe_journeys" (migration V5). Timestamps hold UTC and are always passed in by the
 * service (from the injectable Clock), so the deadline rules are testable and use the SERVER's time.
 * Only the last known location is kept: each new one overwrites the previous one.
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

    /** Phase 4C (V7): optional, both or neither; cleared when the walk ends normally. */
    @Column(name = "destination_latitude", precision = 9, scale = 6)
    private BigDecimal destinationLatitude;

    @Column(name = "destination_longitude", precision = 9, scale = 6)
    private BigDecimal destinationLongitude;

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

    @Column(name = "last_latitude", precision = 9, scale = 6)
    private BigDecimal lastLatitude;

    @Column(name = "last_longitude", precision = 9, scale = 6)
    private BigDecimal lastLongitude;

    @Column(name = "last_accuracy_meters", precision = 7, scale = 1)
    private BigDecimal lastAccuracyMeters;

    @Column(name = "last_location_at")
    private LocalDateTime lastLocationAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** Required by JPA. */
    protected SafeJourney() {
    }

    /** A new journey starts ACTIVE at [now]; the first check-in is due one interval later. */
    public SafeJourney(Long userId, String destinationLabel, int checkInIntervalMinutes, LocalDateTime now) {
        this(userId, destinationLabel, null, null, checkInIntervalMinutes, now);
    }

    /** With the destination's coordinates (both or neither; the service checks that). */
    public SafeJourney(Long userId, String destinationLabel, BigDecimal destinationLatitude,
                       BigDecimal destinationLongitude, int checkInIntervalMinutes, LocalDateTime now) {
        this.userId = userId;
        this.destinationLabel = destinationLabel;
        this.destinationLatitude = destinationLatitude;
        this.destinationLongitude = destinationLongitude;
        this.checkInIntervalMinutes = checkInIntervalMinutes;
        this.status = JourneyStatus.ACTIVE;
        this.escalationLevel = EscalationLevel.NORMAL.code();
        this.startedAt = now;
        this.nextCheckInDueAt = now.plusMinutes(checkInIntervalMinutes);
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** "I'm OK": back to normal, next check-in one full interval from now. */
    public void checkIn(LocalDateTime now) {
        this.escalationLevel = EscalationLevel.NORMAL.code();
        this.nextCheckInDueAt = now.plusMinutes(checkInIntervalMinutes);
        this.updatedAt = now;
    }

    public void escalateTo(EscalationLevel level, LocalDateTime now) {
        this.escalationLevel = level.code();
        this.updatedAt = now;
    }

    /**
     * Ends an ACTIVE journey. The service checks it is still ACTIVE first.
     * Minimum retention (Phase 4C): once the person has arrived or cancelled, nobody needs to know where they
     * were or were going, so the coordinates are deleted. A walk that ended in an EMERGENCY keeps them, because
     * the emergency may need them.
     */
    public void end(JourneyStatus finalStatus, LocalDateTime now) {
        this.status = finalStatus;
        this.endedAt = now;
        this.nextCheckInDueAt = null;
        this.updatedAt = now;
        if (finalStatus == JourneyStatus.COMPLETED || finalStatus == JourneyStatus.CANCELLED) {
            this.destinationLatitude = null;
            this.destinationLongitude = null;
            this.lastLatitude = null;
            this.lastLongitude = null;
            this.lastAccuracyMeters = null;
            this.lastLocationAt = null;
        }
    }

    public boolean hasDestination() {
        return destinationLatitude != null && destinationLongitude != null;
    }

    /** Replaces the last known location (no history is kept). */
    public void recordLocation(BigDecimal latitude, BigDecimal longitude, BigDecimal accuracyMeters,
                               LocalDateTime recordedAt, LocalDateTime now) {
        this.lastLatitude = latitude;
        this.lastLongitude = longitude;
        this.lastAccuracyMeters = accuracyMeters;
        this.lastLocationAt = recordedAt;
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

    public BigDecimal getDestinationLatitude() {
        return destinationLatitude;
    }

    public BigDecimal getDestinationLongitude() {
        return destinationLongitude;
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

    public BigDecimal getLastLatitude() {
        return lastLatitude;
    }

    public BigDecimal getLastLongitude() {
        return lastLongitude;
    }

    public BigDecimal getLastAccuracyMeters() {
        return lastAccuracyMeters;
    }

    public LocalDateTime getLastLocationAt() {
        return lastLocationAt;
    }

    /** The location is deliberately left out so a stray log line never contains it. */
    @Override
    public String toString() {
        return "SafeJourney{id=" + id + ", status=" + status + ", lastLocation=<redacted>}";
    }
}
