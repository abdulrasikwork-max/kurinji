-- Phase 6: Community Safety Intelligence ("Place Signals", docs/PHASE6.md).
-- A place signal is a short-lived, structured observation about a PLACE (never about a person): one row per
-- (area cell, category). Everyone who reports or answers it is one contribution row. Design rules:
--   * No coordinates are stored. The phone turns its location into a ~150 m geohash cell (7 characters) BEFORE
--     sending; the server only ever sees and stores that cell. area_cell (first 6 characters, ~1.2 x 0.6 km) is the
--     coarse cell shown to others while a signal has only one supporter, and the index for area queries.
--   * No free text, no photos, no reporter visible to anyone. user_id exists only for rate limits, "one answer per
--     person" and Phase 7 accountability; the API never returns it.
--   * counts_from is the ONLY time stored per contribution: server time + a random publication delay (+ the rest
--     of the new-account probation). The exact moment someone reported is never stored, so it cannot leak.
--   * Rows are deleted (not hidden) when they expire: see CommunityService.purge (per-category lifetime).
CREATE TABLE place_signals (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    public_id  CHAR(36)    NOT NULL,                     -- random UUID: the only id clients see (no count/order leak)
    category   VARCHAR(30) NOT NULL,
    cell       CHAR(7)     NOT NULL,                     -- geohash, ~150 m
    area_cell  CHAR(6)     NOT NULL,                     -- = LEFT(cell, 6), ~1.2 km x 0.6 km

    PRIMARY KEY (id),
    CONSTRAINT uk_place_signals_public_id UNIQUE (public_id),
    CONSTRAINT uk_place_signals_cell_category UNIQUE (cell, category),   -- reports MERGE, they never pile up
    INDEX idx_place_signals_area (area_cell),
    CONSTRAINT ck_place_signals_category CHECK (category IN (
        'HARASSMENT', 'FOLLOWING', 'THEFT_OR_SNATCHING',
        'POOR_LIGHTING', 'ISOLATED_STRETCH', 'UNSAFE_CROSSING', 'BROKEN_PATH',
        'WELL_LIT_BUSY', 'OPEN_LATE_STAFFED', 'HELP_POINT')),
    CONSTRAINT ck_place_signals_cell CHECK (REGEXP_LIKE(cell, '^[0-9b-hjkmnp-z]{7}$', 'c')),
    CONSTRAINT ck_place_signals_area_cell CHECK (area_cell = LEFT(cell, 6))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE signal_contributions (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    signal_id   BIGINT      NOT NULL,
    user_id     BIGINT      NOT NULL,
    kind        VARCHAR(20) NOT NULL,                    -- REPORT | CONFIRM ("I saw this too") | NO_LONGER
    counts_from DATETIME(6) NOT NULL,                    -- UTC; see the header

    PRIMARY KEY (id),
    CONSTRAINT uk_signal_contributions_signal_user UNIQUE (signal_id, user_id),   -- one answer per person
    CONSTRAINT fk_signal_contributions_signal FOREIGN KEY (signal_id) REFERENCES place_signals (id) ON DELETE CASCADE,
    CONSTRAINT fk_signal_contributions_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    INDEX idx_signal_contributions_user_time (user_id, counts_from),
    INDEX idx_signal_contributions_time (counts_from),
    CONSTRAINT ck_signal_contributions_kind CHECK (kind IN ('REPORT', 'CONFIRM', 'NO_LONGER'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
