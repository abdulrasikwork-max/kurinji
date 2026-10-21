-- Phase 5: restore evidence on a new phone with a recovery passphrase that ONLY the person knows.
--
-- Key hierarchy (all made on the phone; the server never sees a passphrase or a usable key):
--   passphrase --PBKDF2-HMAC-SHA256(salt, iterations)--> KEK
--   KEK  --AES-256-GCM--> wrapped recovery key (RK, 32 random bytes per person)   = the "envelope", one row below
--   RK   --AES-256-GCM--> wrapped item key (per evidence item)                   = evidence_items.recovery_wrapped_key
-- Changing the passphrase re-wraps only the RK (one row). The server can open nothing without guessing the passphrase.
--
-- 1. evidence_recovery_envelopes: at most one per person (unique user_id). Every value is opaque to the server; the
--    CHECKs only pin the formats the phone produces (GCM: 12-byte IV + 32-byte key + 16-byte tag = 60 bytes).
CREATE TABLE evidence_recovery_envelopes (
    id                    BIGINT         NOT NULL AUTO_INCREMENT,
    user_id               BIGINT         NOT NULL,
    rk_version            INT            NOT NULL,      -- generation of the RK (a future "reset" adds 1)
    envelope_version      INT            NOT NULL,      -- +1 on every passphrase change (optimistic concurrency)
    kdf                   VARCHAR(20)    NOT NULL,      -- 'PBKDF2-SHA256'
    kdf_iterations        INT            NOT NULL,
    kdf_salt              VARBINARY(32)  NOT NULL,
    wrapped_recovery_key  VARBINARY(64)  NOT NULL,
    created_at            DATETIME(6)    NOT NULL,      -- UTC
    updated_at            DATETIME(6)    NOT NULL,      -- UTC: when the passphrase was last set
    last_downloaded_at    DATETIME(6)    NULL,          -- UTC: last time the envelope was handed out (shown to the person)

    PRIMARY KEY (id),
    CONSTRAINT uk_evidence_recovery_user UNIQUE (user_id),
    CONSTRAINT fk_evidence_recovery_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_evidence_recovery_versions CHECK (rk_version >= 1 AND envelope_version >= 1),
    CONSTRAINT ck_evidence_recovery_kdf CHECK (kdf IN ('PBKDF2-SHA256')),
    CONSTRAINT ck_evidence_recovery_iterations CHECK (kdf_iterations BETWEEN 600000 AND 10000000),
    CONSTRAINT ck_evidence_recovery_salt CHECK (LENGTH(kdf_salt) BETWEEN 16 AND 32),
    CONSTRAINT ck_evidence_recovery_wrapped CHECK (LENGTH(wrapped_recovery_key) = 60)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- 2. Wrong account passwords given to the recovery endpoints (fetching or replacing the envelope). Counted to lock
--    them after repeated failures, so a stolen login token cannot be turned into offline-guessing material.
--    Not part of the custody ledger: those rows always belong to one evidence item.
CREATE TABLE evidence_recovery_failures (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    user_id      BIGINT       NOT NULL,
    occurred_at  DATETIME(6)  NOT NULL,                 -- UTC

    PRIMARY KEY (id),
    CONSTRAINT fk_evidence_recovery_failures_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    INDEX idx_evidence_recovery_failures_user_time (user_id, occurred_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- 3. The item key wrapped with the RK (both columns or neither). Attachable after declaration (back-fill).
ALTER TABLE evidence_items
    ADD COLUMN recovery_rk_version  INT            NULL AFTER escrow_wrapped_key,
    ADD COLUMN recovery_wrapped_key VARBINARY(64)  NULL AFTER recovery_rk_version;

ALTER TABLE evidence_items
    ADD CONSTRAINT ck_evidence_items_recovery CHECK ((recovery_rk_version IS NULL) = (recovery_wrapped_key IS NULL)),
    ADD CONSTRAINT ck_evidence_items_recovery_len CHECK (recovery_wrapped_key IS NULL OR LENGTH(recovery_wrapped_key) = 60);

-- 4. Crypto-shredding now covers the recovery copy too: a DELETED item keeps neither wrapped key.
ALTER TABLE evidence_items DROP CHECK ck_evidence_items_shredded;

ALTER TABLE evidence_items
    ADD CONSTRAINT ck_evidence_items_shredded CHECK (status <> 'DELETED'
        OR (escrow_wrapped_key IS NULL AND recovery_wrapped_key IS NULL AND recovery_rk_version IS NULL));
