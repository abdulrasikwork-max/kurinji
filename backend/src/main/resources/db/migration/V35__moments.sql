-- Phase N4 (docs/PHASE_N4.md, decisions N-42 to N-58): moments, Today, keep and the daily question replace the old
-- social surface (posts, perspectives, places, cities).
--
-- STATUS: written in N4-B1 and applied NOWHERE yet. Until unit B4 applies it on the development MySQL (backup, dry run
-- of every CHECK, then apply), application.properties holds Flyway at V34 (spring.flyway.target=34), because sections 4
-- and 5 delete data that the old social code still reads until unit B3 removes that code. Units B2 and B3 may still
-- change THIS file (photo limits, moderation lists); after B4 it is never edited again.
--
-- * daily_questions  one question per community per India calendar day, written by an admin (N-49).
-- * moments          a Say or a Who's in? (N-45): text up to 500 characters and at most one photo. No location column
--                    of any kind: a Who's in? has an optional time within 24 h and an optional plain-text "where" of at
--                    most 60 characters (N-50). visible_at = when others could first see it; expires_at = 24 h later
--                    (N-47). Both are NULL while a moment has never been visible (held from the start): the 24 hours
--                    begin when a moderator releases it. kept_at = kept on the profile (N-48; at most 6 per person, in
--                    code). The moderation columns have the meaning they had on posts (V23, V24) and are written by
--                    the moderation package only. No count of anything is stored.
-- * moment_media     the photo of a moment (N4: photos only; no voice, no video: N-44). A row without moment_id is an
--                    upload not attached yet (deleted after 24 h by SocialMediaCleanupJob).
-- * content_reports, content_cases, content_assessments now point at a moment instead of a post or a perspective;
--   their rows about posts and perspectives are deleted. moderation_actions keeps every history row.
-- * post_revisions, comments, post_media, posts, places, cities and profiles.city / languages / interests go (N-51).
--
-- MySQL forbids a CHECK on a column of a foreign key with a referential action (the V6 finding), so "a text or a
-- photo", "the question belongs to the moment's community and day", "exactly one target" and "at most 6 kept" are
-- enforced in code and tested there. A NULL inside IN (...) is UNKNOWN, which a CHECK lets pass: every nullable column
-- in a closed list is written with COALESCE (the V23 finding).
-- Sections 4 and 5 cannot be undone: take a backup first.

-- ---- 1. The daily question -------------------------------------------------------------------------------------------
CREATE TABLE daily_questions (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    public_id     CHAR(36)     COLLATE utf8mb4_bin NOT NULL,
    community_id  BIGINT       NOT NULL,
    question_day  DATE         NOT NULL,                         -- the India calendar day it is pinned on
    body          VARCHAR(140) NOT NULL,
    created_by    BIGINT       NULL,                             -- the admin; never shown to members
    created_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_daily_questions_public_id UNIQUE (public_id),
    CONSTRAINT uk_daily_questions_day UNIQUE (community_id, question_day),
    CONSTRAINT fk_daily_questions_community FOREIGN KEY (community_id) REFERENCES communities (id),
    CONSTRAINT fk_daily_questions_created_by FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT ck_daily_questions_public_id CHECK (
        REGEXP_LIKE(public_id, '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$', 'c')),
    CONSTRAINT ck_daily_questions_body CHECK (CHAR_LENGTH(body) BETWEEN 3 AND 140
        AND CHAR_LENGTH(TRIM(body)) = CHAR_LENGTH(body))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- ---- 2. Moments ------------------------------------------------------------------------------------------------------
CREATE TABLE moments (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    public_id           CHAR(36)     COLLATE utf8mb4_bin NOT NULL,
    author_id           BIGINT       NOT NULL,
    community_id        BIGINT       NOT NULL,                   -- the author's community when it was shared
    kind                VARCHAR(8)   COLLATE utf8mb4_bin NOT NULL,
    audience            VARCHAR(9)   COLLATE utf8mb4_bin NOT NULL,
    body                VARCHAR(500) NULL,                       -- NULL only next to a photo (code)
    plan_at             DATETIME(6)  NULL,                       -- WHOS_IN only: within 24 h of created_at
    plan_where          VARCHAR(60)  NULL,                       -- WHOS_IN only: plain text, never a place record
    question_id         BIGINT       NULL,                       -- an answer to that day's question
    created_at          DATETIME(6)  NOT NULL,
    visible_at          DATETIME(6)  NULL,                       -- when others could first see it
    expires_at          DATETIME(6)  NULL,                       -- visible_at + 24 h: it leaves Today
    kept_at             DATETIME(6)  NULL,                       -- kept on the profile
    moderation          VARCHAR(9)   COLLATE utf8mb4_bin NOT NULL DEFAULT 'NONE',
    moderation_reason   VARCHAR(20)  COLLATE utf8mb4_bin NULL,
    moderation_decision CHAR(36)     COLLATE utf8mb4_bin NULL,
    moderation_source   VARCHAR(9)   COLLATE utf8mb4_bin NULL,
    moderation_spans    VARCHAR(200) COLLATE utf8mb4_bin NULL,
    strong_language     BOOLEAN      NOT NULL DEFAULT FALSE,
    in_review           BOOLEAN      NOT NULL DEFAULT FALSE,     -- an open case needs it: never purged meanwhile
    PRIMARY KEY (id),
    CONSTRAINT uk_moments_public_id UNIQUE (public_id),
    CONSTRAINT fk_moments_author FOREIGN KEY (author_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_moments_community FOREIGN KEY (community_id) REFERENCES communities (id),
    CONSTRAINT fk_moments_question FOREIGN KEY (question_id) REFERENCES daily_questions (id) ON DELETE SET NULL,
    CONSTRAINT ck_moments_public_id CHECK (
        REGEXP_LIKE(public_id, '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$', 'c')),
    CONSTRAINT ck_moments_kind CHECK (kind IN ('SAY', 'WHOS_IN')),
    CONSTRAINT ck_moments_audience CHECK (audience IN ('COMMUNITY', 'MY_PEOPLE')),
    CONSTRAINT ck_moments_body CHECK (body IS NULL OR (CHAR_LENGTH(body) BETWEEN 1 AND 500
        AND CHAR_LENGTH(TRIM(body)) = CHAR_LENGTH(body))),
    CONSTRAINT ck_moments_plan CHECK (
        (kind = 'WHOS_IN' OR (plan_at IS NULL AND plan_where IS NULL))
        AND (plan_at IS NULL OR (plan_at > created_at AND plan_at <= created_at + INTERVAL 24 HOUR))
        AND (plan_where IS NULL OR (CHAR_LENGTH(plan_where) BETWEEN 1 AND 60
            AND CHAR_LENGTH(TRIM(plan_where)) = CHAR_LENGTH(plan_where)))),
    CONSTRAINT ck_moments_life CHECK ((visible_at IS NULL) = (expires_at IS NULL)
        AND (visible_at IS NULL OR (visible_at >= created_at AND expires_at > visible_at))),
    CONSTRAINT ck_moments_kept CHECK (kept_at IS NULL OR (visible_at IS NOT NULL AND kept_at >= visible_at)),
    CONSTRAINT ck_moments_moderation CHECK (moderation IN ('NONE', 'COLLAPSED', 'HELD', 'REMOVED')),
    CONSTRAINT ck_moments_moderation_reason CHECK (moderation_reason IS NULL OR moderation_reason IN ('HARASSMENT',
        'SEXUAL_HARASSMENT', 'HATE', 'THREAT', 'DOXXING', 'SPAM', 'NUDITY_OR_VIOLENCE', 'IMPERSONATION', 'OTHER',
        'NEEDS_REVIEW', 'AWAITING_CHECK')),
    CONSTRAINT ck_moments_moderation_decision CHECK (moderation_decision IS NULL OR
        REGEXP_LIKE(moderation_decision, '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$', 'c')),
    CONSTRAINT ck_moments_moderation_source CHECK (moderation_source IS NULL
        OR moderation_source IN ('REPORTS', 'SYSTEM', 'MODERATOR')),
    CONSTRAINT ck_moments_moderation_spans CHECK (moderation_spans IS NULL
        OR REGEXP_LIKE(moderation_spans, '^[0-9]{1,5}-[0-9]{1,5}(,[0-9]{1,5}-[0-9]{1,5}){0,19}$', 'c')),
    CONSTRAINT ck_moments_moderation_state CHECK (
        (moderation = 'NONE' AND moderation_reason IS NULL AND moderation_decision IS NULL
            AND moderation_source IS NULL AND moderation_spans IS NULL)
        OR (moderation = 'COLLAPSED' AND COALESCE(moderation_source, '-') = 'REPORTS'
            AND moderation_reason IS NULL AND moderation_decision IS NULL AND moderation_spans IS NULL)
        OR (moderation IN ('COLLAPSED', 'HELD') AND COALESCE(moderation_source, '-') = 'SYSTEM'
            AND moderation_reason IS NOT NULL AND moderation_decision IS NULL)
        OR (moderation IN ('COLLAPSED', 'HELD', 'REMOVED') AND COALESCE(moderation_source, '-') = 'MODERATOR'
            AND moderation_reason IS NOT NULL AND moderation_decision IS NOT NULL AND moderation_spans IS NULL)),
    INDEX idx_moments_today (community_id, visible_at, id),
    INDEX idx_moments_author (author_id, created_at),
    INDEX idx_moments_expires (expires_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE moment_media (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    public_id   CHAR(36)    COLLATE utf8mb4_bin NOT NULL,
    owner_id    BIGINT      NOT NULL,
    moment_id   BIGINT      NULL,                                -- NULL = uploaded, not attached yet
    storage_key CHAR(36)    COLLATE utf8mb4_bin NOT NULL,        -- random file name chosen by the server
    width       INT         NOT NULL,
    height      INT         NOT NULL,
    bytes       INT         NOT NULL,
    sha256      CHAR(64)    COLLATE utf8mb4_bin NOT NULL,        -- of the stored (re-encoded) file
    created_at  DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_moment_media_public_id UNIQUE (public_id),
    CONSTRAINT uk_moment_media_storage_key UNIQUE (storage_key),
    CONSTRAINT uk_moment_media_moment UNIQUE (moment_id),        -- at most one photo per moment
    CONSTRAINT fk_moment_media_owner FOREIGN KEY (owner_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_moment_media_moment FOREIGN KEY (moment_id) REFERENCES moments (id) ON DELETE CASCADE,
    CONSTRAINT ck_moment_media_public_id CHECK (
        REGEXP_LIKE(public_id, '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$', 'c')),
    CONSTRAINT ck_moment_media_storage_key CHECK (
        REGEXP_LIKE(storage_key, '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$', 'c')),
    CONSTRAINT ck_moment_media_size CHECK (width BETWEEN 1 AND 4096 AND height BETWEEN 1 AND 4096
        AND bytes BETWEEN 1 AND 5242880),
    CONSTRAINT ck_moment_media_sha256 CHECK (REGEXP_LIKE(sha256, '^[0-9a-f]{64}$', 'c')),
    INDEX idx_moment_media_owner (owner_id, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- ---- 3. Moderation now points at moments -----------------------------------------------------------------------------
-- Reports and cases about posts and perspectives go with their targets (cases about a PERSON stay). A foreign key is
-- dropped before the index it uses, and an index before its column (dropping post_id alone would silently turn
-- UNIQUE (reporter_id, post_id) into UNIQUE (reporter_id)).
DELETE FROM content_reports WHERE post_id IS NOT NULL OR comment_id IS NOT NULL;
DELETE FROM content_cases WHERE post_id IS NOT NULL OR comment_id IS NOT NULL;
DELETE FROM content_assessments;

ALTER TABLE content_reports DROP FOREIGN KEY fk_content_reports_post;
ALTER TABLE content_reports DROP FOREIGN KEY fk_content_reports_comment;
ALTER TABLE content_reports DROP INDEX uk_content_reports_post;
ALTER TABLE content_reports DROP INDEX uk_content_reports_comment;
ALTER TABLE content_reports DROP COLUMN post_id;
ALTER TABLE content_reports DROP COLUMN comment_id;
ALTER TABLE content_reports
    ADD COLUMN moment_id BIGINT NULL AFTER reporter_id,          -- the case's target again, for the UNIQUE rule
    ADD CONSTRAINT uk_content_reports_moment UNIQUE (reporter_id, moment_id),
    ADD CONSTRAINT fk_content_reports_moment FOREIGN KEY (moment_id) REFERENCES moments (id) ON DELETE CASCADE;

ALTER TABLE content_cases DROP FOREIGN KEY fk_content_cases_post;
ALTER TABLE content_cases DROP FOREIGN KEY fk_content_cases_comment;
ALTER TABLE content_cases DROP INDEX uk_content_cases_open_post;
ALTER TABLE content_cases DROP INDEX uk_content_cases_open_comment;
ALTER TABLE content_cases DROP COLUMN post_id;
ALTER TABLE content_cases DROP COLUMN comment_id;
ALTER TABLE content_cases DROP CHECK ck_content_cases_target_kind;
ALTER TABLE content_cases DROP CHECK ck_content_cases_origin;
ALTER TABLE content_cases
    ADD COLUMN moment_id BIGINT NULL AFTER origin,               -- exactly one of moment_id / person_id (code)
    ADD CONSTRAINT uk_content_cases_open_moment UNIQUE (moment_id, open_marker),
    ADD CONSTRAINT fk_content_cases_moment FOREIGN KEY (moment_id) REFERENCES moments (id) ON DELETE CASCADE,
    ADD CONSTRAINT ck_content_cases_target_kind CHECK (target_kind IN ('MOMENT', 'PERSON')),
    -- ABOUT_ME (N-52): someone said "This is about me"; the moment is hidden at once and a human decides.
    ADD CONSTRAINT ck_content_cases_origin CHECK (origin IN ('REPORTS', 'SYSTEM', 'AUTHOR_REQUEST', 'ABOUT_ME'));

ALTER TABLE content_assessments DROP FOREIGN KEY fk_content_assessments_post;
ALTER TABLE content_assessments DROP FOREIGN KEY fk_content_assessments_comment;
ALTER TABLE content_assessments DROP INDEX uk_content_assessments_post;
ALTER TABLE content_assessments DROP INDEX uk_content_assessments_comment;
ALTER TABLE content_assessments DROP COLUMN post_id;
ALTER TABLE content_assessments DROP COLUMN comment_id;
ALTER TABLE content_assessments DROP CHECK ck_content_assessments_target_kind;
ALTER TABLE content_assessments
    ADD COLUMN moment_id BIGINT NOT NULL AFTER target_kind,      -- the table is empty here (emptied above)
    ADD CONSTRAINT uk_content_assessments_moment UNIQUE (moment_id),
    ADD CONSTRAINT fk_content_assessments_moment FOREIGN KEY (moment_id) REFERENCES moments (id) ON DELETE CASCADE,
    ADD CONSTRAINT ck_content_assessments_target_kind CHECK (target_kind = 'MOMENT');

-- The audit log keeps every row it has (decisions about posts and perspectives stay as history) and learns MOMENT.
ALTER TABLE moderation_actions DROP CHECK ck_moderation_actions_target_kind;
ALTER TABLE moderation_actions DROP CHECK ck_moderation_actions_kind_matches;
ALTER TABLE moderation_actions
    ADD CONSTRAINT ck_moderation_actions_target_kind CHECK (target_kind IS NULL
        OR target_kind IN ('POST', 'COMMENT', 'PERSON', 'PLACE', 'MOMENT')),
    ADD CONSTRAINT ck_moderation_actions_kind_matches CHECK (
        (action IN ('CONTENT_KEEP', 'CONTENT_HOLD', 'CONTENT_COLLAPSE', 'CONTENT_REMOVE')
            AND COALESCE(target_kind, '-') IN ('POST', 'COMMENT', 'MOMENT'))
        OR (action IN ('PERSON_KEEP', 'PERSON_WARN', 'PERSON_PAUSE_COMMENTS', 'PERSON_PAUSE_POSTING', 'PERSON_SUSPEND')
            AND COALESCE(target_kind, '-') IN ('POST', 'COMMENT', 'PERSON', 'MOMENT'))
        OR (action = 'PERSON_REMOVE_PHOTO' AND COALESCE(target_kind, '-') = 'PERSON')
        OR (action = 'PLACE_MERGED' AND COALESCE(target_kind, '-') = 'PLACE')
        OR (action IN ('APPEAL_UPHELD', 'APPEAL_OVERTURNED'))
        OR (action IN ('QUARANTINE', 'DISMISS', 'REMOVE', 'RESTRICT', 'ROLE_GRANTED', 'ROLE_REVOKED')
            AND target_kind IS NULL));

-- ---- 4. The old social surface goes (children before parents) ----------------------------------------------------------
-- The photo and video files of the dropped posts are not in the database: SocialMediaCleanupJob removes them as files
-- without a row (copy the media folder first, like the database).
DROP TABLE post_revisions;
DROP TABLE comments;
DROP TABLE post_media;
DROP TABLE posts;
DROP TABLE places;
DROP TABLE cities;

-- ---- 5. The profile loses its place-first fields (N-51) ----------------------------------------------------------------
-- MySQL refuses to drop a column that a CHECK still names: the CHECKs go first.
ALTER TABLE profiles DROP CHECK ck_profiles_city;
ALTER TABLE profiles DROP CHECK ck_profiles_languages;
ALTER TABLE profiles DROP CHECK ck_profiles_interests;
ALTER TABLE profiles DROP COLUMN city;
ALTER TABLE profiles DROP COLUMN languages;
ALTER TABLE profiles DROP COLUMN interests;
