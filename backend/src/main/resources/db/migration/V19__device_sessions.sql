-- Phase 11 C1: device sessions with a rotating refresh secret (docs/PHASE11.md §5).
-- Why: the 24 h access token was renewed only while the app process ran. After a day without the app, the next request
-- answered 401 and the phone signed out, wiping the running SOS and the Safety Circle copy kept for offline SMS. A
-- device session lets the phone get a new access token after any idle time, and it can be revoked on the server.
-- Design rules:
--   * One row per sign-in on one phone. session_id = the token's "sid" claim (random, means nothing outside this row).
--   * The refresh secret itself is NEVER stored: only its SHA-256 (lower-case hex). The secret is 256 random bits, so a
--     fast hash is enough; a stolen table cannot be turned back into secrets.
--   * Every refresh rotates the secret. previous_secret_hash + rotated_at recognise the secret just replaced: shown
--     again within a short grace period it is a lost answer (rotated once more), later it is reuse (session revoked).
--   * authenticated_at = when the password was last typed for this sign-in (the tokens' "auth_time" claim).
--   * expires_at slides with use (app.account.refresh-idle-timeout); revoked rows are kept a while so their access
--     tokens are refused too, then deleted by the clean-up job.
--   * No device name, model, IP or location.
CREATE TABLE device_sessions (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    session_id           CHAR(36)     COLLATE utf8mb4_bin NOT NULL,
    user_id              BIGINT       NOT NULL,
    secret_hash          CHAR(64)     COLLATE utf8mb4_bin NOT NULL,
    previous_secret_hash CHAR(64)     COLLATE utf8mb4_bin NULL,
    rotated_at           DATETIME(6)  NULL,
    authenticated_at     DATETIME(6)  NOT NULL,
    created_at           DATETIME(6)  NOT NULL,
    last_used_at         DATETIME(6)  NOT NULL,
    expires_at           DATETIME(6)  NOT NULL,
    revoked_at           DATETIME(6)  NULL,
    revoked_reason       VARCHAR(20)  COLLATE utf8mb4_bin NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_device_sessions_session_id UNIQUE (session_id),
    CONSTRAINT fk_device_sessions_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_device_sessions_session_format CHECK (
        REGEXP_LIKE(session_id, '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$', 'c')),
    CONSTRAINT ck_device_sessions_secret_hash CHECK (REGEXP_LIKE(secret_hash, '^[0-9a-f]{64}$', 'c')),
    CONSTRAINT ck_device_sessions_previous_hash CHECK (previous_secret_hash IS NULL
        OR REGEXP_LIKE(previous_secret_hash, '^[0-9a-f]{64}$', 'c')),
    CONSTRAINT ck_device_sessions_rotation CHECK ((previous_secret_hash IS NULL) = (rotated_at IS NULL)),
    CONSTRAINT ck_device_sessions_revoked CHECK ((revoked_at IS NULL) = (revoked_reason IS NULL)),
    CONSTRAINT ck_device_sessions_reason CHECK (revoked_reason IS NULL OR revoked_reason IN
        ('SIGNED_OUT', 'PASSWORD_CHANGED', 'REUSE_DETECTED', 'ACCOUNT_DISABLED', 'EXPIRED')),
    CONSTRAINT ck_device_sessions_times CHECK (last_used_at >= created_at AND expires_at > last_used_at),
    INDEX idx_device_sessions_user (user_id),
    INDEX idx_device_sessions_expires (expires_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
