package com.safesphere.backend.evidence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * The person's recovery key (RK) wrapped with a key derived from their recovery passphrase (V11). The server stores
 * it so a NEW phone can fetch it; it cannot open it (the passphrase never reaches the server). At most one per person.
 */
@Entity
@Table(name = "evidence_recovery_envelopes")
public class RecoveryEnvelope {

    public static final String KDF_PBKDF2_SHA256 = "PBKDF2-SHA256";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "rk_version", nullable = false)
    private int rkVersion;

    @Column(name = "envelope_version", nullable = false)
    private int envelopeVersion;

    @Column(nullable = false, length = 20)
    private String kdf;

    @Column(name = "kdf_iterations", nullable = false)
    private int kdfIterations;

    @Column(name = "kdf_salt", nullable = false, length = 32)
    private byte[] kdfSalt;

    @Column(name = "wrapped_recovery_key", nullable = false, length = 64)
    private byte[] wrappedRecoveryKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "last_downloaded_at")
    private LocalDateTime lastDownloadedAt;

    /** Required by JPA. */
    protected RecoveryEnvelope() {
    }

    public RecoveryEnvelope(long userId, int rkVersion, String kdf, int kdfIterations, byte[] kdfSalt,
                            byte[] wrappedRecoveryKey, LocalDateTime now) {
        this.userId = userId;
        this.rkVersion = rkVersion;
        this.envelopeVersion = 1;
        this.kdf = kdf;
        this.kdfIterations = kdfIterations;
        this.kdfSalt = kdfSalt;
        this.wrappedRecoveryKey = wrappedRecoveryKey;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** A new passphrase (same or next RK): every phone holding the old envelope must fetch it again. */
    public void replace(int rkVersion, String kdf, int kdfIterations, byte[] kdfSalt, byte[] wrappedRecoveryKey,
                        LocalDateTime now) {
        this.rkVersion = rkVersion;
        this.envelopeVersion = this.envelopeVersion + 1;
        this.kdf = kdf;
        this.kdfIterations = kdfIterations;
        this.kdfSalt = kdfSalt;
        this.wrappedRecoveryKey = wrappedRecoveryKey;
        this.updatedAt = now;
    }

    public void downloaded(LocalDateTime now) {
        this.lastDownloadedAt = now;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public int getRkVersion() {
        return rkVersion;
    }

    public int getEnvelopeVersion() {
        return envelopeVersion;
    }

    public String getKdf() {
        return kdf;
    }

    public int getKdfIterations() {
        return kdfIterations;
    }

    public byte[] getKdfSalt() {
        return kdfSalt;
    }

    public byte[] getWrappedRecoveryKey() {
        return wrappedRecoveryKey;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public LocalDateTime getLastDownloadedAt() {
        return lastDownloadedAt;
    }

    @Override
    public String toString() {
        return "RecoveryEnvelope{userId=" + userId + ", rkVersion=" + rkVersion + ", envelopeVersion="
                + envelopeVersion + "}";
    }
}
