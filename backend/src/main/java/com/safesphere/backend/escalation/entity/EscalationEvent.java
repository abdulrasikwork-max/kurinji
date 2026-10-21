package com.safesphere.backend.escalation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * One change on the Escalation Ladder. Maps to "escalation_events" (migration V6). It belongs to a journey,
 * an emergency, or both; the factory methods guarantee at least one (the database cannot, see V6).
 * Rows are never updated.
 */
@Entity
@Table(name = "escalation_events")
public class EscalationEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "journey_id", updatable = false)
    private Long journeyId;

    @Column(name = "emergency_id", updatable = false)
    private Long emergencyId;

    @Column(nullable = false, updatable = false)
    private Integer level;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 30)
    private EscalationReason reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** Required by JPA. */
    protected EscalationEvent() {
    }

    private EscalationEvent(Long journeyId, Long emergencyId, EscalationLevel level, EscalationReason reason,
                            LocalDateTime createdAt) {
        if (journeyId == null && emergencyId == null) {
            throw new IllegalArgumentException("An escalation belongs to a journey or an emergency");
        }
        if (level == EscalationLevel.VERIFIED_NEARBY_ASSISTANCE) {
            throw new IllegalArgumentException("Level 5 is reserved");
        }
        this.journeyId = journeyId;
        this.emergencyId = emergencyId;
        this.level = level.code();
        this.reason = reason;
        this.createdAt = createdAt;
    }

    public static EscalationEvent forJourney(Long journeyId, EscalationLevel level, EscalationReason reason,
                                             LocalDateTime at) {
        return new EscalationEvent(journeyId, null, level, reason, at);
    }

    public static EscalationEvent forEmergency(Long emergencyId, EscalationLevel level, EscalationReason reason,
                                               LocalDateTime at) {
        return new EscalationEvent(null, emergencyId, level, reason, at);
    }

    public static EscalationEvent forJourneyAndEmergency(Long journeyId, Long emergencyId, EscalationLevel level,
                                                         EscalationReason reason, LocalDateTime at) {
        return new EscalationEvent(journeyId, emergencyId, level, reason, at);
    }

    public Long getId() {
        return id;
    }

    public Long getJourneyId() {
        return journeyId;
    }

    public Long getEmergencyId() {
        return emergencyId;
    }

    public EscalationLevel getLevel() {
        return EscalationLevel.fromCode(level);
    }

    public EscalationReason getReason() {
        return reason;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
