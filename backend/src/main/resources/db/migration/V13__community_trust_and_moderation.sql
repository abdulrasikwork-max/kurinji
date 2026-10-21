-- Phase 7: Verification, trust and moderation for place signals (docs/PHASE7.md).
-- Design rules:
--   * Trust is PRIVATE and only steers system decisions (weights, what is shown, what is reviewed). Nothing in this
--     migration is ever shown to other people: no scores, levels, badges or counts.
--   * Standing holds COUNTERS only (no places, no times of contributions), and the counters halve every 90 days.
--   * Moderation acts on CONTENT automatically (reversible quarantine) but on PEOPLE only by a human decision, which is
--     written to an append-only audit log and can be appealed to a different moderator.
--   * A community restriction never touches SOS, Safe Walk, the Safety Circle or the Evidence Vault.

-- 1. Roles. Read from the database on every moderation request (never from the token or the client), so a revoked
--    moderator loses access at once. ADMIN is only ever set directly in the database by the operator.
ALTER TABLE users
    ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT 'USER',
    ADD CONSTRAINT ck_users_role CHECK (role IN ('USER', 'MODERATOR', 'ADMIN'));

-- 2. Contributor standing: one private row per person who has taken part (created on first need).
CREATE TABLE contributor_standing (
    user_id          BIGINT      NOT NULL,
    corroborated     INT         NOT NULL DEFAULT 0,   -- own reports that an INDEPENDENT person later confirmed
    refuted          INT         NOT NULL DEFAULT 0,   -- own reports retired without any independent support, or removed as false
    strikes          INT         NOT NULL DEFAULT 0,   -- reports removed by a moderator as targeting or spam
    flags_upheld     INT         NOT NULL DEFAULT 0,   -- own abuse flags a moderator agreed with
    flags_rejected   INT         NOT NULL DEFAULT 0,   -- own abuse flags a moderator dismissed
    restricted_until DATETIME(6) NULL,                 -- UTC; community contributions paused until then (moderator decision)
    decayed_at       DATETIME(6) NOT NULL,             -- UTC; when the counters were last halved

    PRIMARY KEY (user_id),
    CONSTRAINT fk_contributor_standing_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_contributor_standing_counts CHECK (corroborated >= 0 AND refuted >= 0 AND strikes >= 0
        AND flags_upheld >= 0 AND flags_rejected >= 0),
    INDEX idx_contributor_standing_decayed (decayed_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- 3. Moderation state of a signal. QUARANTINED = hidden while it is reviewed; REMOVED = hidden by a moderator, the row
--    stays as a tombstone until moderation_until so a removed fake cannot simply be re-created at once.
ALTER TABLE place_signals
    ADD COLUMN moderation       VARCHAR(20) NOT NULL DEFAULT 'NONE',
    ADD COLUMN moderation_until DATETIME(6) NULL,
    ADD CONSTRAINT ck_place_signals_moderation CHECK (moderation IN ('NONE', 'QUARANTINED', 'REMOVED')),
    ADD CONSTRAINT ck_place_signals_moderation_until CHECK (
        (moderation = 'REMOVED' AND moderation_until IS NOT NULL)
        OR (moderation <> 'REMOVED' AND moderation_until IS NULL));

-- 4. A report is credited to its reporter's standing at most once, when independent support first appears.
ALTER TABLE signal_contributions
    ADD COLUMN credited BOOLEAN NOT NULL DEFAULT FALSE,
    ADD CONSTRAINT ck_signal_contributions_credited CHECK (credited = FALSE OR kind = 'REPORT');

-- 5. Abuse flags ("something is wrong with this signal"). Never shown to anyone; a moderator sees only counts per
--    reason. One flag per person per signal, kept after review (resolved) so the same person cannot flag it again.
CREATE TABLE signal_flags (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    signal_id   BIGINT      NOT NULL,
    user_id     BIGINT      NOT NULL,
    reason      VARCHAR(30) NOT NULL,
    counts_from DATETIME(6) NOT NULL,                    -- UTC; server time, or the end of a new account's probation
    resolved    BOOLEAN     NOT NULL DEFAULT FALSE,

    PRIMARY KEY (id),
    CONSTRAINT uk_signal_flags_signal_user UNIQUE (signal_id, user_id),
    CONSTRAINT fk_signal_flags_signal FOREIGN KEY (signal_id) REFERENCES place_signals (id) ON DELETE CASCADE,
    CONSTRAINT fk_signal_flags_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    INDEX idx_signal_flags_user_time (user_id, counts_from),
    CONSTRAINT ck_signal_flags_reason CHECK (reason IN ('FALSE_INFORMATION', 'TARGETS_SOMEONE', 'SPAM_OR_COORDINATED'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- 6. Review cases: at most one OPEN case per signal. Deleted together with their signal (minimum retention); closed
--    cases are also deleted after app.moderation.closed-case-retention.
CREATE TABLE moderation_cases (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    public_id   CHAR(36)    NOT NULL,
    signal_id   BIGINT      NOT NULL,
    origin      VARCHAR(20) NOT NULL,                    -- FLAGS | REREPORTED | COORDINATION
    status      VARCHAR(10) NOT NULL,                    -- OPEN | CLOSED
    outcome     VARCHAR(20) NULL,                        -- DISMISSED | REMOVED (closed cases only)
    opened_at   DATETIME(6) NOT NULL,
    closed_at   DATETIME(6) NULL,
    version     BIGINT      NOT NULL DEFAULT 0,          -- two moderators acting at once: the second one gets 409

    open_marker TINYINT GENERATED ALWAYS AS (CASE WHEN status = 'OPEN' THEN 1 ELSE NULL END) STORED,

    PRIMARY KEY (id),
    CONSTRAINT uk_moderation_cases_public_id UNIQUE (public_id),
    CONSTRAINT uk_moderation_cases_one_open UNIQUE (signal_id, open_marker),
    CONSTRAINT fk_moderation_cases_signal FOREIGN KEY (signal_id) REFERENCES place_signals (id) ON DELETE CASCADE,
    INDEX idx_moderation_cases_status_opened (status, opened_at),
    CONSTRAINT ck_moderation_cases_origin CHECK (origin IN ('FLAGS', 'REREPORTED', 'COORDINATION')),
    CONSTRAINT ck_moderation_cases_state CHECK (
        (status = 'OPEN' AND outcome IS NULL AND closed_at IS NULL)
        OR (status = 'CLOSED' AND outcome IN ('DISMISSED', 'REMOVED') AND closed_at IS NOT NULL))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- 7. The moderation audit log: append-only (the application never updates or deletes a row except by retention).
--    Reason codes only, no free text (free text invites personal data and opinions about people).
--    actor_id has NO cascade: an account that took moderation decisions cannot be erased together with its trail.
CREATE TABLE moderation_actions (
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    public_id        CHAR(36)    NOT NULL,
    actor_id         BIGINT      NOT NULL,
    action           VARCHAR(30) NOT NULL,
    reason           VARCHAR(30) NOT NULL,
    case_ref         CHAR(36)    NULL,                   -- the case's public id (cases are deleted; the log outlives them)
    signal_category  VARCHAR(30) NULL,
    subject_user_id  BIGINT      NULL,                   -- the person a RESTRICT / appeal / role decision is about
    restricted_until DATETIME(6) NULL,
    new_role         VARCHAR(20) NULL,
    occurred_at      DATETIME(6) NOT NULL,

    PRIMARY KEY (id),
    CONSTRAINT uk_moderation_actions_public_id UNIQUE (public_id),
    CONSTRAINT fk_moderation_actions_actor FOREIGN KEY (actor_id) REFERENCES users (id),
    CONSTRAINT fk_moderation_actions_subject FOREIGN KEY (subject_user_id) REFERENCES users (id) ON DELETE CASCADE,
    INDEX idx_moderation_actions_subject (subject_user_id, action, occurred_at),
    INDEX idx_moderation_actions_time (occurred_at),
    CONSTRAINT ck_moderation_actions_action CHECK (action IN ('QUARANTINE', 'DISMISS', 'REMOVE', 'RESTRICT',
        'APPEAL_UPHELD', 'APPEAL_OVERTURNED', 'ROLE_GRANTED', 'ROLE_REVOKED')),
    CONSTRAINT ck_moderation_actions_reason CHECK (reason IN ('FALSE_INFORMATION', 'TARGETS_SOMEONE',
        'SPAM_OR_COORDINATED', 'DUPLICATE', 'NO_VIOLATION', 'NEEDS_REVIEW', 'APPEAL_REVIEWED', 'ROLE_CHANGE')),
    CONSTRAINT ck_moderation_actions_role CHECK (new_role IS NULL OR new_role IN ('USER', 'MODERATOR'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- 8. Appeals against a restriction: one per decision, decided by a moderator OTHER than the one who restricted.
CREATE TABLE moderation_appeals (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    public_id  CHAR(36)    NOT NULL,
    action_id  BIGINT      NOT NULL,
    user_id    BIGINT      NOT NULL,
    status     VARCHAR(20) NOT NULL,                     -- OPEN | UPHELD | OVERTURNED
    created_at DATETIME(6) NOT NULL,
    decided_at DATETIME(6) NULL,
    decided_by BIGINT      NULL,
    version    BIGINT      NOT NULL DEFAULT 0,           -- two moderators deciding at once: the second one gets 409

    PRIMARY KEY (id),
    CONSTRAINT uk_moderation_appeals_public_id UNIQUE (public_id),
    CONSTRAINT uk_moderation_appeals_action UNIQUE (action_id),
    CONSTRAINT fk_moderation_appeals_action FOREIGN KEY (action_id) REFERENCES moderation_actions (id) ON DELETE CASCADE,
    CONSTRAINT fk_moderation_appeals_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_moderation_appeals_decider FOREIGN KEY (decided_by) REFERENCES users (id),
    INDEX idx_moderation_appeals_status (status, created_at),
    -- decided_by is set together with decided_at in code (MySQL does not allow a CHECK on a foreign-key column here).
    CONSTRAINT ck_moderation_appeals_state CHECK (
        (status = 'OPEN' AND decided_at IS NULL)
        OR (status IN ('UPHELD', 'OVERTURNED') AND decided_at IS NOT NULL))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
