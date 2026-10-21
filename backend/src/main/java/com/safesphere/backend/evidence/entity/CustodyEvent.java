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
 * One row of the hash-chained chain-of-custody ledger (migration V9). Immutable: every column is insert-only, and
 * there is no setter. [entryHash] = SHA-256 over the row's fields and [previousHash] (see CustodyLedger.hashOf).
 */
@Entity
@Table(name = "evidence_custody_events")
public class CustodyEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "evidence_id", nullable = false, updatable = false)
    private Long evidenceId;

    @Column(name = "sequence_no", nullable = false, updatable = false)
    private long sequenceNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private CustodyAction action;

    @Column(name = "ciphertext_sha256", nullable = false, updatable = false, length = 64)
    private String ciphertextSha256;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private LocalDateTime occurredAt;

    @Column(name = "previous_hash", nullable = false, updatable = false, length = 64)
    private String previousHash;

    @Column(name = "entry_hash", nullable = false, updatable = false, length = 64)
    private String entryHash;

    /** Required by JPA. */
    protected CustodyEvent() {
    }

    public CustodyEvent(long userId, long evidenceId, long sequenceNo, CustodyAction action, String ciphertextSha256,
                        LocalDateTime occurredAt, String previousHash, String entryHash) {
        this.userId = userId;
        this.evidenceId = evidenceId;
        this.sequenceNo = sequenceNo;
        this.action = action;
        this.ciphertextSha256 = ciphertextSha256;
        this.occurredAt = occurredAt;
        this.previousHash = previousHash;
        this.entryHash = entryHash;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getEvidenceId() {
        return evidenceId;
    }

    public long getSequenceNo() {
        return sequenceNo;
    }

    public CustodyAction getAction() {
        return action;
    }

    public String getCiphertextSha256() {
        return ciphertextSha256;
    }

    public LocalDateTime getOccurredAt() {
        return occurredAt;
    }

    public String getPreviousHash() {
        return previousHash;
    }

    public String getEntryHash() {
        return entryHash;
    }
}
