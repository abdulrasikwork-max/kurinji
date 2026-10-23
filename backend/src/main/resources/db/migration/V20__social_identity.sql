-- Phase 13A: identity foundation of the experience platform (docs/PHASE13A_PLAN.md, docs/EXPERIENCE_PLATFORM_DESIGN.md).
-- Design rules:
--   * 18+ only (decision D6). The date of birth is checked when it is given and then discarded: only the time the
--     person declared an adult age is kept (users.age_confirmed_at). There is no date-of-birth column on purpose.
--   * A profile is the social face of an account: a unique ASCII handle (no look-alike characters), a display name of
--     the person's choice (the account name stays the real name used in Safety Circle messages), a short bio, closed
--     lists of languages and interests, an optional city. No counts of anything are stored or derived.
--   * A handle can change at most once per 30 days; the previous one stays reserved for 30 days so nobody can take a
--     handle its owner just left and pretend to be them.
--   * Connections are mutual: one row per pair of people (user_low < user_high, enforced in code), whatever the
--     direction of the request. A declined request stays DECLINED and looks like a pending request to its sender.
--   * Invite codes are never stored: only the SHA-256 (lower-case hex) of a 128-bit random code.
--   * Blocks and mutes are private lists of their owner.
--   * MySQL refuses CHECK constraints on columns used by a cascading foreign key (the V6 finding), so "not yourself",
--     "low < high" and "the requester is one of the pair" are enforced in code (ConnectionService, BlockService) and
--     tested there.

ALTER TABLE users
    ADD COLUMN age_confirmed_at DATETIME(6) NULL;   -- UTC; when the person declared a date of birth at least 18 years ago

CREATE TABLE profiles (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    public_id             CHAR(36)     COLLATE utf8mb4_bin NOT NULL,   -- random UUID: the only id clients see
    user_id               BIGINT       NOT NULL,
    handle                VARCHAR(20)  COLLATE utf8mb4_bin NOT NULL,   -- lower-case ASCII
    previous_handle       VARCHAR(20)  COLLATE utf8mb4_bin NULL,       -- reserved until previous_handle_until
    previous_handle_until DATETIME(6)  NULL,
    handle_changed_at     DATETIME(6)  NULL,
    display_name          VARCHAR(40)  NOT NULL,
    bio                   VARCHAR(160) NULL,
    languages             VARCHAR(40)  COLLATE utf8mb4_bin NULL,       -- closed list, comma-separated
    interests             VARCHAR(200) COLLATE utf8mb4_bin NULL,       -- closed list, comma-separated
    city                  VARCHAR(60)  NULL,
    created_at            DATETIME(6)  NOT NULL,
    updated_at            DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_profiles_public_id UNIQUE (public_id),
    CONSTRAINT uk_profiles_user UNIQUE (user_id),
    CONSTRAINT uk_profiles_handle UNIQUE (handle),
    CONSTRAINT uk_profiles_previous_handle UNIQUE (previous_handle),
    CONSTRAINT fk_profiles_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_profiles_public_id CHECK (
        REGEXP_LIKE(public_id, '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$', 'c')),
    CONSTRAINT ck_profiles_handle CHECK (REGEXP_LIKE(handle, '^[a-z][a-z0-9_.]{1,18}[a-z0-9_]$', 'c')
        AND NOT REGEXP_LIKE(handle, '[.][.]', 'c')),
    CONSTRAINT ck_profiles_previous_handle CHECK (previous_handle IS NULL
        OR (REGEXP_LIKE(previous_handle, '^[a-z][a-z0-9_.]{1,18}[a-z0-9_]$', 'c')
            AND NOT REGEXP_LIKE(previous_handle, '[.][.]', 'c'))),
    CONSTRAINT ck_profiles_previous_pair CHECK ((previous_handle IS NULL) = (previous_handle_until IS NULL)),
    CONSTRAINT ck_profiles_previous_not_current CHECK (previous_handle IS NULL OR previous_handle <> handle),
    CONSTRAINT ck_profiles_display_name CHECK (CHAR_LENGTH(TRIM(display_name)) BETWEEN 1 AND 40),
    CONSTRAINT ck_profiles_bio CHECK (bio IS NULL OR CHAR_LENGTH(TRIM(bio)) BETWEEN 1 AND 160),
    CONSTRAINT ck_profiles_city CHECK (city IS NULL OR CHAR_LENGTH(TRIM(city)) BETWEEN 1 AND 60),
    CONSTRAINT ck_profiles_languages CHECK (languages IS NULL OR REGEXP_LIKE(languages,
        '^(ta|en|hi|ml|te|kn)(,(ta|en|hi|ml|te|kn)){0,5}$', 'c')),
    CONSTRAINT ck_profiles_interests CHECK (interests IS NULL OR REGEXP_LIKE(interests,
        '^(FOOD_CAFES|NATURE_OUTDOORS|CULTURE_HERITAGE|MUSIC_NIGHTLIFE|STUDY_WORK|DAY_TRIPS|ACTIVITIES_SPORT|HOBBIES_MAKING|CITY_LIFE|HIDDEN_GEMS)(,(FOOD_CAFES|NATURE_OUTDOORS|CULTURE_HERITAGE|MUSIC_NIGHTLIFE|STUDY_WORK|DAY_TRIPS|ACTIVITIES_SPORT|HOBBIES_MAKING|CITY_LIFE|HIDDEN_GEMS)){0,9}$', 'c')),
    CONSTRAINT ck_profiles_times CHECK (updated_at >= created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE connections (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    public_id    CHAR(36)    COLLATE utf8mb4_bin NOT NULL,
    user_low     BIGINT      NOT NULL,                          -- the smaller user id of the pair
    user_high    BIGINT      NOT NULL,                          -- the larger user id of the pair
    requester_id BIGINT      NOT NULL,                          -- one of the pair (enforced in code)
    status       VARCHAR(10) COLLATE utf8mb4_bin NOT NULL,
    requested_at DATETIME(6) NOT NULL,
    responded_at DATETIME(6) NULL,                              -- set when accepted or declined
    PRIMARY KEY (id),
    CONSTRAINT uk_connections_public_id UNIQUE (public_id),
    CONSTRAINT uk_connections_pair UNIQUE (user_low, user_high),
    CONSTRAINT fk_connections_low FOREIGN KEY (user_low) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_connections_high FOREIGN KEY (user_high) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_connections_public_id CHECK (
        REGEXP_LIKE(public_id, '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$', 'c')),
    CONSTRAINT ck_connections_status CHECK (status IN ('PENDING', 'ACCEPTED', 'DECLINED')),
    CONSTRAINT ck_connections_responded CHECK ((status = 'PENDING') = (responded_at IS NULL)),
    CONSTRAINT ck_connections_times CHECK (responded_at IS NULL OR responded_at >= requested_at),
    INDEX idx_connections_high (user_high)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE connection_invites (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    public_id  CHAR(36)    COLLATE utf8mb4_bin NOT NULL,
    owner_id   BIGINT      NOT NULL,
    code_hash  CHAR(64)    COLLATE utf8mb4_bin NOT NULL,        -- SHA-256 of the code; the code itself is never stored
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    used_at    DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_connection_invites_public_id UNIQUE (public_id),
    CONSTRAINT uk_connection_invites_code UNIQUE (code_hash),
    CONSTRAINT fk_connection_invites_owner FOREIGN KEY (owner_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_connection_invites_public_id CHECK (
        REGEXP_LIKE(public_id, '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$', 'c')),
    CONSTRAINT ck_connection_invites_hash CHECK (REGEXP_LIKE(code_hash, '^[0-9a-f]{64}$', 'c')),
    CONSTRAINT ck_connection_invites_times CHECK (expires_at > created_at
        AND (used_at IS NULL OR used_at >= created_at)),
    INDEX idx_connection_invites_owner (owner_id),
    INDEX idx_connection_invites_expires (expires_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE blocks (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    blocker_id BIGINT      NOT NULL,
    blocked_id BIGINT      NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_blocks_pair UNIQUE (blocker_id, blocked_id),
    CONSTRAINT fk_blocks_blocker FOREIGN KEY (blocker_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_blocks_blocked FOREIGN KEY (blocked_id) REFERENCES users (id) ON DELETE CASCADE,
    INDEX idx_blocks_blocked (blocked_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE mutes (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    muter_id   BIGINT      NOT NULL,
    muted_id   BIGINT      NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_mutes_pair UNIQUE (muter_id, muted_id),
    CONSTRAINT fk_mutes_muter FOREIGN KEY (muter_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_mutes_muted FOREIGN KEY (muted_id) REFERENCES users (id) ON DELETE CASCADE,
    INDEX idx_mutes_muted (muted_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
