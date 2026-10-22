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

    /**
     * Phase 10A.4 (V17): the sign-in (token "sid") that started this emergency, kept ONLY while it is ACTIVE (cleared
     * when it ends). Another sign-in needs the password to end it. Null for emergencies started by an older token.
     */
    @Column(name = "started_session", length = 36)
    private String startedSession;

    /** Phase 10A.4 (V17): ended from a different sign-in than the one that started it (with the password). */
    @Column(name = "ended_elsewhere", nullable = false)
    private boolean endedElsewhere;

    /** Phase 11 (V18): the phone's random reference for this SOS (re-sends return this row); null for older phones. */
    @Column(name = "client_ref", length = 36, updatable = false)
    private String clientRef;

    /** Phase 11 (V18): the SOS began on a phone that could not reach the server; its times are the phone's. */
    @Column(name = "started_offline", nullable = false, updatable = false)
    private boolean startedOffline;

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
        this(userId, triggerType, null);
    }

    /** Phase 10A.4: [startedSession] = the sign-in that starts it (null for a token without a session id). */
    public EmergencyEvent(Long userId, TriggerType triggerType, String startedSession) {
        this(userId, triggerType, startedSession, LocalDateTime.now(ZoneOffset.UTC), null, false);
    }

    /**
     * Phase 11: [startedAt] = UTC wall clock (the server's, or the phone's when [startedOffline], already clamped by
     * the service); [clientRef] = the phone's reference for this SOS or null.
     */
    public EmergencyEvent(Long userId, TriggerType triggerType, String startedSession, LocalDateTime startedAt,
                          String clientRef, boolean startedOffline) {
        this.userId = userId;
        this.triggerType = triggerType;
        this.startedSession = startedSession;
        this.status = EmergencyStatus.ACTIVE;
        this.startedAt = startedAt.truncatedTo(ChronoUnit.MICROS);
        this.clientRef = clientRef;
        this.startedOffline = startedOffline;
    }

    /** Moves an ACTIVE emergency to CANCELLED or RESOLVED. The service checks it is still ACTIVE first. */
    public void end(EmergencyStatus finalStatus) {
        end(finalStatus, false);
    }

    /** Phase 10A.4: the session id is no longer needed once the emergency ended (V17 CHECK), so it is dropped. */
    public void end(EmergencyStatus finalStatus, boolean fromAnotherSignIn) {
        end(finalStatus, fromAnotherSignIn, LocalDateTime.now(ZoneOffset.UTC));
    }

    /** Phase 11: [endedAt] = UTC wall clock, never before the start (V18 CHECK; the service clamps phone times). */
    public void end(EmergencyStatus finalStatus, boolean fromAnotherSignIn, LocalDateTime endedAt) {
        this.status = finalStatus;
        LocalDateTime end = endedAt.truncatedTo(ChronoUnit.MICROS);
        this.endedAt = end.isBefore(startedAt) ? startedAt : end;
        this.endedElsewhere = fromAnotherSignIn;
        this.startedSession = null;
    }

    /** True when [sessionId] is not the sign-in that started this emergency (an unknown starter counts as "same"). */
    public boolean startedByAnotherSignIn(String sessionId) {
        return startedSession != null && !startedSession.equals(sessionId);
    }

    public boolean isEndedElsewhere() {
        return endedElsewhere;
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

    /** When the server first stored this row (for an SOS started offline: when the phone reached the server). */
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public String getClientRef() {
        return clientRef;
    }

    public boolean isStartedOffline() {
        return startedOffline;
    }
}
