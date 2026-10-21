package com.safesphere.backend.emergency.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/** One recorded emergency session. Maps to "emergency_events" (migration V3). Timestamps hold UTC. */
@Entity
@Table(name = "emergency_events")
public class EmergencyEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EmergencyStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, updatable = false, length = 20)
    private TriggerType triggerType;

    /** The Safe Walk that was active when this emergency started (migration V6); null for a plain SOS. */
    @Column(name = "journey_id")
    private Long journeyId;

    @Column(name = "started_at", nullable = false, updatable = false)
    private LocalDateTime startedAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** Required by JPA. */
    protected EmergencyEvent() {
    }

    /** A new emergency always starts ACTIVE, right now. */
    public EmergencyEvent(Long userId, TriggerType triggerType) {
        this.userId = userId;
        this.triggerType = triggerType;
        this.status = EmergencyStatus.ACTIVE;
        this.startedAt = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    /** Moves an ACTIVE emergency to CANCELLED or RESOLVED. The service checks it is still ACTIVE first. */
    public void end(EmergencyStatus finalStatus) {
        this.status = finalStatus;
        this.endedAt = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    /** Set once by JourneyService when the emergency starts during a Safe Walk. */
    public void linkJourney(Long journeyId) {
        this.journeyId = journeyId;
    }

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public EmergencyStatus getStatus() {
        return status;
    }

    public TriggerType getTriggerType() {
        return triggerType;
    }

    public Long getJourneyId() {
        return journeyId;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public LocalDateTime getEndedAt() {
        return endedAt;
    }
}
