-- Phase 5: Emergency Evidence Vault.
--
-- 1. evidence_items: one encrypted piece of evidence (an audio segment, a photo or a video) captured by the phone
--    during one of the person's emergencies. The phone encrypts BEFORE upload; the server stores only ciphertext
--    (a file on disk named by storage_key, never inside the database) and can never read it: the per-item key is
--    either kept on the phone only, or additionally sealed for the offline vault recovery key (escrow_wrapped_key,
--    RSA-OAEP), whose private half is NOT held by the running server.
--    There is deliberately NO user_id: ownership always goes through the emergency (like emergency_locations).
--    Deletion keeps a tombstone (kind, sizes, hashes, times) so the custody ledger stays verifiable, but removes the
--    stored file and the escrow key copy (crypto-shredding).
CREATE TABLE evidence_items (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    emergency_id        BIGINT        NOT NULL,
    client_ref          CHAR(36)      NOT NULL,           -- UUID made by the phone: re-declaring is idempotent
    kind                VARCHAR(10)   NOT NULL,           -- AUDIO, PHOTO, VIDEO
    media_type          VARCHAR(40)   NOT NULL,           -- of the plaintext: audio/aac, image/jpeg, video/mp4
    sequence_no         INT           NOT NULL,           -- capture order on the phone within this emergency
    recording_ref       CHAR(36)      NULL,               -- groups the segments of one audio recording
    status              VARCHAR(10)   NOT NULL,           -- UPLOADING, SEALED, DELETED
    size_bytes          BIGINT        NOT NULL,           -- ciphertext size declared by the phone
    received_bytes      BIGINT        NOT NULL,           -- how much of it the server has (resumable upload)
    ciphertext_sha256   CHAR(64)      NOT NULL,           -- verified by the server before SEALED
    plaintext_sha256    CHAR(64)      NOT NULL,           -- reported by the phone (the server cannot verify it)
    encryption          VARCHAR(20)   NOT NULL,           -- container format, SSEV1 (AES-256-GCM, chunked)
    escrow_key_id       CHAR(64)      NULL,               -- SHA-256 fingerprint of the vault key used
    escrow_wrapped_key  VARBINARY(1024) NULL,             -- item key sealed with RSA-OAEP for the vault key
    storage_key         CHAR(36)      NULL,               -- random file name on the server; NULL once deleted
    captured_started_at DATETIME(6)   NOT NULL,           -- UTC, phone clock (checked against the emergency)
    captured_ended_at   DATETIME(6)   NOT NULL,           -- UTC, phone clock
    received_at         DATETIME(6)   NOT NULL,           -- UTC, server: when it was declared
    sealed_at           DATETIME(6)   NULL,               -- UTC, server: when the hash was verified
    deleted_at          DATETIME(6)   NULL,               -- UTC, server
    updated_at          DATETIME(6)   NOT NULL,

    PRIMARY KEY (id),
    -- client_ref is the idempotency key. sequence_no is NOT unique on purpose: two phones of the same person (or a
    -- reinstalled app) may both record during one emergency; their numbers can repeat, the capture times order them.
    CONSTRAINT uk_evidence_items_client_ref UNIQUE (client_ref),
    CONSTRAINT uk_evidence_items_storage_key UNIQUE (storage_key),
    CONSTRAINT fk_evidence_items_emergency FOREIGN KEY (emergency_id) REFERENCES emergency_events (id) ON DELETE CASCADE,
    INDEX idx_evidence_items_emergency_captured (emergency_id, captured_started_at, sequence_no),
    CONSTRAINT ck_evidence_items_kind CHECK (kind IN ('AUDIO', 'PHOTO', 'VIDEO')),
    CONSTRAINT ck_evidence_items_status CHECK (status IN ('UPLOADING', 'SEALED', 'DELETED')),
    CONSTRAINT ck_evidence_items_sequence CHECK (sequence_no >= 0),
    CONSTRAINT ck_evidence_items_sizes CHECK (size_bytes > 0 AND received_bytes >= 0 AND received_bytes <= size_bytes),
    CONSTRAINT ck_evidence_items_cipher_hash CHECK (REGEXP_LIKE(ciphertext_sha256, '^[0-9a-f]{64}$', 'c')),
    CONSTRAINT ck_evidence_items_plain_hash CHECK (REGEXP_LIKE(plaintext_sha256, '^[0-9a-f]{64}$', 'c')),
    CONSTRAINT ck_evidence_items_capture_order CHECK (captured_ended_at >= captured_started_at),
    -- Status and the columns that belong to it can never disagree.
    CONSTRAINT ck_evidence_items_sealed CHECK (status <> 'SEALED' OR (sealed_at IS NOT NULL AND received_bytes = size_bytes)),
    CONSTRAINT ck_evidence_items_deleted CHECK ((status = 'DELETED') = (deleted_at IS NOT NULL)),
    CONSTRAINT ck_evidence_items_storage CHECK ((status = 'DELETED') = (storage_key IS NULL)),
    CONSTRAINT ck_evidence_items_shredded CHECK (status <> 'DELETED' OR escrow_wrapped_key IS NULL),
    CONSTRAINT ck_evidence_items_escrow CHECK ((escrow_key_id IS NULL) = (escrow_wrapped_key IS NULL) OR status = 'DELETED')
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- 2. evidence_custody_events: the chain of custody. Append-only and hash-chained PER USER: every row stores the hash
--    of the previous row, so editing, removing or re-ordering any past row breaks every hash after it and is
--    detected by the verification endpoint. Records who did what to which item and when (declared, sealed,
--    downloaded, deleted, failed integrity check). Never updated, never deleted (except with the account).
CREATE TABLE evidence_custody_events (
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    user_id           BIGINT      NOT NULL,
    evidence_id       BIGINT      NOT NULL,
    sequence_no       BIGINT      NOT NULL,             -- 1, 2, 3 ... per user, no gaps
    action            VARCHAR(20) NOT NULL,
    ciphertext_sha256 CHAR(64)    NOT NULL,             -- the item's hash at that moment
    occurred_at       DATETIME(6) NOT NULL,             -- UTC, server clock
    previous_hash     CHAR(64)    NOT NULL,             -- 64 zeros for the first row of a user
    entry_hash        CHAR(64)    NOT NULL,             -- SHA-256 over this row's fields + previous_hash

    PRIMARY KEY (id),
    CONSTRAINT uk_evidence_custody_user_sequence UNIQUE (user_id, sequence_no),
    CONSTRAINT fk_evidence_custody_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_evidence_custody_item FOREIGN KEY (evidence_id) REFERENCES evidence_items (id) ON DELETE CASCADE,
    INDEX idx_evidence_custody_item (evidence_id),
    CONSTRAINT ck_evidence_custody_sequence CHECK (sequence_no >= 1),
    CONSTRAINT ck_evidence_custody_action CHECK (action IN ('DECLARED', 'SEALED', 'INTEGRITY_FAILED', 'DOWNLOADED', 'DELETED')),
    CONSTRAINT ck_evidence_custody_hashes CHECK (REGEXP_LIKE(previous_hash, '^[0-9a-f]{64}$', 'c')
        AND REGEXP_LIKE(entry_hash, '^[0-9a-f]{64}$', 'c'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
