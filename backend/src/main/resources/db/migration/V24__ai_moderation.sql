-- Phase 13E: AI v1, AI-assisted moderation (docs/PHASE13E_PLAN.md E1-E30; EXPERIENCE_PLATFORM_DESIGN.md §6-§8).
-- Design rules:
--   * The moderation service produces EVIDENCE; the deterministic DecisionPolicy (Java) decides PUBLISH /
--     PUBLISH_COLLAPSED / HOLD_FOR_REVIEW. A machine never removes anything and never restricts a person.
--   * Who set a moderation state is explicit: REPORTS (a collapse by independent reports), SYSTEM (the automatic check:
--     COLLAPSED or HELD, with a reason and the flagged spans, never a decision id) or MODERATOR (a human decision id).
--   * content_assessments keeps NO TEXT: codes, severities, code-point offsets, versions. It can never hold a self-harm
--     signal (its evidence CHECK lists the stored categories only). Deleted with the content, else after 90 days.
--   * MySQL lets a CHECK pass when it is UNKNOWN (13D-D1): every nullable column inside IN (...) is COALESCEd.

-- 1. Posts and perspectives: who set the state, the flagged spans of an automatic state, strong language.
ALTER TABLE posts DROP CHECK ck_posts_moderation_state;
ALTER TABLE posts DROP CHECK ck_posts_moderation_reason;
ALTER TABLE posts
    ADD COLUMN moderation_source VARCHAR(9)   COLLATE utf8mb4_bin NULL AFTER moderation_decision,
    ADD COLUMN moderation_spans  VARCHAR(200) COLLATE utf8mb4_bin NULL AFTER moderation_source,
    ADD COLUMN strong_language   BOOLEAN      NOT NULL DEFAULT FALSE AFTER moderation_spans;
UPDATE posts SET moderation_source = CASE WHEN moderation = 'NONE' THEN NULL
                                          WHEN moderation_decision IS NOT NULL THEN 'MODERATOR' ELSE 'REPORTS' END;
ALTER TABLE posts
    ADD CONSTRAINT ck_posts_moderation_reason CHECK (moderation_reason IS NULL OR moderation_reason IN ('HARASSMENT',
        'SEXUAL_HARASSMENT', 'HATE', 'THREAT', 'DOXXING', 'SPAM', 'NUDITY_OR_VIOLENCE', 'IMPERSONATION', 'OTHER',
        'NEEDS_REVIEW', 'AWAITING_CHECK')),
    ADD CONSTRAINT ck_posts_moderation_source CHECK (moderation_source IS NULL
        OR moderation_source IN ('REPORTS', 'SYSTEM', 'MODERATOR')),
    ADD CONSTRAINT ck_posts_moderation_spans CHECK (moderation_spans IS NULL
        OR REGEXP_LIKE(moderation_spans, '^[0-9]{1,5}-[0-9]{1,5}(,[0-9]{1,5}-[0-9]{1,5}){0,19}$', 'c')),
    ADD CONSTRAINT ck_posts_moderation_state CHECK (
        (moderation = 'NONE' AND moderation_reason IS NULL AND moderation_decision IS NULL
            AND moderation_source IS NULL AND moderation_spans IS NULL)
        OR (moderation = 'COLLAPSED' AND COALESCE(moderation_source, '-') = 'REPORTS'
            AND moderation_reason IS NULL AND moderation_decision IS NULL AND moderation_spans IS NULL)
        OR (moderation IN ('COLLAPSED', 'HELD') AND COALESCE(moderation_source, '-') = 'SYSTEM'
            AND moderation_reason IS NOT NULL AND moderation_decision IS NULL)
        OR (moderation IN ('COLLAPSED', 'HELD', 'REMOVED') AND COALESCE(moderation_source, '-') = 'MODERATOR'
            AND moderation_reason IS NOT NULL AND moderation_decision IS NOT NULL AND moderation_spans IS NULL));

ALTER TABLE comments DROP CHECK ck_comments_moderation_state;
ALTER TABLE comments DROP CHECK ck_comments_moderation_reason;
ALTER TABLE comments
    ADD COLUMN moderation_source VARCHAR(9)   COLLATE utf8mb4_bin NULL AFTER moderation_decision,
    ADD COLUMN moderation_spans  VARCHAR(200) COLLATE utf8mb4_bin NULL AFTER moderation_source,
    ADD COLUMN strong_language   BOOLEAN      NOT NULL DEFAULT FALSE AFTER moderation_spans;
UPDATE comments SET moderation_source = CASE WHEN moderation = 'NONE' THEN NULL
                                             WHEN moderation_decision IS NOT NULL THEN 'MODERATOR' ELSE 'REPORTS' END;
ALTER TABLE comments
    ADD CONSTRAINT ck_comments_moderation_reason CHECK (moderation_reason IS NULL OR moderation_reason IN (
        'HARASSMENT', 'SEXUAL_HARASSMENT', 'HATE', 'THREAT', 'DOXXING', 'SPAM', 'NUDITY_OR_VIOLENCE', 'IMPERSONATION',
        'OTHER', 'NEEDS_REVIEW', 'AWAITING_CHECK')),
    ADD CONSTRAINT ck_comments_moderation_source CHECK (moderation_source IS NULL
        OR moderation_source IN ('REPORTS', 'SYSTEM', 'MODERATOR')),
    ADD CONSTRAINT ck_comments_moderation_spans CHECK (moderation_spans IS NULL
        OR REGEXP_LIKE(moderation_spans, '^[0-9]{1,5}-[0-9]{1,5}(,[0-9]{1,5}-[0-9]{1,5}){0,19}$', 'c')),
    ADD CONSTRAINT ck_comments_moderation_state CHECK (
        (moderation = 'NONE' AND moderation_reason IS NULL AND moderation_decision IS NULL
            AND moderation_source IS NULL AND moderation_spans IS NULL)
        OR (moderation = 'COLLAPSED' AND COALESCE(moderation_source, '-') = 'REPORTS'
            AND moderation_reason IS NULL AND moderation_decision IS NULL AND moderation_spans IS NULL)
        OR (moderation IN ('COLLAPSED', 'HELD') AND COALESCE(moderation_source, '-') = 'SYSTEM'
            AND moderation_reason IS NOT NULL AND moderation_decision IS NULL)
        OR (moderation IN ('COLLAPSED', 'HELD', 'REMOVED') AND COALESCE(moderation_source, '-') = 'MODERATOR'
            AND moderation_reason IS NOT NULL AND moderation_decision IS NOT NULL AND moderation_spans IS NULL));

-- 2. Why a content case was opened: reports, the automatic check (a hold), or the author asking for a review.
ALTER TABLE content_cases
    ADD COLUMN origin VARCHAR(14) COLLATE utf8mb4_bin NOT NULL DEFAULT 'REPORTS' AFTER target_kind,
    ADD CONSTRAINT ck_content_cases_origin CHECK (origin IN ('REPORTS', 'SYSTEM', 'AUTHOR_REQUEST'));

-- 3. The automatic check of one post or perspective (one row per target, replaced when its text is edited).
CREATE TABLE content_assessments (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    public_id          CHAR(36)     COLLATE utf8mb4_bin NOT NULL,
    target_kind        VARCHAR(7)   COLLATE utf8mb4_bin NOT NULL,
    post_id            BIGINT       NULL,                        -- exactly one of the two (code)
    comment_id         BIGINT       NULL,
    status             VARCHAR(11)  COLLATE utf8mb4_bin NOT NULL, -- PENDING (to retry), DONE, UNAVAILABLE (gave up)
    attempts           SMALLINT     NOT NULL DEFAULT 0,
    next_attempt_at    DATETIME(6)  NULL,
    action             VARCHAR(17)  COLLATE utf8mb4_bin NOT NULL,
    rule               VARCHAR(18)  COLLATE utf8mb4_bin NOT NULL,
    reason             VARCHAR(20)  COLLATE utf8mb4_bin NULL,
    evidence           VARCHAR(400) COLLATE utf8mb4_bin NULL,     -- CATEGORY:severity:T|U:Q|- (';'), never text
    spans              VARCHAR(200) COLLATE utf8mb4_bin NULL,     -- code-point offsets start-end (',')
    strong_language    BOOLEAN      NOT NULL DEFAULT FALSE,
    normaliser_version VARCHAR(16)  COLLATE utf8mb4_bin NULL,
    lexicon_version    VARCHAR(16)  COLLATE utf8mb4_bin NULL,
    model_version      VARCHAR(16)  COLLATE utf8mb4_bin NULL,
    policy_version     VARCHAR(16)  COLLATE utf8mb4_bin NOT NULL,
    created_at         DATETIME(6)  NOT NULL,
    checked_at         DATETIME(6)  NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_content_assessments_public_id UNIQUE (public_id),
    CONSTRAINT uk_content_assessments_post UNIQUE (post_id),
    CONSTRAINT uk_content_assessments_comment UNIQUE (comment_id),
    CONSTRAINT fk_content_assessments_post FOREIGN KEY (post_id) REFERENCES posts (id) ON DELETE CASCADE,
    CONSTRAINT fk_content_assessments_comment FOREIGN KEY (comment_id) REFERENCES comments (id) ON DELETE CASCADE,
    CONSTRAINT ck_content_assessments_public_id CHECK (
        REGEXP_LIKE(public_id, '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$', 'c')),
    CONSTRAINT ck_content_assessments_target_kind CHECK (target_kind IN ('POST', 'COMMENT')),
    CONSTRAINT ck_content_assessments_status CHECK (status IN ('PENDING', 'DONE', 'UNAVAILABLE')),
    CONSTRAINT ck_content_assessments_attempts CHECK (attempts BETWEEN 0 AND 100),
    CONSTRAINT ck_content_assessments_action CHECK (action IN ('PUBLISH', 'PUBLISH_COLLAPSED', 'HOLD_FOR_REVIEW')),
    CONSTRAINT ck_content_assessments_rule CHECK (rule IN ('CLEAN', 'STRONG_LANGUAGE', 'QUOTED', 'TARGETED', 'SEVERE',
        'SPAM', 'NEW_ACCOUNT_PHOTOS', 'CHECK_PENDING', 'MODEL')),
    CONSTRAINT ck_content_assessments_reason CHECK (reason IS NULL OR reason IN ('HARASSMENT', 'SEXUAL_HARASSMENT',
        'HATE', 'THREAT', 'DOXXING', 'SPAM', 'NEEDS_REVIEW', 'AWAITING_CHECK')),
    CONSTRAINT ck_content_assessments_reason_action CHECK ((action = 'PUBLISH') = (reason IS NULL)),
    -- Only these categories can be stored: a self-harm signal never reaches this table.
    CONSTRAINT ck_content_assessments_evidence CHECK (evidence IS NULL OR REGEXP_LIKE(evidence,
        '^(PROFANITY|HARASSMENT|SEXUAL_HARASSMENT|HATE|THREAT|DOXXING|SPAM):[0-3]:[TU]:[Q-](;(PROFANITY|HARASSMENT|SEXUAL_HARASSMENT|HATE|THREAT|DOXXING|SPAM):[0-3]:[TU]:[Q-]){0,19}$',
        'c')),
    CONSTRAINT ck_content_assessments_spans CHECK (spans IS NULL
        OR REGEXP_LIKE(spans, '^[0-9]{1,5}-[0-9]{1,5}(,[0-9]{1,5}-[0-9]{1,5}){0,19}$', 'c')),
    CONSTRAINT ck_content_assessments_versions CHECK (
        (normaliser_version IS NULL OR REGEXP_LIKE(normaliser_version, '^(-|[a-z]+-[0-9]+([.][0-9]+)?)$', 'c'))
        AND (lexicon_version IS NULL OR REGEXP_LIKE(lexicon_version, '^(-|[a-z]+-[0-9]+([.][0-9]+)?)$', 'c'))
        AND (model_version IS NULL OR REGEXP_LIKE(model_version, '^(-|[a-z]+-[0-9]+([.][0-9]+)?)$', 'c'))
        AND REGEXP_LIKE(policy_version, '^[a-z]+-[0-9]+([.][0-9]+)?$', 'c')),
    CONSTRAINT ck_content_assessments_state CHECK (
        (status = 'PENDING' AND rule = 'CHECK_PENDING' AND checked_at IS NULL AND next_attempt_at IS NOT NULL)
        OR (status = 'DONE' AND rule <> 'CHECK_PENDING' AND checked_at IS NOT NULL AND next_attempt_at IS NULL
            AND lexicon_version IS NOT NULL AND normaliser_version IS NOT NULL)
        OR (status = 'UNAVAILABLE' AND rule = 'CHECK_PENDING' AND checked_at IS NULL AND next_attempt_at IS NULL)),
    CONSTRAINT ck_content_assessments_times CHECK (checked_at IS NULL OR checked_at >= created_at),
    INDEX idx_content_assessments_retry (status, next_attempt_at),
    INDEX idx_content_assessments_created (created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- 4. Phase 9B counters learn the automatic actions and their human review (weekly, system-wide, no content).
ALTER TABLE evaluation_counters DROP CHECK ck_evaluation_counters_metric;
ALTER TABLE evaluation_counters
    ADD CONSTRAINT ck_evaluation_counters_metric CHECK (metric IN ('SIGNAL_STARTED', 'SIGNAL_JOINED_NEIGHBOUR',
        'SIGNAL_CONFIRMED', 'SIGNAL_ENDED', 'SIGNAL_REMOVED', 'CASE_OPENED', 'CASE_CLOSED', 'CASE_UNREVIEWED',
        'COORDINATION_SHADOW', 'SNAPSHOT_TAKEN', 'VISIBLE_SIGNALS', 'AREAS_BY_INFORMATION', 'AREAS_BY_OUTLOOK',
        'SHADOW_PICTURE', 'AI_CHECKED', 'AI_REVIEWED'));
