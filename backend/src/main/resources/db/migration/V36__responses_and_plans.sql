-- Phase N5 (docs/PHASE_N5.md, decisions N-88 to N-99): responses and plans. Same, Say more and its one follow-up,
-- I'm in and the plan, private replies, the Replies marker, and moderation of messages.
--
-- STATUS: written in N5-B1. Applied by unit B4 on the development MySQL (backup, dry run of every CHECK, then apply);
-- until then Flyway is held at V35 (N-99). Once applied anywhere this file is never edited again.
--
-- * moments          + more_text / more_at: the author's ONE follow-up to "Say more" (N-89; not an edit: the original
--                    stays); + called_off_at: a Who's in? its author called off; + happened_of: a "This happened" Say
--                    names the plan it came from (N-92).
-- * moment_signals   Same and Say more: one row per (moment, person, kind). Nothing is counted in the database; the
--                    answers carry names for the author and a band for others (N-88).
-- * plan_people      I'm in: ASKED, then IN when the author adds them, REMOVED when the author removes them (they cannot
--                    ask again). set_aside = the author's silent "Not this time" (N-90). Withdrawing deletes the row.
-- * conversations    a PLAN (one per Who's in?, members = its author + the people IN) or a REPLY (one per moment and
--                    replier: a private reply to the author, strict turns in code, N-91).
-- * messages         the words in a conversation (TEXT) or a quiet line (SYSTEM: someone is in, can't make it, the plan
--                    was called off, this happened, the conversation ended). The moderation columns mean what they mean
--                    on moments.
-- * social_settings  who may reply privately to the person's moments (no row = MY_PEOPLE, N-91).
-- * replies_opened   when the person last opened Replies (N-93): theirs alone, never per message, never shown.
-- * content_reports, content_cases, content_assessments learn a message as a target; moderation_actions learns MESSAGE.
--
-- No location column, no count column, nothing dropped. MySQL forbids a CHECK on a column of a foreign key with a
-- referential action, so "a REPLY has a replier", "a follow-up needs an ask first", "This happened only after the plan
-- time" and "exactly one target" are enforced in code and tested there. A NULL inside IN (...) is UNKNOWN, which a CHECK
-- lets pass: nullable closed lists are written with COALESCE (the V23 finding).

-- ---- 1. Moments: the follow-up, called off, This happened ------------------------------------------------------------
ALTER TABLE moments
    ADD COLUMN more_text     VARCHAR(500) NULL AFTER question_id,
    ADD COLUMN more_at       DATETIME(6)  NULL AFTER more_text,
    ADD COLUMN called_off_at DATETIME(6)  NULL AFTER more_at,
    ADD COLUMN happened_of   BIGINT       NULL AFTER called_off_at,
    ADD CONSTRAINT fk_moments_happened_of FOREIGN KEY (happened_of) REFERENCES moments (id) ON DELETE SET NULL,
    ADD CONSTRAINT ck_moments_more CHECK ((more_text IS NULL) = (more_at IS NULL)
        AND (more_text IS NULL OR (CHAR_LENGTH(more_text) BETWEEN 1 AND 500
            AND CHAR_LENGTH(TRIM(more_text)) = CHAR_LENGTH(more_text)))
        AND (more_at IS NULL OR (visible_at IS NOT NULL AND more_at >= visible_at))),
    ADD CONSTRAINT ck_moments_called_off CHECK (called_off_at IS NULL
        OR (kind = 'WHOS_IN' AND visible_at IS NOT NULL AND called_off_at >= visible_at));

-- ---- 2. Same and Say more ---------------------------------------------------------------------------------------------
CREATE TABLE moment_signals (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    moment_id  BIGINT      NOT NULL,
    user_id    BIGINT      NOT NULL,
    kind       VARCHAR(8)  COLLATE utf8mb4_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_moment_signals UNIQUE (moment_id, user_id, kind),
    CONSTRAINT fk_moment_signals_moment FOREIGN KEY (moment_id) REFERENCES moments (id) ON DELETE CASCADE,
    CONSTRAINT fk_moment_signals_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_moment_signals_kind CHECK (kind IN ('SAME', 'SAY_MORE')),
    INDEX idx_moment_signals_user (user_id, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- ---- 3. I'm in --------------------------------------------------------------------------------------------------------
CREATE TABLE plan_people (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    public_id   CHAR(36)    COLLATE utf8mb4_bin NOT NULL,     -- what the author acts on; never a user id
    moment_id   BIGINT      NOT NULL,
    user_id     BIGINT      NOT NULL,
    state       VARCHAR(7)  COLLATE utf8mb4_bin NOT NULL,
    asked_at    DATETIME(6) NOT NULL,
    answered_at DATETIME(6) NULL,                             -- when the author added or removed them
    set_aside   BOOLEAN     NOT NULL DEFAULT FALSE,           -- the author's silent "Not this time"
    PRIMARY KEY (id),
    CONSTRAINT uk_plan_people_public_id UNIQUE (public_id),
    CONSTRAINT uk_plan_people UNIQUE (moment_id, user_id),
    CONSTRAINT fk_plan_people_moment FOREIGN KEY (moment_id) REFERENCES moments (id) ON DELETE CASCADE,
    CONSTRAINT fk_plan_people_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_plan_people_public_id CHECK (
        REGEXP_LIKE(public_id, '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$', 'c')),
    CONSTRAINT ck_plan_people_state CHECK (state IN ('ASKED', 'IN', 'REMOVED')
        AND (state = 'ASKED') = (answered_at IS NULL)
        AND (answered_at IS NULL OR answered_at >= asked_at)
        AND (set_aside = FALSE OR state = 'ASKED')),
    INDEX idx_plan_people_user (user_id, asked_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- ---- 4. Conversations and messages ------------------------------------------------------------------------------------
CREATE TABLE conversations (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    public_id   CHAR(36)    COLLATE utf8mb4_bin NOT NULL,
    moment_id   BIGINT      NOT NULL,
    kind        VARCHAR(5)  COLLATE utf8mb4_bin NOT NULL,
    replier_id  BIGINT      NULL,                             -- REPLY: the person who replied privately (code)
    created_at  DATETIME(6) NOT NULL,
    closes_at   DATETIME(6) NOT NULL,                         -- read-only from then on
    ended_at    DATETIME(6) NULL,                             -- a REPLY one of the two ended early
    plan_marker TINYINT GENERATED ALWAYS AS (CASE WHEN kind = 'PLAN' THEN 1 ELSE NULL END) STORED,
    PRIMARY KEY (id),
    CONSTRAINT uk_conversations_public_id UNIQUE (public_id),
    CONSTRAINT uk_conversations_plan UNIQUE (moment_id, plan_marker),   -- one plan per Who's in?
    CONSTRAINT uk_conversations_reply UNIQUE (moment_id, replier_id),   -- one private reply per moment and person
    CONSTRAINT fk_conversations_moment FOREIGN KEY (moment_id) REFERENCES moments (id) ON DELETE CASCADE,
    CONSTRAINT fk_conversations_replier FOREIGN KEY (replier_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_conversations_public_id CHECK (
        REGEXP_LIKE(public_id, '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$', 'c')),
    CONSTRAINT ck_conversations_kind CHECK (kind IN ('PLAN', 'REPLY')),
    CONSTRAINT ck_conversations_times CHECK (closes_at > created_at
        AND (ended_at IS NULL OR (kind = 'REPLY' AND ended_at >= created_at))),
    INDEX idx_conversations_replier (replier_id, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE messages (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    public_id           CHAR(36)     COLLATE utf8mb4_bin NOT NULL,
    conversation_id     BIGINT       NOT NULL,
    author_id           BIGINT       NOT NULL,
    kind                VARCHAR(6)   COLLATE utf8mb4_bin NOT NULL,
    body                VARCHAR(500) NULL,                    -- TEXT: 1-500 characters, trimmed
    event               VARCHAR(10)  COLLATE utf8mb4_bin NULL, -- SYSTEM: what happened
    created_at          DATETIME(6)  NOT NULL,
    moderation          VARCHAR(9)   COLLATE utf8mb4_bin NOT NULL DEFAULT 'NONE',
    moderation_reason   VARCHAR(20)  COLLATE utf8mb4_bin NULL,
    moderation_decision CHAR(36)     COLLATE utf8mb4_bin NULL,
    moderation_source   VARCHAR(9)   COLLATE utf8mb4_bin NULL,
    moderation_spans    VARCHAR(200) COLLATE utf8mb4_bin NULL,
    in_review           BOOLEAN      NOT NULL DEFAULT FALSE,  -- an open case needs it: never purged meanwhile
    PRIMARY KEY (id),
    CONSTRAINT uk_messages_public_id UNIQUE (public_id),
    CONSTRAINT fk_messages_conversation FOREIGN KEY (conversation_id) REFERENCES conversations (id) ON DELETE CASCADE,
    CONSTRAINT fk_messages_author FOREIGN KEY (author_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_messages_public_id CHECK (
        REGEXP_LIKE(public_id, '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$', 'c')),
    CONSTRAINT ck_messages_kind CHECK (
        (kind = 'TEXT' AND event IS NULL AND body IS NOT NULL AND CHAR_LENGTH(body) BETWEEN 1 AND 500
            AND CHAR_LENGTH(TRIM(body)) = CHAR_LENGTH(body))
        OR (kind = 'SYSTEM' AND body IS NULL AND moderation = 'NONE'
            AND COALESCE(event, '-') IN ('IN', 'LEFT', 'CALLED_OFF', 'HAPPENED', 'ENDED'))),
    CONSTRAINT ck_messages_moderation CHECK (moderation IN ('NONE', 'COLLAPSED', 'HELD', 'REMOVED')),
    CONSTRAINT ck_messages_moderation_reason CHECK (moderation_reason IS NULL OR moderation_reason IN ('HARASSMENT',
        'SEXUAL_HARASSMENT', 'HATE', 'THREAT', 'DOXXING', 'SPAM', 'NUDITY_OR_VIOLENCE', 'IMPERSONATION', 'OTHER',
        'NEEDS_REVIEW', 'AWAITING_CHECK')),
    CONSTRAINT ck_messages_moderation_decision CHECK (moderation_decision IS NULL OR
        REGEXP_LIKE(moderation_decision, '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$', 'c')),
    CONSTRAINT ck_messages_moderation_source CHECK (moderation_source IS NULL
        OR moderation_source IN ('REPORTS', 'SYSTEM', 'MODERATOR')),
    CONSTRAINT ck_messages_moderation_spans CHECK (moderation_spans IS NULL
        OR REGEXP_LIKE(moderation_spans, '^[0-9]{1,5}-[0-9]{1,5}(,[0-9]{1,5}-[0-9]{1,5}){0,19}$', 'c')),
    CONSTRAINT ck_messages_moderation_state CHECK (
        (moderation = 'NONE' AND moderation_reason IS NULL AND moderation_decision IS NULL
            AND moderation_source IS NULL AND moderation_spans IS NULL)
        OR (moderation = 'COLLAPSED' AND COALESCE(moderation_source, '-') = 'REPORTS'
            AND moderation_reason IS NULL AND moderation_decision IS NULL AND moderation_spans IS NULL)
        OR (moderation IN ('COLLAPSED', 'HELD') AND COALESCE(moderation_source, '-') = 'SYSTEM'
            AND moderation_reason IS NOT NULL AND moderation_decision IS NULL)
        OR (moderation IN ('COLLAPSED', 'HELD', 'REMOVED') AND COALESCE(moderation_source, '-') = 'MODERATOR'
            AND moderation_reason IS NOT NULL AND moderation_decision IS NOT NULL AND moderation_spans IS NULL)),
    INDEX idx_messages_conversation (conversation_id, created_at, id),
    INDEX idx_messages_author (author_id, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- ---- 5. The person's own settings and their Replies marker -------------------------------------------------------------
CREATE TABLE social_settings (
    user_id         BIGINT      NOT NULL,
    private_replies VARCHAR(9)  COLLATE utf8mb4_bin NOT NULL,
    updated_at      DATETIME(6) NOT NULL,
    PRIMARY KEY (user_id),
    CONSTRAINT fk_social_settings_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_social_settings_private_replies CHECK (private_replies IN ('MY_PEOPLE', 'COMMUNITY', 'NOBODY'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE replies_opened (
    user_id   BIGINT      NOT NULL,
    opened_at DATETIME(6) NOT NULL,
    PRIMARY KEY (user_id),
    CONSTRAINT fk_replies_opened_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- ---- 6. Moderation learns messages -------------------------------------------------------------------------------------
ALTER TABLE content_reports
    ADD COLUMN message_id BIGINT NULL AFTER moment_id,         -- exactly one target per report (code)
    ADD CONSTRAINT uk_content_reports_message UNIQUE (reporter_id, message_id),
    ADD CONSTRAINT fk_content_reports_message FOREIGN KEY (message_id) REFERENCES messages (id) ON DELETE CASCADE;

ALTER TABLE content_cases DROP CHECK ck_content_cases_target_kind;
ALTER TABLE content_cases
    ADD COLUMN message_id BIGINT NULL AFTER moment_id,         -- exactly one of moment / message / person (code)
    ADD CONSTRAINT uk_content_cases_open_message UNIQUE (message_id, open_marker),
    ADD CONSTRAINT fk_content_cases_message FOREIGN KEY (message_id) REFERENCES messages (id) ON DELETE CASCADE,
    ADD CONSTRAINT ck_content_cases_target_kind CHECK (target_kind IN ('MOMENT', 'PERSON', 'MESSAGE'));

-- An assessment is about a moment OR a message now (exactly one, in code: both are cascading foreign keys).
ALTER TABLE content_assessments DROP CHECK ck_content_assessments_target_kind;
ALTER TABLE content_assessments DROP FOREIGN KEY fk_content_assessments_moment;
ALTER TABLE content_assessments MODIFY COLUMN moment_id BIGINT NULL;
ALTER TABLE content_assessments
    ADD CONSTRAINT fk_content_assessments_moment FOREIGN KEY (moment_id) REFERENCES moments (id) ON DELETE CASCADE,
    ADD COLUMN message_id BIGINT NULL AFTER moment_id,
    ADD CONSTRAINT uk_content_assessments_message UNIQUE (message_id),
    ADD CONSTRAINT fk_content_assessments_message FOREIGN KEY (message_id) REFERENCES messages (id) ON DELETE CASCADE,
    ADD CONSTRAINT ck_content_assessments_target_kind CHECK (target_kind IN ('MOMENT', 'MESSAGE'));

ALTER TABLE moderation_actions DROP CHECK ck_moderation_actions_target_kind;
ALTER TABLE moderation_actions DROP CHECK ck_moderation_actions_kind_matches;
ALTER TABLE moderation_actions
    ADD CONSTRAINT ck_moderation_actions_target_kind CHECK (target_kind IS NULL
        OR target_kind IN ('POST', 'COMMENT', 'PERSON', 'PLACE', 'MOMENT', 'MESSAGE')),
    ADD CONSTRAINT ck_moderation_actions_kind_matches CHECK (
        (action IN ('CONTENT_KEEP', 'CONTENT_HOLD', 'CONTENT_COLLAPSE', 'CONTENT_REMOVE')
            AND COALESCE(target_kind, '-') IN ('POST', 'COMMENT', 'MOMENT', 'MESSAGE'))
        OR (action IN ('PERSON_KEEP', 'PERSON_WARN', 'PERSON_PAUSE_COMMENTS', 'PERSON_PAUSE_POSTING', 'PERSON_SUSPEND')
            AND COALESCE(target_kind, '-') IN ('POST', 'COMMENT', 'PERSON', 'MOMENT', 'MESSAGE'))
        OR (action = 'PERSON_REMOVE_PHOTO' AND COALESCE(target_kind, '-') = 'PERSON')
        OR (action = 'PLACE_MERGED' AND COALESCE(target_kind, '-') = 'PLACE')
        OR (action IN ('APPEAL_UPHELD', 'APPEAL_OVERTURNED'))
        OR (action IN ('QUARANTINE', 'DISMISS', 'REMOVE', 'RESTRICT', 'ROLE_GRANTED', 'ROLE_REVOKED')
            AND target_kind IS NULL));
