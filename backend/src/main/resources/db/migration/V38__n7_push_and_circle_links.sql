-- Phase N7 (docs/PHASE_N7.md, decision D42 = N-134): push notifications and the Safety Circle in the app.
--
-- 1. push_devices: one FCM token per sign-in (device_sessions row). The token is stored only to send; token_hash (SHA-256)
--    keeps a token on one session at a time. Deleted with the session or the person.
-- 2. push_settings: the person's switches (no row = the defaults: everything on except staff).
-- 3. push_messages: the queue. One row per (event, phone). NO text, NO name, NO location: a closed type, an opaque
--    subject id and times. event_id is the same on every phone of one event (it travels as "m" for de-duplication).
-- 4. circle_links: "Also in the IRL app" (D36). A contact of the owner whose number is a verified IRL account, PENDING
--    until that person (the member) accepts. Nothing about it is shown to the owner before ACCEPTED.
-- 5. circle_link_mutes: "Don't ask again" (the member, about one owner).
-- MySQL forbids CHECKs on cascading-FK columns: owner <> member and "the contact belongs to the owner" live in code.
-- Nothing existing changes.

-- 1. push_devices ------------------------------------------------------------------------------------------------------
CREATE TABLE push_devices (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    user_id     BIGINT       NOT NULL,
    session_id  BIGINT       NOT NULL,
    token       VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    token_hash  CHAR(64)     CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    app_version VARCHAR(32)  NULL,
    created_at  DATETIME(6)  NOT NULL,
    updated_at  DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_push_devices_session UNIQUE (session_id),
    CONSTRAINT uk_push_devices_token UNIQUE (token_hash),
    CONSTRAINT fk_push_devices_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_push_devices_session FOREIGN KEY (session_id) REFERENCES device_sessions (id) ON DELETE CASCADE,
    CONSTRAINT ck_push_devices_hash CHECK (token_hash REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT ck_push_devices_token CHECK (CHAR_LENGTH(token) BETWEEN 32 AND 512),
    INDEX idx_push_devices_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- 2. push_settings -----------------------------------------------------------------------------------------------------
CREATE TABLE push_settings (
    user_id       BIGINT      NOT NULL,
    replies       TINYINT(1)  NOT NULL DEFAULT 1,
    plans         TINYINT(1)  NOT NULL DEFAULT 1,
    plan_messages TINYINT(1)  NOT NULL DEFAULT 1,
    people        TINYINT(1)  NOT NULL DEFAULT 1,
    staff         TINYINT(1)  NOT NULL DEFAULT 0,
    updated_at    DATETIME(6) NOT NULL,
    PRIMARY KEY (user_id),
    CONSTRAINT fk_push_settings_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- 3. push_messages -----------------------------------------------------------------------------------------------------
CREATE TABLE push_messages (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    event_id        CHAR(36)    CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    device_id       BIGINT      NOT NULL,
    user_id         BIGINT      NOT NULL,
    type            VARCHAR(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    subject         VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    urgent          TINYINT(1)  NOT NULL,                   -- Android priority HIGH (not "high_priority": a MySQL reserved word)
    ttl_seconds     INT         NOT NULL,
    not_before      DATETIME(6) NOT NULL,                   -- UTC
    expires_at      DATETIME(6) NOT NULL,                   -- UTC; never sent after this
    status          VARCHAR(12) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    attempts        TINYINT     NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NULL,                       -- UTC; set exactly while QUEUED
    sent_at         DATETIME(6) NULL,                       -- UTC; FCM accepted it (not: shown)
    skip_reason     VARCHAR(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    created_at      DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_push_messages_device FOREIGN KEY (device_id) REFERENCES push_devices (id) ON DELETE CASCADE,
    CONSTRAINT fk_push_messages_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_push_messages_type CHECK (type IN ('PLAN_ASK', 'PLAN_ADDED', 'PLAN_MESSAGE', 'PLAN_CALLED_OFF',
        'REPLY_TURN', 'MORE_ADDED', 'PEOPLE_REQUEST', 'ACCOUNT_SIGN_IN', 'MEMBERSHIP_DECIDED', 'CONTENT_DECIDED',
        'CIRCLE_REQUEST', 'CIRCLE_SOS', 'OMW_REMINDER', 'STAFF_WAITING')),
    CONSTRAINT ck_push_messages_status CHECK (status IN ('QUEUED', 'SENT', 'SKIPPED', 'FAILED', 'EXPIRED')),
    CONSTRAINT ck_push_messages_queued CHECK ((status = 'QUEUED') = (next_attempt_at IS NOT NULL)),
    CONSTRAINT ck_push_messages_sent CHECK ((status = 'SENT') = (sent_at IS NOT NULL)),
    CONSTRAINT ck_push_messages_reason CHECK ((status IN ('SKIPPED', 'FAILED', 'EXPIRED')) = (skip_reason IS NOT NULL)),
    CONSTRAINT ck_push_messages_subject CHECK (subject REGEXP '^[A-Za-z0-9-]{1,64}$'),
    CONSTRAINT ck_push_messages_event CHECK (event_id REGEXP '^[0-9a-f-]{36}$'),
    CONSTRAINT ck_push_messages_ttl CHECK (ttl_seconds BETWEEN 60 AND 2419200),
    CONSTRAINT ck_push_messages_attempts CHECK (attempts BETWEEN 0 AND 20),
    INDEX idx_push_messages_queue (status, next_attempt_at),
    INDEX idx_push_messages_user (user_id, type, subject, created_at),
    INDEX idx_push_messages_event (event_id),
    INDEX idx_push_messages_created (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- 4. circle_links ------------------------------------------------------------------------------------------------------
CREATE TABLE circle_links (
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    public_id      CHAR(36)    CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    contact_id     BIGINT      NOT NULL,
    owner_user_id  BIGINT      NOT NULL,
    member_user_id BIGINT      NOT NULL,
    status         VARCHAR(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    created_at     DATETIME(6) NOT NULL,
    decided_at     DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_circle_links_public UNIQUE (public_id),
    CONSTRAINT uk_circle_links_contact UNIQUE (contact_id),
    CONSTRAINT fk_circle_links_contact FOREIGN KEY (contact_id) REFERENCES safety_contacts (id) ON DELETE CASCADE,
    CONSTRAINT fk_circle_links_owner FOREIGN KEY (owner_user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_circle_links_member FOREIGN KEY (member_user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_circle_links_status CHECK (status IN ('PENDING', 'ACCEPTED')),
    CONSTRAINT ck_circle_links_decided CHECK ((status = 'ACCEPTED') = (decided_at IS NOT NULL)),
    INDEX idx_circle_links_member (member_user_id, status),
    INDEX idx_circle_links_owner (owner_user_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- 5. circle_link_mutes -------------------------------------------------------------------------------------------------
CREATE TABLE circle_link_mutes (
    member_user_id BIGINT      NOT NULL,
    owner_user_id  BIGINT      NOT NULL,
    created_at     DATETIME(6) NOT NULL,
    PRIMARY KEY (member_user_id, owner_user_id),
    CONSTRAINT fk_circle_link_mutes_member FOREIGN KEY (member_user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_circle_link_mutes_owner FOREIGN KEY (owner_user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
