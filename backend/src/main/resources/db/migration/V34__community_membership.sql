-- Phase N3 (docs/PHASE_N3.md, decisions N-2 to N-6, N-24, N-25, N-27 onwards): the verified MIET community.
--
-- * communities           a community IRL recognises (nobody owns it). Seeded with MIET; no endpoint creates one.
-- * memberships           one per person (one community per person at launch). The college roll number is kept ONLY as
--                         an HMAC (roll_key, the key lives outside the database: COMMUNITY_SECRET), so the database
--                         alone never reveals a roll number. One account per roll number per community (unique).
--                         Ends at ends_at (31 July of the graduation year, end of the day in India) or earlier by an
--                         admin or a ban (ended_at + end_reason). verifier = a verifier of this community.
-- * verification_requests one open request per person, in person (a short code, only its HMAC) OR by an ID photo (the
--                         encrypted file's random name; the image is deleted at the decision). Deleted at the decision.
-- * number_relinks        N-25: a new number waiting for an admin to move a member's account to it (in person).
-- * bans                  a ban on a roll number and/or a phone number, both only as HMACs (it survives the person's
--                         account deletion without keeping a readable number). Never touches the account or safety.
-- * community_actions     the log of every decision (who, about whom, what, why, when).
-- * users.name_checked_at the real name was checked against the college ID (N-24: locked from then on).
--
-- MySQL forbids a CHECK on a column of a foreign key with a referential action; the rules on those columns
-- ("claimed_by and claimed_at together", "lifted_by and lifted_at together") are enforced in code. The deciding
-- accounts (verified_by, banned_by, lifted_by, actor_id) have no cascade on purpose: like the moderation audit log, an
-- account that decided something becomes an anonymous tombstone when it is deleted (AccountEraser).
-- Nothing existing is changed except the new users column. Development database first; the pilot stays on V30.

ALTER TABLE users ADD COLUMN name_checked_at DATETIME(6) NULL;
ALTER TABLE users ADD CONSTRAINT ck_users_name_checked
    CHECK (name_checked_at IS NULL OR (erased_at IS NULL AND name_checked_at >= created_at));

CREATE TABLE communities (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    code        VARCHAR(16)  COLLATE utf8mb4_bin NOT NULL,
    name        VARCHAR(100) NOT NULL,
    created_at  DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_communities_code UNIQUE (code),
    CONSTRAINT ck_communities_code CHECK (REGEXP_LIKE(code, '^[A-Z]{2,16}$', 'c')),
    CONSTRAINT ck_communities_name CHECK (CHAR_LENGTH(TRIM(name)) BETWEEN 2 AND 100)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

INSERT INTO communities (code, name, created_at) VALUES ('MIET', 'MIET Engineering College', UTC_TIMESTAMP(6));

CREATE TABLE memberships (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    user_id          BIGINT       NOT NULL,
    community_id     BIGINT       NOT NULL,
    roll_key         CHAR(64)     COLLATE utf8mb4_bin NOT NULL,
    programme        VARCHAR(12)  COLLATE utf8mb4_bin NOT NULL,
    graduation_year  SMALLINT     NOT NULL,
    method           VARCHAR(10)  COLLATE utf8mb4_bin NOT NULL,
    verified_by      BIGINT       NOT NULL,
    verified_at      DATETIME(6)  NOT NULL,
    ends_at          DATETIME(6)  NOT NULL,
    ended_at         DATETIME(6)  NULL,
    end_reason       VARCHAR(10)  COLLATE utf8mb4_bin NULL,
    verifier         TINYINT(1)   NOT NULL DEFAULT 0,
    verifier_since   DATETIME(6)  NULL,
    updated_at       DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_memberships_user UNIQUE (user_id),
    CONSTRAINT uk_memberships_roll UNIQUE (community_id, roll_key),
    CONSTRAINT fk_memberships_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_memberships_community FOREIGN KEY (community_id) REFERENCES communities (id),
    CONSTRAINT fk_memberships_verified_by FOREIGN KEY (verified_by) REFERENCES users (id),
    CONSTRAINT ck_memberships_roll_key CHECK (REGEXP_LIKE(roll_key, '^[0-9a-f]{64}$', 'c')),
    CONSTRAINT ck_memberships_programme CHECK (programme IN ('BE_CSE', 'BTECH_IT', 'BE_MECH', 'BE_EEE', 'BE_ECE',
        'BTECH_AIDS', 'BE_CIVIL', 'BE_BME', 'ME_STRUCT', 'ME_MANUF', 'ME_PED', 'ME_VLSI', 'ME_CSE', 'MBA')),
    CONSTRAINT ck_memberships_year CHECK (graduation_year BETWEEN 2000 AND 2100),
    CONSTRAINT ck_memberships_method CHECK (method IN ('IN_PERSON', 'ID_PHOTO')),
    CONSTRAINT ck_memberships_times CHECK (ends_at > verified_at AND updated_at >= verified_at),
    CONSTRAINT ck_memberships_end CHECK ((ended_at IS NULL) = (end_reason IS NULL)
        AND COALESCE(end_reason, 'GRADUATED') IN ('GRADUATED', 'REMOVED', 'BANNED')
        AND (ended_at IS NULL OR ended_at >= verified_at)),
    CONSTRAINT ck_memberships_verifier CHECK (verifier IN (0, 1) AND (verifier = 0) = (verifier_since IS NULL)
        AND (verifier = 0 OR ended_at IS NULL)),
    INDEX idx_memberships_ends (ends_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE verification_requests (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    public_id          CHAR(36)     COLLATE utf8mb4_bin NOT NULL,
    user_id            BIGINT       NOT NULL,
    community_id       BIGINT       NOT NULL,
    method             VARCHAR(10)  COLLATE utf8mb4_bin NOT NULL,
    roll_key           CHAR(64)     COLLATE utf8mb4_bin NOT NULL,
    programme          VARCHAR(12)  COLLATE utf8mb4_bin NOT NULL,
    graduation_year    SMALLINT     NOT NULL,
    code_hash          CHAR(64)     COLLATE utf8mb4_bin NULL,
    code_expires_at    DATETIME(6)  NULL,
    photo_key          CHAR(36)     COLLATE utf8mb4_bin NULL,
    photo_uploaded_at  DATETIME(6)  NULL,
    claimed_by         BIGINT       NULL,
    claimed_at         DATETIME(6)  NULL,
    created_at         DATETIME(6)  NOT NULL,
    expires_at         DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_verification_requests_public UNIQUE (public_id),
    CONSTRAINT uk_verification_requests_user UNIQUE (user_id),
    CONSTRAINT uk_verification_requests_code UNIQUE (code_hash),
    CONSTRAINT uk_verification_requests_photo UNIQUE (photo_key),
    CONSTRAINT fk_verification_requests_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_verification_requests_community FOREIGN KEY (community_id) REFERENCES communities (id),
    CONSTRAINT fk_verification_requests_claimed_by FOREIGN KEY (claimed_by) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT ck_verification_requests_public CHECK
        (REGEXP_LIKE(public_id, '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$', 'c')),
    CONSTRAINT ck_verification_requests_method CHECK (method IN ('IN_PERSON', 'ID_PHOTO')),
    CONSTRAINT ck_verification_requests_roll_key CHECK (REGEXP_LIKE(roll_key, '^[0-9a-f]{64}$', 'c')),
    CONSTRAINT ck_verification_requests_programme CHECK (programme IN ('BE_CSE', 'BTECH_IT', 'BE_MECH', 'BE_EEE',
        'BE_ECE', 'BTECH_AIDS', 'BE_CIVIL', 'BE_BME', 'ME_STRUCT', 'ME_MANUF', 'ME_PED', 'ME_VLSI', 'ME_CSE', 'MBA')),
    CONSTRAINT ck_verification_requests_year CHECK (graduation_year BETWEEN 2000 AND 2100),
    CONSTRAINT ck_verification_requests_code CHECK ((code_hash IS NULL) = (code_expires_at IS NULL)
        AND (code_hash IS NULL OR (method = 'IN_PERSON' AND REGEXP_LIKE(code_hash, '^[0-9a-f]{64}$', 'c')))),
    CONSTRAINT ck_verification_requests_photo CHECK ((photo_key IS NULL) = (photo_uploaded_at IS NULL)
        AND (photo_key IS NULL OR (method = 'ID_PHOTO'
            AND REGEXP_LIKE(photo_key, '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$', 'c')))),
    CONSTRAINT ck_verification_requests_times CHECK (expires_at > created_at),
    INDEX idx_verification_requests_expires (expires_at),
    INDEX idx_verification_requests_queue (method, photo_uploaded_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE number_relinks (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    community_id  BIGINT       NOT NULL,
    phone_e164    VARCHAR(16)  COLLATE utf8mb4_bin NOT NULL,
    roll_key      CHAR(64)     COLLATE utf8mb4_bin NOT NULL,
    code_hash     CHAR(64)     COLLATE utf8mb4_bin NOT NULL,
    created_at    DATETIME(6)  NOT NULL,
    expires_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_number_relinks_phone UNIQUE (phone_e164),
    CONSTRAINT uk_number_relinks_code UNIQUE (code_hash),
    CONSTRAINT fk_number_relinks_community FOREIGN KEY (community_id) REFERENCES communities (id),
    CONSTRAINT ck_number_relinks_phone CHECK (REGEXP_LIKE(phone_e164, '^\\+91[6-9][0-9]{9}$', 'c')),
    CONSTRAINT ck_number_relinks_roll_key CHECK (REGEXP_LIKE(roll_key, '^[0-9a-f]{64}$', 'c')),
    CONSTRAINT ck_number_relinks_code CHECK (REGEXP_LIKE(code_hash, '^[0-9a-f]{64}$', 'c')),
    CONSTRAINT ck_number_relinks_times CHECK (expires_at > created_at),
    INDEX idx_number_relinks_expires (expires_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE bans (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    community_id  BIGINT       NOT NULL,
    roll_key      CHAR(64)     COLLATE utf8mb4_bin NULL,
    phone_key     CHAR(64)     COLLATE utf8mb4_bin NULL,
    reason        VARCHAR(20)  COLLATE utf8mb4_bin NOT NULL,
    note          VARCHAR(200) NULL,
    banned_by     BIGINT       NOT NULL,
    created_at    DATETIME(6)  NOT NULL,
    lifted_by     BIGINT       NULL,
    lifted_at     DATETIME(6)  NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_bans_community FOREIGN KEY (community_id) REFERENCES communities (id),
    CONSTRAINT fk_bans_banned_by FOREIGN KEY (banned_by) REFERENCES users (id),
    CONSTRAINT fk_bans_lifted_by FOREIGN KEY (lifted_by) REFERENCES users (id),
    CONSTRAINT ck_bans_keys CHECK ((roll_key IS NOT NULL OR phone_key IS NOT NULL)
        AND (roll_key IS NULL OR REGEXP_LIKE(roll_key, '^[0-9a-f]{64}$', 'c'))
        AND (phone_key IS NULL OR REGEXP_LIKE(phone_key, '^[0-9a-f]{64}$', 'c'))),
    CONSTRAINT ck_bans_reason CHECK (reason IN ('HARASSMENT', 'HATE', 'THREATS', 'SEXUAL_CONTENT', 'IMPERSONATION',
        'FALSE_ID', 'PRIVACY', 'REPEATED', 'OTHER')),
    CONSTRAINT ck_bans_note CHECK (note IS NULL OR CHAR_LENGTH(TRIM(note)) BETWEEN 1 AND 200),
    CONSTRAINT ck_bans_lifted CHECK (lifted_at IS NULL OR lifted_at >= created_at),
    INDEX idx_bans_roll (roll_key),
    INDEX idx_bans_phone (phone_key)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE community_actions (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    community_id     BIGINT       NOT NULL,
    actor_id         BIGINT       NULL,
    subject_user_id  BIGINT       NULL,
    action           VARCHAR(20)  COLLATE utf8mb4_bin NOT NULL,
    method           VARCHAR(10)  COLLATE utf8mb4_bin NULL,
    reason           VARCHAR(20)  COLLATE utf8mb4_bin NULL,
    detail           VARCHAR(100) NULL,
    created_at       DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_community_actions_community FOREIGN KEY (community_id) REFERENCES communities (id),
    CONSTRAINT fk_community_actions_actor FOREIGN KEY (actor_id) REFERENCES users (id),
    CONSTRAINT fk_community_actions_subject FOREIGN KEY (subject_user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_community_actions_action CHECK (action IN ('VERIFIED', 'REFUSED', 'NAME_CORRECTED', 'VERIFIER_ADDED',
        'VERIFIER_REMOVED', 'ENDED', 'BANNED', 'BAN_LIFTED', 'RELINKED')),
    CONSTRAINT ck_community_actions_method CHECK (COALESCE(method, 'IN_PERSON') IN ('IN_PERSON', 'ID_PHOTO')),
    CONSTRAINT ck_community_actions_reason CHECK (COALESCE(reason, 'OTHER') IN ('NAME_MISMATCH', 'ROLL_MISMATCH',
        'NOT_THE_PERSON', 'NOT_MIET_ID', 'UNREADABLE', 'ALREADY_MEMBER', 'BANNED', 'OTHER', 'GRADUATED', 'REMOVED',
        'HARASSMENT', 'HATE', 'THREATS', 'SEXUAL_CONTENT', 'IMPERSONATION', 'FALSE_ID', 'PRIVACY', 'REPEATED')),
    CONSTRAINT ck_community_actions_detail CHECK ((action = 'NAME_CORRECTED') = (detail IS NOT NULL)),
    INDEX idx_community_actions_subject (subject_user_id, created_at),
    INDEX idx_community_actions_actor (actor_id, action, created_at),
    INDEX idx_community_actions_created (created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
