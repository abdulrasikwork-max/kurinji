package com.safesphere.backend.evidence.entity;

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
 * One encrypted evidence item of an emergency (Phase 5, migration V9). Holds metadata only: the ciphertext lives in a
 * file named [storageKey] (see EvidenceStorage). Timestamps hold UTC; "captured" times come from the phone's clock and
 * are checked against the emergency, "received/sealed/deleted" come from the server clock.
 *
 * State changes go through the methods below so the database CHECK rules (status vs. columns) always hold.
 */
@Entity
@Table(name = "evidence_items")
public class EvidenceItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "emergency_id", nullable = false, updatable = false)
    private Long emergencyId;

    @Column(name = "client_ref", nullable = false, updatable = false, length = 36)
    private String clientRef;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 10)
    private EvidenceKind kind;

    @Column(name = "media_type", nullable = false, updatable = false, length = 40)
    private String mediaType;

    @Column(name = "sequence_no", nullable = false, updatable = false)
    private int sequenceNo;

    @Column(name = "recording_ref", updatable = false, length = 36)
    private String recordingRef;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private EvidenceStatus status;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long sizeBytes;

    @Column(name = "received_bytes", nullable = false)
    private long receivedBytes;

    @Column(name = "ciphertext_sha256", nullable = false, updatable = false, length = 64)
    private String ciphertextSha256;

    @Column(name = "plaintext_sha256", nullable = false, updatable = false, length = 64)
    private String plaintextSha256;

    @Column(nullable = false, updatable = false, length = 20)
    private String encryption;

    @Column(name = "escrow_key_id", updatable = false, length = 64)
    private String escrowKeyId;

    @Column(name = "escrow_wrapped_key", length = 1024)
    private byte[] escrowWrappedKey;

    @Column(name = "recovery_rk_version")
    private Integer recoveryRkVersion;

    @Column(name = "recovery_wrapped_key", length = 64)
    private byte[] recoveryWrappedKey;

    @Column(name = "storage_key", length = 36)
    private String storageKey;

    @Column(name = "captured_started_at", nullable = false, updatable = false)
    private LocalDateTime capturedStartedAt;

    @Column(name = "captured_ended_at", nullable = false, updatable = false)
    private LocalDateTime capturedEndedAt;

    @Column(name = "received_at", nullable = false, updatable = false)
    private LocalDateTime receivedAt;

    @Column(name = "sealed_at")
    private LocalDateTime sealedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** Required by JPA. */
    protected EvidenceItem() {
    }

    public EvidenceItem(long emergencyId, String clientRef, EvidenceKind kind, int sequenceNo, String recordingRef,
                        long sizeBytes, String ciphertextSha256, String plaintextSha256, String encryption,
                        String escrowKeyId, byte[] escrowWrappedKey, String storageKey,
                        LocalDateTime capturedStartedAt, LocalDateTime capturedEndedAt, LocalDateTime now) {
        this.emergencyId = emergencyId;
        this.clientRef = clientRef;
        this.kind = kind;
        this.mediaType = kind.mediaType();
        this.sequenceNo = sequenceNo;
        this.recordingRef = recordingRef;
        this.status = EvidenceStatus.UPLOADING;
        this.sizeBytes = sizeBytes;
        this.receivedBytes = 0;
        this.ciphertextSha256 = ciphertextSha256;
        this.plaintextSha256 = plaintextSha256;
        this.encryption = encryption;
        this.escrowKeyId = escrowKeyId;
        this.escrowWrappedKey = escrowWrappedKey;
        this.storageKey = storageKey;
        this.capturedStartedAt = capturedStartedAt;
        this.capturedEndedAt = capturedEndedAt;
        this.receivedAt = now;
        this.updatedAt = now;
    }

    public void received(long totalBytes, LocalDateTime now) {
        this.receivedBytes = totalBytes;
        this.updatedAt = now;
    }

    /** Every byte arrived and the hash matched. */
    public void seal(LocalDateTime now) {
        this.status = EvidenceStatus.SEALED;
        this.sealedAt = now;
        this.updatedAt = now;
    }

    /** The bytes did not match the declared hash: start the upload again from zero. */
    public void restartUpload(LocalDateTime now) {
        this.receivedBytes = 0;
        this.updatedAt = now;
    }

    /**
     * The stored copy of a SEALED item no longer matches its hash (disk damage or tampering found by a re-check): it is
     * no longer "verified". Back to UPLOADING from byte 0, so a phone that still holds the item uploads it again; the
     * declared hash is unchanged, so only the original bytes can ever seal it again.
     */
    public void reopenAfterDamage(LocalDateTime now) {
        this.status = EvidenceStatus.UPLOADING;
        this.receivedBytes = 0;
        this.sealedAt = null;
        this.updatedAt = now;
    }

    /** The item key wrapped with the person's recovery key (V11). Opaque to the server. */
    public void attachRecoveryKey(int rkVersion, byte[] wrappedKey, LocalDateTime now) {
        this.recoveryRkVersion = rkVersion;
        this.recoveryWrappedKey = wrappedKey;
        this.updatedAt = now;
    }

    /** Tombstone: hashes, sizes and times stay; the file reference and every wrapped key copy are gone. */
    public void markDeleted(LocalDateTime now) {
        this.status = EvidenceStatus.DELETED;
        this.storageKey = null;
        this.escrowWrappedKey = null;
        this.recoveryRkVersion = null;
        this.recoveryWrappedKey = null;
        this.deletedAt = now;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public Long getEmergencyId() {
        return emergencyId;
    }

    public String getClientRef() {
        return clientRef;
    }

    public EvidenceKind getKind() {
        return kind;
    }

    public String getMediaType() {
        return mediaType;
    }

    public int getSequenceNo() {
        return sequenceNo;
    }

    public String getRecordingRef() {
        return recordingRef;
    }

    public EvidenceStatus getStatus() {
        return status;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public long getReceivedBytes() {
        return receivedBytes;
    }

    public String getCiphertextSha256() {
        return ciphertextSha256;
    }

    public String getPlaintextSha256() {
        return plaintextSha256;
    }

    public String getEncryption() {
        return encryption;
    }

    public String getEscrowKeyId() {
        return escrowKeyId;
    }

    public byte[] getEscrowWrappedKey() {
        return escrowWrappedKey;
    }

    public Integer getRecoveryRkVersion() {
        return recoveryRkVersion;
    }

    public byte[] getRecoveryWrappedKey() {
        return recoveryWrappedKey;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public LocalDateTime getCapturedStartedAt() {
        return capturedStartedAt;
    }

    public LocalDateTime getCapturedEndedAt() {
        return capturedEndedAt;
    }

    public LocalDateTime getReceivedAt() {
        return receivedAt;
    }

    public LocalDateTime getSealedAt() {
        return sealedAt;
    }

    public LocalDateTime getDeletedAt() {
        return deletedAt;
    }

    @Override
    public String toString() {
        // Never print the escrow key copy.
        return "EvidenceItem{id=" + id + ", kind=" + kind + ", status=" + status + ", size=" + sizeBytes + "}";
    }
}
