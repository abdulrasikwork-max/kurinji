-- Phone verification (Phase 17B, docs/PHASE17B.md). Replaces V25 e-mail links in the sign-up flow.
-- Why: no mail server is configured, so V25 sign-ups waited for an e-mail that was never sent and could never sign in.
-- Design rules:
--   * users.phone_e164: the account's mobile number as +91 and 10 digits (India only for now, decision D-1 / Fast2SMS).
--     users.phone_verified_at: NULL = not verified. A new account gets no session until it is set.
--   * ONE verified account per number: verified_phone is the number only while it is verified, and it is UNIQUE
--     (NULLs do not clash), so two sign-ups racing for one number cannot both finish.
--   * phone_challenges: at most ONE per account (PRIMARY KEY user_id). The app holds a 256-bit challenge token; only its
--     SHA-256 is stored. The 6-digit code is NEVER stored: only HMAC-SHA256(server key, token hash | code).
--     No IP address, device or message text is stored.
--   * Accounts from before V26 keep phone_e164 NULL and verify a phone at their next sign-in (their sessions keep
--     working); V25's email_verified_at keeps meaning "this account was activated before V26".
ALTER TABLE users
    ADD COLUMN phone_e164 VARCHAR(16) COLLATE utf8mb4_bin NULL AFTER email_verified_at,
    ADD COLUMN phone_verified_at DATETIME(6) NULL AFTER phone_e164,
    ADD COLUMN verified_phone VARCHAR(16) COLLATE utf8mb4_bin
        GENERATED ALWAYS AS (IF(phone_verified_at IS NULL, NULL, phone_e164)) STORED AFTER phone_verified_at,
    ADD CONSTRAINT uk_users_verified_phone UNIQUE (verified_phone),
    ADD CONSTRAINT ck_users_phone_format CHECK (phone_e164 IS NULL OR REGEXP_LIKE(phone_e164, '^\\+91[6-9][0-9]{9}$', 'c')),
    ADD CONSTRAINT ck_users_phone_verified_has_number CHECK (phone_verified_at IS NULL OR phone_e164 IS NOT NULL),
    ADD CONSTRAINT ck_users_phone_verified_after_created CHECK (phone_verified_at IS NULL OR phone_verified_at >= created_at),
    -- An anonymised tombstone (V16) keeps no number.
    ADD CONSTRAINT ck_users_erased_no_phone CHECK (erased_at IS NULL OR phone_e164 IS NULL);

CREATE TABLE phone_challenges (
    user_id          BIGINT       NOT NULL,
    token_hash       CHAR(64)     COLLATE utf8mb4_bin NOT NULL,
    phone_e164       VARCHAR(16)  COLLATE utf8mb4_bin NULL,
    code_hash        CHAR(64)     COLLATE utf8mb4_bin NULL,
    code_expires_at  DATETIME(6)  NULL,
    attempts         TINYINT      NOT NULL DEFAULT 0,
    sends            TINYINT      NOT NULL DEFAULT 0,
    last_sent_at     DATETIME(6)  NULL,
    created_at       DATETIME(6)  NOT NULL,
    expires_at       DATETIME(6)  NOT NULL,
    PRIMARY KEY (user_id),
    CONSTRAINT uk_phone_challenges_token UNIQUE (token_hash),
    CONSTRAINT fk_phone_challenges_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_phone_challenges_token CHECK (REGEXP_LIKE(token_hash, '^[0-9a-f]{64}$', 'c')),
    CONSTRAINT ck_phone_challenges_phone CHECK (phone_e164 IS NULL OR REGEXP_LIKE(phone_e164, '^\\+91[6-9][0-9]{9}$', 'c')),
    -- A code exists only together with its expiry, its send time and a number to send it to.
    CONSTRAINT ck_phone_challenges_code CHECK ((code_hash IS NULL) = (code_expires_at IS NULL)
        AND (code_hash IS NULL OR (REGEXP_LIKE(code_hash, '^[0-9a-f]{64}$', 'c') AND phone_e164 IS NOT NULL
             AND last_sent_at IS NOT NULL))),
    CONSTRAINT ck_phone_challenges_counts CHECK (attempts BETWEEN 0 AND 20 AND sends BETWEEN 0 AND 20),
    CONSTRAINT ck_phone_challenges_times CHECK (expires_at > created_at),
    INDEX idx_phone_challenges_expires (expires_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
