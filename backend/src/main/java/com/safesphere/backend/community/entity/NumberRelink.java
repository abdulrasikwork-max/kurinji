package com.safesphere.backend.community.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * Phase N3 (V34, N-25): a new number (proved by its sign-up ticket) waiting for an admin who sees the college ID in
 * person to move a member's account to it. Only the HMAC of the code and of the roll number are stored. Deleted at the
 * decision or at expiry.
 */
@Entity
@Table(name = "number_relinks", uniqueConstraints = {
        @UniqueConstraint(name = "uk_number_relinks_phone", columnNames = "phone_e164"),
        @UniqueConstraint(name = "uk_number_relinks_code", columnNames = "code_hash")})
public class NumberRelink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "community_id", nullable = false, updatable = false)
    private Long communityId;

    @Column(name = "phone_e164", nullable = false, updatable = false, length = 16)
    private String phoneE164;

    @Column(name = "roll_key", nullable = false, updatable = false, length = 64)
    private String rollKey;

    @Column(name = "code_hash", nullable = false, updatable = false, length = 64)
    private String codeHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private LocalDateTime expiresAt;

    protected NumberRelink() {
    }

    public NumberRelink(long communityId, String phoneE164, String rollKey, String codeHash, LocalDateTime now,
                        LocalDateTime expiresAt) {
        this.communityId = communityId;
        this.phoneE164 = phoneE164;
        this.rollKey = rollKey;
        this.codeHash = codeHash;
        this.createdAt = now.truncatedTo(ChronoUnit.MICROS);
        this.expiresAt = expiresAt.truncatedTo(ChronoUnit.MICROS);
    }

    public boolean isExpired(LocalDateTime now) {
        return !expiresAt.isAfter(now);
    }

    public Long getId() {
        return id;
    }

    public Long getCommunityId() {
        return communityId;
    }

    public String getPhoneE164() {
        return phoneE164;
    }

    public String getRollKey() {
        return rollKey;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }
}
