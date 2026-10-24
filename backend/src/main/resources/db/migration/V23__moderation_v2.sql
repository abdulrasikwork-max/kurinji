-- Phase 13D: moderation v2 (docs/PHASE13D_PLAN.md, docs/EXPERIENCE_PLATFORM_DESIGN.md §8, §4.5-§4.6, §6.1).
-- Design rules:
--   * Reports never reach the reported person: no table returns a reporter to anyone but a moderator, and then only as
--     a case-scoped pseudonym (CaseRefs). Nothing here stores a count that anyone else sees.
--   * Content can be hidden automatically only reversibly (COLLAPSED by independent reports); HELD and REMOVED are a
--     moderator's decision (moderation_decision = the audit row's public id). People are restricted only by a human.
--   * Content cases are a SIBLING of the Phase 7 signal cases (moderation_cases stays as it is: Phase 7/9 code reads
--     it). The audit log and the appeals table are shared (new codes; target_kind NULL = a Phase 7 signal decision).
--   * Strikes store the weight and the time; their decay (half-life 90 days) is computed, never written back.
--   * An edit is kept (post_revisions) only while a case about the post is open (posts.in_review), for the moderator.
--   * MySQL refuses CHECK constraints on columns used by a cascading foreign key (the V6 finding): "exactly one target"
--     (content_cases, content_reports) and "not your own content" are enforced in code and tested there.

-- 1. Moderation state of posts and perspectives (social reads only these columns; it never reads the cases).
ALTER TABLE posts
    ADD COLUMN moderation          VARCHAR(9)  COLLATE utf8mb4_bin NOT NULL DEFAULT 'NONE',
    ADD COLUMN moderation_reason   VARCHAR(20) COLLATE utf8mb4_bin NULL,
    ADD COLUMN moderation_decision CHAR(36)    COLLATE utf8mb4_bin NULL,
    ADD COLUMN in_review           BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN approve_first       BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD CONSTRAINT ck_posts_moderation CHECK (moderation IN ('NONE', 'COLLAPSED', 'HELD', 'REMOVED')),
    ADD CONSTRAINT ck_posts_moderation_reason CHECK (moderation_reason IS NULL OR moderation_reason IN ('HARASSMENT',
        'SEXUAL_HARASSMENT', 'HATE', 'THREAT', 'DOXXING', 'SPAM', 'NUDITY_OR_VIOLENCE', 'IMPERSONATION', 'OTHER',
        'NEEDS_REVIEW')),
    ADD CONSTRAINT ck_posts_moderation_decision CHECK (moderation_decision IS NULL OR
        REGEXP_LIKE(moderation_decision, '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$', 'c')),
    ADD CONSTRAINT ck_posts_moderation_state CHECK (
        (moderation = 'NONE' AND moderation_reason IS NULL AND moderation_decision IS NULL)
        OR (moderation = 'COLLAPSED' AND (moderation_reason IS NULL) = (moderation_decision IS NULL))
        OR (moderation IN ('HELD', 'REMOVED') AND moderation_reason IS NOT NULL AND moderation_decision IS NOT NULL)),
    ADD CONSTRAINT ck_posts_approve_first CHECK (approve_first = FALSE OR comment_policy = 'EVERYONE');

ALTER TABLE comments
    ADD COLUMN moderation          VARCHAR(9)  COLLATE utf8mb4_bin NOT NULL DEFAULT 'NONE',
    ADD COLUMN moderation_reason   VARCHAR(20) COLLATE utf8mb4_bin NULL,
    ADD COLUMN moderation_decision CHAR(36)    COLLATE utf8mb4_bin NULL,
    ADD COLUMN awaiting_approval   BOOLEAN     NOT NULL DEFAULT FALSE,   -- Q6: the post's author approves it first
    ADD CONSTRAINT ck_comments_moderation CHECK (moderation IN ('NONE', 'COLLAPSED', 'HELD', 'REMOVED')),
    ADD CONSTRAINT ck_comments_moderation_reason CHECK (moderation_reason IS NULL OR moderation_reason IN (
        'HARASSMENT', 'SEXUAL_HARASSMENT', 'HATE', 'THREAT', 'DOXXING', 'SPAM', 'NUDITY_OR_VIOLENCE', 'IMPERSONATION',
        'OTHER', 'NEEDS_REVIEW')),
    ADD CONSTRAINT ck_comments_moderation_decision CHECK (moderation_decision IS NULL OR
        REGEXP_LIKE(moderation_decision, '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$', 'c')),
    ADD CONSTRAINT ck_comments_moderation_state CHECK (
        (moderation = 'NONE' AND moderation_reason IS NULL AND moderation_decision IS NULL)
        OR (moderation = 'COLLAPSED' AND (moderation_reason IS NULL) = (moderation_decision IS NULL))
        OR (moderation IN ('HELD', 'REMOVED') AND moderation_reason IS NOT NULL AND moderation_decision IS NOT NULL));

-- 2. Q7: the text a post had before an edit made while a case about it was open. Seen by moderators only; deleted when
--    the case closes (code) or with the post.
CREATE TABLE post_revisions (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    post_id     BIGINT       NOT NULL,
    body        TEXT         NOT NULL,
    tip         VARCHAR(300) NULL,
    replaced_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_post_revisions_post FOREIGN KEY (post_id) REFERENCES posts (id) ON DELETE CASCADE,
    CONSTRAINT ck_post_revisions_body CHECK (CHAR_LENGTH(body) BETWEEN 1 AND 5000),
    CONSTRAINT ck_post_revisions_tip CHECK (tip IS NULL OR CHAR_LENGTH(tip) BETWEEN 1 AND 300),
    INDEX idx_post_revisions_post (post_id, replaced_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- 3. Review cases about a post, a perspective or a person: at most one OPEN case per target. Deleted with their
--    target (minimum retention); closed cases are deleted after app.moderation.closed-case-retention.
CREATE TABLE content_cases (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    public_id   CHAR(36)    COLLATE utf8mb4_bin NOT NULL,
    target_kind VARCHAR(7)  COLLATE utf8mb4_bin NOT NULL,
    post_id     BIGINT      NULL,                                -- exactly one of the three targets (code)
    comment_id  BIGINT      NULL,
    person_id   BIGINT      NULL,
    status      VARCHAR(6)  COLLATE utf8mb4_bin NOT NULL,
    outcome     VARCHAR(12) COLLATE utf8mb4_bin NULL,
    opened_at   DATETIME(6) NOT NULL,
    closed_at   DATETIME(6) NULL,
    version     BIGINT      NOT NULL DEFAULT 0,                  -- two moderators acting at once: the second gets 409

    open_marker TINYINT GENERATED ALWAYS AS (CASE WHEN status = 'OPEN' THEN 1 ELSE NULL END) STORED,

    PRIMARY KEY (id),
    CONSTRAINT uk_content_cases_public_id UNIQUE (public_id),
    CONSTRAINT uk_content_cases_open_post UNIQUE (post_id, open_marker),
    CONSTRAINT uk_content_cases_open_comment UNIQUE (comment_id, open_marker),
    CONSTRAINT uk_content_cases_open_person UNIQUE (person_id, open_marker),
    CONSTRAINT fk_content_cases_post FOREIGN KEY (post_id) REFERENCES posts (id) ON DELETE CASCADE,
    CONSTRAINT fk_content_cases_comment FOREIGN KEY (comment_id) REFERENCES comments (id) ON DELETE CASCADE,
    CONSTRAINT fk_content_cases_person FOREIGN KEY (person_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_content_cases_public_id CHECK (
        REGEXP_LIKE(public_id, '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$', 'c')),
    CONSTRAINT ck_content_cases_target_kind CHECK (target_kind IN ('POST', 'COMMENT', 'PERSON')),
    -- "outcome IS NOT NULL" is written out: a NULL outcome makes "outcome IN (...)" UNKNOWN, and MySQL lets a CHECK
    -- that is UNKNOWN pass (found in the V23 dry run).
    CONSTRAINT ck_content_cases_state CHECK (
        (status = 'OPEN' AND outcome IS NULL AND closed_at IS NULL)
        OR (status = 'CLOSED' AND outcome IS NOT NULL AND outcome IN ('NO_VIOLATION', 'ACTION_TAKEN')
            AND closed_at IS NOT NULL AND closed_at >= opened_at)),
    INDEX idx_content_cases_status_opened (status, opened_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- 4. Reports: one per person per target (kept after review, so the same person cannot report the same thing again
--    until the case is deleted). counts_from = when the report starts to count (later for accounts on probation).
--    The note exists only for OTHER. Shown only to moderators, with the reporter as a case pseudonym.
CREATE TABLE content_reports (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    public_id   CHAR(36)     COLLATE utf8mb4_bin NOT NULL,
    case_id     BIGINT       NOT NULL,
    reporter_id BIGINT       NOT NULL,
    post_id     BIGINT       NULL,                               -- the case's target again, for the UNIQUE rules
    comment_id  BIGINT       NULL,
    person_id   BIGINT       NULL,
    reason      VARCHAR(20)  COLLATE utf8mb4_bin NOT NULL,
    note        VARCHAR(200) NULL,
    created_at  DATETIME(6)  NOT NULL,
    counts_from DATETIME(6)  NOT NULL,
    resolved    BOOLEAN      NOT NULL DEFAULT FALSE,
    PRIMARY KEY (id),
    CONSTRAINT uk_content_reports_public_id UNIQUE (public_id),
    CONSTRAINT uk_content_reports_post UNIQUE (reporter_id, post_id),
    CONSTRAINT uk_content_reports_comment UNIQUE (reporter_id, comment_id),
    CONSTRAINT uk_content_reports_person UNIQUE (reporter_id, person_id),
    CONSTRAINT fk_content_reports_case FOREIGN KEY (case_id) REFERENCES content_cases (id) ON DELETE CASCADE,
    CONSTRAINT fk_content_reports_reporter FOREIGN KEY (reporter_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_content_reports_post FOREIGN KEY (post_id) REFERENCES posts (id) ON DELETE CASCADE,
    CONSTRAINT fk_content_reports_comment FOREIGN KEY (comment_id) REFERENCES comments (id) ON DELETE CASCADE,
    CONSTRAINT fk_content_reports_person FOREIGN KEY (person_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_content_reports_public_id CHECK (
        REGEXP_LIKE(public_id, '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$', 'c')),
    CONSTRAINT ck_content_reports_reason CHECK (reason IN ('HARASSMENT', 'SEXUAL_HARASSMENT', 'HATE', 'THREAT',
        'DOXXING', 'SPAM', 'NUDITY_OR_VIOLENCE', 'IMPERSONATION', 'OTHER')),
    CONSTRAINT ck_content_reports_note CHECK ((reason = 'OTHER') = (note IS NOT NULL)
        AND (note IS NULL OR CHAR_LENGTH(TRIM(note)) BETWEEN 1 AND 200)),
    CONSTRAINT ck_content_reports_times CHECK (counts_from >= created_at),
    INDEX idx_content_reports_reporter (reporter_id, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- 5. Strikes: human-confirmed violations only (removed content, or a decision about a person). The weight never
--    changes; points = weight x 0.5^(age / 90 days), computed when read (StrikePolicy). Deleted when an appeal
--    overturns the decision, or once they have decayed below a tenth of a point.
CREATE TABLE social_strikes (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    user_id    BIGINT      NOT NULL,
    reason     VARCHAR(20) COLLATE utf8mb4_bin NOT NULL,
    weight     TINYINT     NOT NULL,
    decision   CHAR(36)    COLLATE utf8mb4_bin NOT NULL,          -- the audit row (moderation_actions.public_id)
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_social_strikes_decision UNIQUE (decision),
    CONSTRAINT fk_social_strikes_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_social_strikes_reason CHECK (reason IN ('HARASSMENT', 'SEXUAL_HARASSMENT', 'HATE', 'THREAT',
        'DOXXING', 'SPAM', 'NUDITY_OR_VIOLENCE', 'IMPERSONATION', 'OTHER')),
    CONSTRAINT ck_social_strikes_weight CHECK (weight BETWEEN 1 AND 3),
    CONSTRAINT ck_social_strikes_decision CHECK (
        REGEXP_LIKE(decision, '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$', 'c')),
    INDEX idx_social_strikes_user (user_id, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- 6. A person's current social pause (one row, replaced by the next decision; in force while restricted_until is in
--    the future). Social reads it before posts, perspectives, places, connections and profile changes. It is never
--    read by SOS, Safe Walk, the Safety Circle or the vault.
CREATE TABLE social_standing (
    user_id          BIGINT      NOT NULL,
    restriction      VARCHAR(15) COLLATE utf8mb4_bin NOT NULL,
    restricted_until DATETIME(6) NOT NULL,
    reason           VARCHAR(20) COLLATE utf8mb4_bin NOT NULL,
    decision         CHAR(36)    COLLATE utf8mb4_bin NOT NULL,
    decided_at       DATETIME(6) NOT NULL,
    PRIMARY KEY (user_id),
    CONSTRAINT fk_social_standing_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_social_standing_restriction CHECK (restriction IN ('COMMENTS_PAUSED', 'POSTING_PAUSED',
        'SUSPENDED')),
    CONSTRAINT ck_social_standing_reason CHECK (reason IN ('HARASSMENT', 'SEXUAL_HARASSMENT', 'HATE', 'THREAT',
        'DOXXING', 'SPAM', 'NUDITY_OR_VIOLENCE', 'IMPERSONATION', 'OTHER')),
    CONSTRAINT ck_social_standing_decision CHECK (
        REGEXP_LIKE(decision, '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$', 'c')),
    CONSTRAINT ck_social_standing_times CHECK (restricted_until > decided_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- 7. The shared audit log learns the new decisions. target_kind NULL = a Phase 7 decision about a place signal.
ALTER TABLE moderation_actions DROP CHECK ck_moderation_actions_action;
ALTER TABLE moderation_actions DROP CHECK ck_moderation_actions_reason;

ALTER TABLE moderation_actions
    ADD COLUMN target_kind VARCHAR(7) COLLATE utf8mb4_bin NULL AFTER case_ref,
    ADD CONSTRAINT ck_moderation_actions_target_kind CHECK (target_kind IS NULL
        OR target_kind IN ('POST', 'COMMENT', 'PERSON', 'PLACE')),
    ADD CONSTRAINT ck_moderation_actions_action CHECK (action IN ('QUARANTINE', 'DISMISS', 'REMOVE', 'RESTRICT',
        'APPEAL_UPHELD', 'APPEAL_OVERTURNED', 'ROLE_GRANTED', 'ROLE_REVOKED',
        'CONTENT_KEEP', 'CONTENT_HOLD', 'CONTENT_COLLAPSE', 'CONTENT_REMOVE', 'PERSON_KEEP', 'PERSON_WARN',
        'PERSON_PAUSE_COMMENTS', 'PERSON_PAUSE_POSTING', 'PERSON_SUSPEND', 'PLACE_MERGED')),
    ADD CONSTRAINT ck_moderation_actions_reason CHECK (reason IN ('FALSE_INFORMATION', 'TARGETS_SOMEONE',
        'SPAM_OR_COORDINATED', 'DUPLICATE', 'NO_VIOLATION', 'NEEDS_REVIEW', 'APPEAL_REVIEWED', 'ROLE_CHANGE',
        'HARASSMENT', 'SEXUAL_HARASSMENT', 'HATE', 'THREAT', 'DOXXING', 'SPAM', 'NUDITY_OR_VIOLENCE', 'IMPERSONATION',
        'OTHER')),
    -- COALESCE: a NULL target_kind must fail the "IN" tests, not make them UNKNOWN (which a CHECK lets pass).
    ADD CONSTRAINT ck_moderation_actions_kind_matches CHECK (
        (action IN ('CONTENT_KEEP', 'CONTENT_HOLD', 'CONTENT_COLLAPSE', 'CONTENT_REMOVE')
            AND COALESCE(target_kind, '-') IN ('POST', 'COMMENT'))
        OR (action IN ('PERSON_KEEP', 'PERSON_WARN', 'PERSON_PAUSE_COMMENTS', 'PERSON_PAUSE_POSTING', 'PERSON_SUSPEND')
            AND COALESCE(target_kind, '-') IN ('POST', 'COMMENT', 'PERSON'))
        OR (action = 'PLACE_MERGED' AND COALESCE(target_kind, '-') = 'PLACE')
        OR (action IN ('APPEAL_UPHELD', 'APPEAL_OVERTURNED'))
        OR (action IN ('QUARANTINE', 'DISMISS', 'REMOVE', 'RESTRICT', 'ROLE_GRANTED', 'ROLE_REVOKED')
            AND target_kind IS NULL));
