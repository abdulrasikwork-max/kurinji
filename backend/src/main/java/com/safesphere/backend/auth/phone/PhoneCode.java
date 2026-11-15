package com.safesphere.backend.auth.phone;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * Phase N2 (V33): one code in progress for one phone number and one purpose (replaces V26's per-account challenge).
 *
 * <ul>
 *   <li>[Purpose#SIGN_IN]: anyone who types a number; no account is named ([userId] null). After the right code for a
 *       number without an account the row holds a sign-up ticket ([ticketHash]) instead of a code.</li>
 *   <li>[Purpose#CONFIRM]: a signed-in person asks for a fresh code before a sensitive action; [userId] is theirs.</li>
 * </ul>
 * The app holds the challenge token (and later the ticket); only their SHA-256 is stored. The code itself is never
 * stored, only an HMAC of it ([codeHash]).
 */
@Entity
@Table(name = "phone_codes", uniqueConstraints = {
        @UniqueConstraint(name = "uk_phone_codes_purpose_phone", columnNames = {"purpose", "phone_e164"}),
        @UniqueConstraint(name = "uk_phone_codes_token", columnNames = "token_hash"),
        @UniqueConstraint(name = "uk_phone_codes_ticket", columnNames = "ticket_hash")})
public class PhoneCode {

    public enum Purpose { SIGN_IN, CONFIRM }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Purpose purpose;

    @Column(name = "phone_e164", nullable = false, length = 16)
    private String phoneE164;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

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

    @Column(name = "ticket_hash", length = 64)
    private String ticketHash;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    protected PhoneCode() {
    }

    /** [userId] must be null for SIGN_IN and set for CONFIRM (enforced here: MySQL cannot CHECK a cascading FK). */
    public PhoneCode(Purpose purpose, String phoneE164, Long userId, String tokenHash, LocalDateTime now,
                     LocalDateTime expiresAt) {
        if ((purpose == Purpose.CONFIRM) != (userId != null)) {
            throw new IllegalArgumentException("CONFIRM codes belong to an account, SIGN_IN codes to none");
        }
        this.purpose = purpose;
        this.phoneE164 = phoneE164;
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.createdAt = now.truncatedTo(ChronoUnit.MICROS);
        this.expiresAt = expiresAt.truncatedTo(ChronoUnit.MICROS);
    }

    /** The same number asked again while a code is still live: a new token, the code on its way still works. */
    public void rotateToken(String tokenHash, LocalDateTime expiresAt) {
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt.truncatedTo(ChronoUnit.MICROS);
    }

    /** A new code was made: the earlier one stops working and its wrong tries are forgotten. */
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

    public void wrongCode() {
        this.attempts++;
    }

    /** SIGN_IN only: the right code for a number without an account. The code is used up; the ticket replaces it. */
    public void issueTicket(String ticketHash, LocalDateTime expiresAt) {
        if (purpose != Purpose.SIGN_IN) throw new IllegalStateException("Only a sign-in code leads to a sign-up");
        this.ticketHash = ticketHash;
        this.codeHash = null;
        this.codeExpiresAt = null;
        this.expiresAt = expiresAt.truncatedTo(ChronoUnit.MICROS);
    }

    public boolean isLive(LocalDateTime now) {
        return now.isBefore(expiresAt);
    }

    public boolean hasLiveCode(LocalDateTime now) {
        return codeHash != null && now.isBefore(codeExpiresAt);
    }

    public boolean holdsTicket() {
        return ticketHash != null;
    }

    /** Seconds until the next code may be sent (0 = now). */
    public long resendInSeconds(LocalDateTime now, Duration cooldown) {
        if (lastSentAt == null) return 0;
        long seconds = Duration.between(now, lastSentAt.plus(cooldown)).toSeconds();
        return Math.max(0, seconds);
    }

    public Long getId() {
        return id;
    }

    public Purpose getPurpose() {
        return purpose;
    }

    public String getPhoneE164() {
        return phoneE164;
    }

    public Long getUserId() {
        return userId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public String getCodeHash() {
        return codeHash;
    }

    public LocalDateTime getCodeExpiresAt() {
        return codeExpiresAt;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public int getAttempts() {
        return attempts;
    }

    public int getSends() {
        return sends;
    }

    @Override
    public String toString() {
        return "PhoneCode[id=" + id + ", purpose=" + purpose + ", <hidden>]";
    }
}
