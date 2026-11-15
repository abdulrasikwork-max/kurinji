-- Phase N2 (docs/PHASE_N2.md, decisions N-7, N-21 to N-26): sign-in by phone number and code only; no e-mail, no
-- password; one name per person, the real one.
--
-- * phone_codes replaces phone_challenges (V26). A code can now be sent to a number that has no account yet (sign-in
--   and sign-up are one flow), so a row is keyed by the NUMBER and its purpose, not by an account:
--     SIGN_IN  user_id NULL. After the right code for a number without an account the row holds a sign-up ticket
--              (ticket_hash) instead of a code; POST /api/auth/sign-up consumes it.
--     CONFIRM  user_id = the signed-in person asking for a fresh code before a sensitive action (deletion, cancel,
--              ending an SOS from another sign-in, signing out the other phones).
--   Only SHA-256 hashes of the challenge token and of the ticket, and only an HMAC of the code, are stored.
--   "CONFIRM rows have a user, SIGN_IN rows have none" is enforced in code: MySQL forbids a CHECK on user_id, a column
--   of a cascading foreign key.
-- * The e-mail address, its V25 verification and the password hash leave the users table; V25's link table goes.
-- * profiles.display_name goes: the profile shows users.name (the real name) everywhere.
-- * device_sessions may end with the new reason OTHERS_SIGNED_OUT ("Sign out all other phones", N-26).
--
-- Accounts without a verified phone (created before 17B and never verified) stay as they are, but can no longer sign
-- in or keep a session (the server refuses them: owner's decision N-22). Nothing else is deleted.
-- Cannot be undone: take a backup first (mysqldump on a development database; restore-rehearsal.sh on the pilot).

CREATE TABLE phone_codes (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    purpose          VARCHAR(10)  COLLATE utf8mb4_bin NOT NULL,
    phone_e164       VARCHAR(16)  COLLATE utf8mb4_bin NOT NULL,
    user_id          BIGINT       NULL,
    token_hash       CHAR(64)     COLLATE utf8mb4_bin NOT NULL,
    code_hash        CHAR(64)     COLLATE utf8mb4_bin NULL,
    code_expires_at  DATETIME(6)  NULL,
    attempts         TINYINT      NOT NULL DEFAULT 0,
    sends            TINYINT      NOT NULL DEFAULT 0,
    last_sent_at     DATETIME(6)  NULL,
    ticket_hash      CHAR(64)     COLLATE utf8mb4_bin NULL,
    created_at       DATETIME(6)  NOT NULL,
    expires_at       DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_phone_codes_purpose_phone UNIQUE (purpose, phone_e164),
    CONSTRAINT uk_phone_codes_token UNIQUE (token_hash),
    CONSTRAINT uk_phone_codes_ticket UNIQUE (ticket_hash),
    CONSTRAINT fk_phone_codes_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_phone_codes_purpose CHECK (purpose IN ('SIGN_IN', 'CONFIRM')),
    CONSTRAINT ck_phone_codes_phone CHECK (REGEXP_LIKE(phone_e164, '^\\+91[6-9][0-9]{9}$', 'c')),
    CONSTRAINT ck_phone_codes_token CHECK (REGEXP_LIKE(token_hash, '^[0-9a-f]{64}$', 'c')),
    CONSTRAINT ck_phone_codes_code CHECK ((code_hash IS NULL) = (code_expires_at IS NULL)
        AND (code_hash IS NULL OR (REGEXP_LIKE(code_hash, '^[0-9a-f]{64}$', 'c') AND last_sent_at IS NOT NULL))),
    CONSTRAINT ck_phone_codes_ticket CHECK (ticket_hash IS NULL
        OR (REGEXP_LIKE(ticket_hash, '^[0-9a-f]{64}$', 'c') AND purpose = 'SIGN_IN' AND code_hash IS NULL)),
    CONSTRAINT ck_phone_codes_counts CHECK (attempts BETWEEN 0 AND 20 AND sends BETWEEN 0 AND 20),
    CONSTRAINT ck_phone_codes_times CHECK (expires_at > created_at),
    INDEX idx_phone_codes_expires (expires_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

DROP TABLE phone_challenges;
DROP TABLE email_verification_tokens;

-- Sign-in looks a person up by their verified number.
CREATE INDEX idx_users_phone ON users (phone_e164);

-- MySQL refuses to drop a column that a CHECK or an index still names: those go first.
ALTER TABLE users
    DROP CHECK ck_users_erased_email,
    DROP CHECK ck_users_email_verified_after_created;
ALTER TABLE users
    DROP INDEX idx_users_email_verified_created,
    DROP INDEX uk_users_email;
ALTER TABLE users
    DROP COLUMN email,
    DROP COLUMN email_verified_at,
    DROP COLUMN password_hash;

ALTER TABLE profiles DROP CHECK ck_profiles_display_name;
ALTER TABLE profiles DROP COLUMN display_name;

-- N-26: "Sign out all other phones" ends sessions with its own reason; PASSWORD_CHANGED stays for rows from before N2.
ALTER TABLE device_sessions DROP CHECK ck_device_sessions_reason;
ALTER TABLE device_sessions ADD CONSTRAINT ck_device_sessions_reason CHECK (revoked_reason IS NULL OR revoked_reason IN
    ('SIGNED_OUT', 'PASSWORD_CHANGED', 'OTHERS_SIGNED_OUT', 'REUSE_DETECTED', 'ACCOUNT_DISABLED', 'EXPIRED'));
