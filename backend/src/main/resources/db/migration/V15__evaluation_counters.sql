-- Phase 9B: outcome counters, so the rules can be evaluated (docs/PHASE9_DESIGN.md §7.5.4, docs/PHASE9.md §27).
-- Design rules:
--   * SYSTEM-WIDE, WEEKLY counts only. No user id, no cell, no coordinate, no signal or case id, no free text and no time
--     finer than an ISO week (week_start is the Monday, UTC). The CHECKs make storing a cell or an id impossible:
--     a dimension is upper-case letters and underscores only (a geohash is lower case and digits), a model version is a
--     short lower-case name with a number (e.g. 'rules-1.1', 'coord-1').
--   * Written only by the server itself (best effort, after commit); read only by an ADMIN through
--     GET /api/admin/evaluation. Rows older than 104 weeks are deleted by the clean-up job.
--   * The count column is called "occurrences" because VALUE is a keyword in H2 (the test database).
--   * metric and coordination_rule are binary-collated: with the default case-insensitive collation an IN (...) CHECK
--     also accepts 'support_core' (found in the dry run).
CREATE TABLE evaluation_counters (
    week_start    DATE        NOT NULL,                 -- Monday (UTC) of the ISO week; nothing finer
    metric        VARCHAR(40) COLLATE utf8mb4_bin NOT NULL,
    dimension     VARCHAR(48) NOT NULL DEFAULT '-',     -- e.g. 'CONDITION.EXPIRED_CONFIRMED'
    model_version VARCHAR(20) NOT NULL DEFAULT '-',     -- e.g. 'rules-1.1', 'rules-2', 'coord-1'
    occurrences   INT         NOT NULL,

    PRIMARY KEY (week_start, metric, dimension, model_version),
    CONSTRAINT ck_evaluation_counters_metric CHECK (metric IN ('SIGNAL_STARTED', 'SIGNAL_JOINED_NEIGHBOUR',
        'SIGNAL_CONFIRMED', 'SIGNAL_ENDED', 'SIGNAL_REMOVED', 'CASE_OPENED', 'CASE_CLOSED', 'CASE_UNREVIEWED',
        'COORDINATION_SHADOW', 'SNAPSHOT_TAKEN', 'VISIBLE_SIGNALS', 'AREAS_BY_INFORMATION', 'AREAS_BY_OUTLOOK',
        'SHADOW_PICTURE')),
    CONSTRAINT ck_evaluation_counters_dimension CHECK (REGEXP_LIKE(dimension, '^(-|[A-Z_]+([.][A-Z_]+)?)$', 'c')),
    CONSTRAINT ck_evaluation_counters_version CHECK (REGEXP_LIKE(model_version, '^(-|[a-z]+-[0-9]+([.][0-9]+)?)$', 'c')),
    CONSTRAINT ck_evaluation_counters_occurrences CHECK (occurrences >= 0),
    CONSTRAINT ck_evaluation_counters_monday CHECK (DAYOFWEEK(week_start) = 2)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- Which rule opened a COORDINATION case (Phase 7's linked group, or coord-1's support core / exclusive pair), so that a
-- moderator's decision is counted against the rule that asked for it. Recomputing the rule at decision time does not
-- work: a whole-group pause (9C.2) makes the pattern disappear before the case is closed. A code only; deleted with
-- the case. NULL for FLAGS and REREPORTED cases and for COORDINATION cases opened before V15.
ALTER TABLE moderation_cases
    ADD COLUMN coordination_rule VARCHAR(20) COLLATE utf8mb4_bin NULL,
    ADD CONSTRAINT ck_moderation_cases_coordination_rule CHECK (coordination_rule IS NULL
        OR (origin = 'COORDINATION' AND coordination_rule IN ('LINKED_GROUP', 'SUPPORT_CORE', 'EXCLUSIVE_PAIR')));
