package com.safesphere.backend.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * Phase 11 C1 (V19): one sign-in on one phone. Its id is the access tokens' "sid" claim. Holds only the SHA-256 of the
 * current refresh secret (and of the one just replaced), never a secret itself. Timestamps hold UTC.
 */
@Entity
@Table(name = "device_sessions")
public class DeviceSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false, updatable = false, unique = true, length = 36)
    private String sessionId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "secret_hash", nullable = false, length = 64)
    private String secretHash;

    @Column(name = "previous_secret_hash", length = 64)
    private String previousSecretHash;

    @Column(name = "rotated_at")
    private LocalDateTime rotatedAt;

    @Column(name = "authenticated_at", nullable = false)
    private LocalDateTime authenticatedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_used_at", nullable = false)
    private LocalDateTime lastUsedAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "revoked_reason", length = 20)
    private SessionRevocation revokedReason;

    /** Required by JPA. */
    protected DeviceSession() {
    }

    public DeviceSession(String sessionId, Long userId, String secretHash, LocalDateTime authenticatedAt,
                         LocalDateTime now, LocalDateTime expiresAt) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.secretHash = secretHash;
        this.authenticatedAt = micros(authenticatedAt);
        this.createdAt = micros(now);
        this.lastUsedAt = micros(now);
        this.expiresAt = micros(expiresAt);
    }

    public Long getId() {
        return id;
    }

    public String getSessionId() {
        return sessionId;
    }

    public Long getUserId() {
        return userId;
    }

    public String getSecretHash() {
        return secretHash;
    }

    public String getPreviousSecretHash() {
        return previousSecretHash;
    }

    public LocalDateTime getRotatedAt() {
        return rotatedAt;
    }

    public LocalDateTime getAuthenticatedAt() {
        return authenticatedAt;
    }

    public LocalDateTime getLastUsedAt() {
        return lastUsedAt;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public LocalDateTime getRevokedAt() {
        return revokedAt;
    }

    public SessionRevocation getRevokedReason() {
        return revokedReason;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpired(LocalDateTime now) {
        return !now.isBefore(expiresAt);
    }

    /** A new secret replaces the current one, which is remembered as "previous" (lost-answer grace, reuse detection). */
    public void rotate(String newSecretHash, LocalDateTime now, LocalDateTime newExpiry) {
        this.previousSecretHash = this.secretHash;
        this.secretHash = newSecretHash;
        this.rotatedAt = micros(now);
        this.lastUsedAt = micros(now);
        this.expiresAt = micros(newExpiry);
    }

    /** The password was typed again for this sign-in (password change on this phone). */
    public void reauthenticated(LocalDateTime now) {
        this.authenticatedAt = micros(now);
    }

    /** Idempotent: the first reason is kept. */
    public void revoke(SessionRevocation reason, LocalDateTime now) {
        if (revokedAt != null) return;
        this.revokedAt = micros(now);
        this.revokedReason = reason;
    }

    private static LocalDateTime micros(LocalDateTime value) {
        return value.truncatedTo(ChronoUnit.MICROS);
    }
}
