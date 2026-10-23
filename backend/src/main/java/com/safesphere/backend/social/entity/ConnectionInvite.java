package com.safesphere.backend.social.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Phase 13A (V20): a one-time invite. Only the SHA-256 of the code is stored; the code is shown to its owner once.
 * Single use: [usedAt] is set when someone redeems it (the connection it created is the record of who).
 */
@Entity
@Table(name = "connection_invites", uniqueConstraints = {
        @UniqueConstraint(name = "uk_connection_invites_public_id", columnNames = "public_id"),
        @UniqueConstraint(name = "uk_connection_invites_code", columnNames = "code_hash")})
public class ConnectionInvite {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, updatable = false, length = 36)
    private String publicId;

    @Column(name = "owner_id", nullable = false, updatable = false)
    private Long ownerId;

    @Column(name = "code_hash", nullable = false, updatable = false, length = 64)
    private String codeHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private LocalDateTime expiresAt;

    @Column(name = "used_at")
    private LocalDateTime usedAt;

    /** Required by JPA. */
    protected ConnectionInvite() {
    }

    public ConnectionInvite(long ownerId, String codeHash, LocalDateTime now, LocalDateTime expiresAt) {
        this.publicId = UUID.randomUUID().toString();
        this.ownerId = ownerId;
        this.codeHash = codeHash;
        this.createdAt = now.truncatedTo(ChronoUnit.MICROS);
        this.expiresAt = expiresAt.truncatedTo(ChronoUnit.MICROS);
    }

    /** Usable = not used and not expired at [now]. */
    public boolean isUsable(LocalDateTime now) {
        return usedAt == null && now.isBefore(expiresAt);
    }

    public void markUsed(LocalDateTime now) {
        LocalDateTime at = now.truncatedTo(ChronoUnit.MICROS);
        this.usedAt = at.isBefore(createdAt) ? createdAt : at;
    }

    public Long getId() {
        return id;
    }

    public String getPublicId() {
        return publicId;
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public String getCodeHash() {
        return codeHash;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public LocalDateTime getUsedAt() {
        return usedAt;
    }
}
