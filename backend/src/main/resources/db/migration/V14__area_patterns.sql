-- Phase 8C: pattern memory for Safety Intelligence (docs/PHASE8_DESIGN.md §7.4, §14.2).
-- Design rules:
--   * ONE decaying number per (~1 km area cell, group). It is fed only by FINISHED episodes: a signal that reached
--     confirmation by independent people (place_signals.reached_confirmed), ended by its normal lifetime, was never
--     retired, removed or under review at the end. At most one episode per area and group per week.
--   * No user id, no signal id, no foreign key, no episode list, no report time: it cannot be linked back to the
--     signals or the people that formed it. The two dates have DAY precision and are never returned to any client.
--   * Only the band RECURRING ever leaves the server (as a reason code), and only from 3 or more episodes.
--   * Rows decay (60-day half-life) and are deleted below a threshold (at most ~240 days after the last episode),
--     and any moderator removal of a signal in the area deletes that area's row for the group.
CREATE TABLE area_patterns (
    area_cell       CHAR(6)      NOT NULL,          -- ~1 km geohash, never finer
    signal_group    VARCHAR(12)  NOT NULL,          -- INCIDENT | CONDITION
    pattern_value   DECIMAL(5,3) NOT NULL,          -- decayed episode weight; never returned
    decayed_on      DATE         NOT NULL,          -- UTC day the value was last decayed
    next_episode_on DATE         NOT NULL,          -- weekly admission cap

    PRIMARY KEY (area_cell, signal_group),
    CONSTRAINT ck_area_patterns_cell  CHECK (REGEXP_LIKE(area_cell, '^[0-9b-hjkmnp-z]{6}$', 'c')),
    CONSTRAINT ck_area_patterns_group CHECK (signal_group IN ('INCIDENT', 'CONDITION')),
    CONSTRAINT ck_area_patterns_value CHECK (pattern_value >= 0 AND pattern_value <= 4),
    CONSTRAINT ck_area_patterns_dates CHECK (next_episode_on >= decayed_on)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- Set by the clean-up job once a signal has been confirmed by independent people (MODERATE or HIGH). It is deleted
-- together with its signal and is never returned to any client.
ALTER TABLE place_signals
    ADD COLUMN reached_confirmed BOOLEAN NOT NULL DEFAULT FALSE;
