package com.safesphere.backend.journey.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/** One entry in a journey's history. Maps to "journey_events" (migration V5). Never updated. */
@Entity
@Table(name = "journey_events")
public class JourneyEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "journey_id", nullable = false, updatable = false)
    private Long journeyId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private JourneyEventType type;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private LocalDateTime occurredAt;

    /** Required by JPA. */
    protected JourneyEvent() {
    }

    public JourneyEvent(Long journeyId, JourneyEventType type, LocalDateTime occurredAt) {
        this.journeyId = journeyId;
        this.type = type;
        this.occurredAt = occurredAt;
    }

    public Long getJourneyId() {
        return journeyId;
    }

    public JourneyEventType getType() {
        return type;
    }

    public LocalDateTime getOccurredAt() {
        return occurredAt;
    }
}
