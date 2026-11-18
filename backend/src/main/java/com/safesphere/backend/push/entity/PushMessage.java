package com.safesphere.backend.push.entity;

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
 * N7 (D35 = N-127, V38 "push_messages"): one queued push for one phone. Holds NO text, name, time of anything or place:
 * a closed type, an opaque subject id, the event id (the same on every phone of one event) and the queue's own times.
 */
@Entity
@Table(name = "push_messages")
public class PushMessage {

    public enum Status { QUEUED, SENT, SKIPPED, FAILED, EXPIRED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, updatable = false, length = 36)
    private String eventId;

    @Column(name = "device_id", nullable = false, updatable = false)
    private Long deviceId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, updatable = false, length = 24)
    private PushType type;

    @Column(name = "subject", nullable = false, updatable = false, length = 64)
    private String subject;

    @Column(name = "high_priority", nullable = false, updatable = false)
    private boolean highPriority;

    @Column(name = "ttl_seconds", nullable = false, updatable = false)
    private int ttlSeconds;

    @Column(name = "not_before", nullable = false, updatable = false)
    private LocalDateTime notBefore;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private LocalDateTime expiresAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    private Status status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at")
    private LocalDateTime nextAttemptAt;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @Column(name = "skip_reason", length = 24)
    private String skipReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** Required by JPA. */
    protected PushMessage() {
    }

    public PushMessage(String eventId, long deviceId, long userId, PushType type, String subject, LocalDateTime notBefore,
                       LocalDateTime now) {
        this.eventId = eventId;
        this.deviceId = deviceId;
        this.userId = userId;
        this.type = type;
        this.subject = subject;
        this.highPriority = type.highPriority();
        this.ttlSeconds = (int) type.lifetime().toSeconds();
        this.notBefore = notBefore;
        this.expiresAt = notBefore.plus(type.lifetime());
        this.status = Status.QUEUED;
        this.attempts = 0;
        this.nextAttemptAt = notBefore;
        this.createdAt = now;
    }

    /** Taken by a dispatcher run: counts the attempt and holds the row for a while, so a crash leads to a retry. */
    public void claim(LocalDateTime leaseUntil) {
        this.attempts++;
        this.nextAttemptAt = leaseUntil;
    }

    public void sent(LocalDateTime now) {
        this.status = Status.SENT;
        this.sentAt = now;
        this.nextAttemptAt = null;
    }

    public void retryAt(LocalDateTime when) {
        this.nextAttemptAt = when;
    }

    /** SKIPPED, FAILED or EXPIRED, with why (a closed code, never anything personal). */
    public void close(Status status, String reason) {
        if (status == Status.QUEUED || status == Status.SENT) throw new IllegalArgumentException("not a closing status");
        this.status = status;
        this.skipReason = reason;
        this.nextAttemptAt = null;
    }

    public Long getId() {
        return id;
    }

    public String getEventId() {
        return eventId;
    }

    public Long getDeviceId() {
        return deviceId;
    }

    public Long getUserId() {
        return userId;
    }

    public PushType getType() {
        return type;
    }

    public String getSubject() {
        return subject;
    }

    public boolean isHighPriority() {
        return highPriority;
    }

    public int getTtlSeconds() {
        return ttlSeconds;
    }

    public LocalDateTime getNotBefore() {
        return notBefore;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public Status getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public LocalDateTime getNextAttemptAt() {
        return nextAttemptAt;
    }

    public LocalDateTime getSentAt() {
        return sentAt;
    }

    public String getSkipReason() {
        return skipReason;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
