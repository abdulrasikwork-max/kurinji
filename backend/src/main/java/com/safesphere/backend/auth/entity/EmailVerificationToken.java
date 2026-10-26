package com.safesphere.backend.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * V25: the one live e-mail verification token of an account (the primary key is the account id, so a new e-mail
 * replaces the old token). Only SHA-256 of the token is stored; the token itself exists only in the e-mail.
 * Logically separate from refresh secrets (DeviceSession) and from any future password-reset token.
 */
@Entity
@Table(name = "email_verification_tokens")
public class EmailVerificationToken {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    protected EmailVerificationToken() {
    }

    public EmailVerificationToken(Long userId, String tokenHash, LocalDateTime createdAt, LocalDateTime expiresAt) {
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.createdAt = createdAt.truncatedTo(ChronoUnit.MICROS);
        this.expiresAt = expiresAt.truncatedTo(ChronoUnit.MICROS);
    }

    /** Replaces the token (a new e-mail): the previous link stops working. */
    public void replace(String tokenHash, LocalDateTime createdAt, LocalDateTime expiresAt) {
        this.tokenHash = tokenHash;
        this.createdAt = createdAt.truncatedTo(ChronoUnit.MICROS);
        this.expiresAt = expiresAt.truncatedTo(ChronoUnit.MICROS);
    }

    public Long getUserId() {
        return userId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public boolean isLive(LocalDateTime now) {
        return now.isBefore(expiresAt);
    }

    @Override
    public String toString() {
        return "EmailVerificationToken[userId=" + userId + ", hash=<hidden>]";
    }
}
