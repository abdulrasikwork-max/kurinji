package com.safesphere.backend.circlealert.entity;

import com.safesphere.backend.circlealert.entity.CircleAlertEnums.Channel;
import com.safesphere.backend.circlealert.entity.CircleAlertEnums.Kind;
import com.safesphere.backend.circlealert.entity.CircleAlertEnums.Status;
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
 * One reported attempt to tell a Safety Circle contact something (Phase 4D, migration V8). Belongs to an
 * emergency or a journey (the factory guarantees one of them). Holds no phone number and no message text.
 */
@Entity
@Table(name = "circle_alerts")
public class CircleAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "emergency_id", updatable = false)
    private Long emergencyId;

    @Column(name = "journey_id", updatable = false)
    private Long journeyId;

    /** Null when the contact was deleted later (ON DELETE SET NULL), or never known. */
    @Column(name = "contact_id")
    private Long contactId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 30)
    private Kind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private Channel channel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "client_ref", nullable = false, updatable = false, length = 36)
    private String clientRef;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private LocalDateTime occurredAt;

    @Column(name = "received_at", nullable = false, updatable = false)
    private LocalDateTime receivedAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** Required by JPA. */
    protected CircleAlert() {
    }

    private CircleAlert(Long emergencyId, Long journeyId, Long contactId, Kind kind, Channel channel, Status status,
                        String clientRef, LocalDateTime occurredAt, LocalDateTime now) {
        if ((emergencyId == null) == (journeyId == null)) {
            throw new IllegalArgumentException("A circle alert belongs to exactly one emergency or journey");
        }
        this.emergencyId = emergencyId;
        this.journeyId = journeyId;
        this.contactId = contactId;
        this.kind = kind;
        this.channel = channel;
        this.status = status;
        this.clientRef = clientRef;
        this.occurredAt = occurredAt;
        this.receivedAt = now;
        this.updatedAt = now;
    }

    public static CircleAlert forEmergency(long emergencyId, Long contactId, Kind kind, Channel channel, Status status,
                                           String clientRef, LocalDateTime occurredAt, LocalDateTime now) {
        return new CircleAlert(emergencyId, null, contactId, kind, channel, status, clientRef, occurredAt, now);
    }

    public static CircleAlert forJourney(long journeyId, Long contactId, Kind kind, Channel channel, Status status,
                                         String clientRef, LocalDateTime occurredAt, LocalDateTime now) {
        return new CircleAlert(null, journeyId, contactId, kind, channel, status, clientRef, occurredAt, now);
    }

    /** Status only moves forward (e.g. SENT -> DELIVERED). Returns true when it changed. */
    public boolean advanceTo(Status newStatus, LocalDateTime now) {
        if (newStatus.rank() <= status.rank()) return false;
        this.status = newStatus;
        this.updatedAt = now;
        return true;
    }

    public Long getId() {
        return id;
    }

    public Long getEmergencyId() {
        return emergencyId;
    }

    public Long getJourneyId() {
        return journeyId;
    }

    public Long getContactId() {
        return contactId;
    }

    public Kind getKind() {
        return kind;
    }

    public Channel getChannel() {
        return channel;
    }

    public Status getStatus() {
        return status;
    }

    public String getClientRef() {
        return clientRef;
    }

    public LocalDateTime getOccurredAt() {
        return occurredAt;
    }
}
