package com.safesphere.backend.push.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * N7 (D28 = N-120, V38 "push_devices"): the FCM token of ONE sign-in (a device_sessions row). Stored only to send; never
 * logged, never returned. A token belongs to one session at a time (token_hash unique); the row goes with the session.
 */
@Entity
@Table(name = "push_devices")
public class PushDevice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "session_id", nullable = false, updatable = false, unique = true)
    private Long sessionId;

    @Column(name = "token", nullable = false, length = 512)
    private String token;

    @Column(name = "token_hash", nullable = false, length = 64, unique = true)
    private String tokenHash;

    @Column(name = "app_version", length = 32)
    private String appVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** Required by JPA. */
    protected PushDevice() {
    }

    public PushDevice(long userId, long sessionId, String token, String tokenHash, String appVersion, LocalDateTime now) {
        this.userId = userId;
        this.sessionId = sessionId;
        this.token = token;
        this.tokenHash = tokenHash;
        this.appVersion = appVersion;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void refresh(String token, String tokenHash, String appVersion, LocalDateTime now) {
        this.token = token;
        this.tokenHash = tokenHash;
        this.appVersion = appVersion;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public String getToken() {
        return token;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    /** Never prints the token. */
    @Override
    public String toString() {
        return "PushDevice[id=" + id + "]";
    }
}
