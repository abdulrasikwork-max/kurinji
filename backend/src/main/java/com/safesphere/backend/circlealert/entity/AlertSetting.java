package com.safesphere.backend.circlealert.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * N6 (D20, V37 "alert_settings"): the person's one explicit choice whether IRL texts their Safety Circle when an SOS
 * reaches the server. No row = not chosen yet = nothing is sent automatically (no preselection).
 */
@Entity
@Table(name = "alert_settings")
public class AlertSetting {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "server_sms", nullable = false)
    private boolean serverSms;

    @Column(name = "decided_at", nullable = false, updatable = false)
    private LocalDateTime decidedAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** Required by JPA. */
    protected AlertSetting() {
    }

    public AlertSetting(long userId, boolean serverSms, LocalDateTime now) {
        this.userId = userId;
        this.serverSms = serverSms;
        this.decidedAt = now;
        this.updatedAt = now;
    }

    public void change(boolean serverSms, LocalDateTime now) {
        this.serverSms = serverSms;
        this.updatedAt = now;
    }

    public Long getUserId() {
        return userId;
    }

    public boolean isServerSms() {
        return serverSms;
    }

    public LocalDateTime getDecidedAt() {
        return decidedAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
