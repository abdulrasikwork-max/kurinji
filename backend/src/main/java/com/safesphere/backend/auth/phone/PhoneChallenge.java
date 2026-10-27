package com.safesphere.backend.auth.phone;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * Phase 17B (V26): the one phone verification in progress for an account (the primary key is the account id, so a new
 * one replaces the old). The app holds the challenge token; only its SHA-256 is stored. The code is never stored: only
 * an HMAC of it ([codeHash]). [phoneE164] is the number being verified (null while the person still has to enter one).
 */
@Entity
@Table(name = "phone_challenges")
public class PhoneChallenge {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "phone_e164", length = 16)
    private String phoneE164;

    @Column(name = "code_hash", length = 64)
    private String codeHash;

    @Column(name = "code_expires_at")
    private LocalDateTime codeExpiresAt;

    @Column(nullable = false)
    private int attempts;

    @Column(nullable = false)
    private int sends;

    @Column(name = "last_sent_at")
    private LocalDateTime lastSentAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    protected PhoneChallenge() {
    }

    public PhoneChallenge(Long userId, String tokenHash, String phoneE164, LocalDateTime now, LocalDateTime expiresAt) {
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.phoneE164 = phoneE164;
        this.createdAt = now.truncatedTo(ChronoUnit.MICROS);
        this.expiresAt = expiresAt.truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * The app lost its token (closed during sign-up) and the person signed in again: a new token for the same
     * verification, so a code already on its way still works. The old token stops working.
     */
    public void rotateToken(String tokenHash, LocalDateTime expiresAt) {
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt.truncatedTo(ChronoUnit.MICROS);
    }

    /** A new code was made for [phoneE164]: the earlier one stops working and its wrong tries are forgotten. */
    public void newCode(String codeHash, LocalDateTime now, LocalDateTime codeExpiresAt) {
        this.codeHash = codeHash;
        this.codeExpiresAt = codeExpiresAt.truncatedTo(ChronoUnit.MICROS);
        this.lastSentAt = now.truncatedTo(ChronoUnit.MICROS);
        this.attempts = 0;
        this.sends++;
    }

    /** The provider did not take the code: it never reached the phone, so it neither counts nor makes anyone wait. */
    public void undoSend() {
        this.codeHash = null;
        this.codeExpiresAt = null;
        this.lastSentAt = null;
        this.sends = Math.max(0, sends - 1);
    }

    /** Another number (only before verification). The earlier code stops working. */
    public void changeNumber(String phoneE164) {
        this.phoneE164 = phoneE164;
        this.codeHash = null;
        this.codeExpiresAt = null;
        this.attempts = 0;
    }

    public void wrongCode() {
        this.attempts++;
    }

    public boolean isLive(LocalDateTime now) {
        return now.isBefore(expiresAt);
    }

    public boolean hasLiveCode(LocalDateTime now) {
        return codeHash != null && now.isBefore(codeExpiresAt);
    }

    /** Seconds until the next code may be sent (0 = now). */
    public long resendInSeconds(LocalDateTime now, Duration cooldown) {
        if (lastSentAt == null) return 0;
        long seconds = Duration.between(now, lastSentAt.plus(cooldown)).toSeconds();
        return Math.max(0, seconds);
    }

    public Long getUserId() {
        return userId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public String getPhoneE164() {
        return phoneE164;
    }

    public String getCodeHash() {
        return codeHash;
    }

    public LocalDateTime getCodeExpiresAt() {
        return codeExpiresAt;
    }

    public int getAttempts() {
        return attempts;
    }

    public int getSends() {
        return sends;
    }

    @Override
    public String toString() {
        return "PhoneChallenge[userId=" + userId + ", hashes=<hidden>]";
    }
}
