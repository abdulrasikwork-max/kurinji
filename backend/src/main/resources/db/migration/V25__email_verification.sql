-- Email ownership verification (docs/EMAIL_VERIFICATION.md).
-- Why: until now anyone could create an account with any e-mail address and use it at once. From V25 a new account
-- starts with an UNVERIFIED e-mail and gets no session until its owner opens the link sent to that address.
-- Design rules:
--   * users.email_verified_at: NULL = not verified, a time = verified. It is its own fact: not the moderation standing,
--     not a pending deletion, not "enabled", not a session.
--   * Existing accounts are treated as verified (set to the migration time). There is no production user base: every
--     existing row is a development or test account created before e-mail could be sent, including the owner's own.
--     Erased tombstones (V16) are left NULL: they can never sign in anyway.
--   * At most ONE live verification token per account (PRIMARY KEY user_id): sending a new e-mail replaces the token,
--     so the previous link stops working. A successful verification deletes the row, so a link works once.
--   * The token itself is NEVER stored: only its SHA-256 (lower-case hex). It is 256 random bits, so a fast hash is
--     enough (the same reasoning as V19's refresh secrets); a stolen table cannot be turned back into links.
--   * No IP address, device or e-mail text is stored.
ALTER TABLE users
    ADD COLUMN email_verified_at DATETIME(6) NULL AFTER age_confirmed_at;

UPDATE users SET email_verified_at = UTC_TIMESTAMP(6) WHERE erased_at IS NULL AND email_verified_at IS NULL;

ALTER TABLE users
    ADD CONSTRAINT ck_users_email_verified_after_created CHECK (email_verified_at IS NULL OR email_verified_at >= created_at),
    -- The clean-up job removes accounts never verified after app.email-verification.unverified-retention.
    ADD INDEX idx_users_email_verified_created (email_verified_at, created_at);

CREATE TABLE email_verification_tokens (
    user_id     BIGINT       NOT NULL,
    token_hash  CHAR(64)     COLLATE utf8mb4_bin NOT NULL,
    created_at  DATETIME(6)  NOT NULL,
    expires_at  DATETIME(6)  NOT NULL,
    PRIMARY KEY (user_id),
    CONSTRAINT uk_email_verification_tokens_hash UNIQUE (token_hash),
    CONSTRAINT fk_email_verification_tokens_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_email_verification_tokens_hash CHECK (REGEXP_LIKE(token_hash, '^[0-9a-f]{64}$', 'c')),
    CONSTRAINT ck_email_verification_tokens_times CHECK (expires_at > created_at),
    INDEX idx_email_verification_tokens_expires (expires_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
