-- Phase 13C: intent and discovery (docs/PHASE13C_PLAN.md, docs/EXPERIENCE_PLATFORM_DESIGN.md §3.4, §3.7, §4.10).
-- Design rules:
--   * The butterfly IS the Cocoon item (C1): one row per (person, post), WANT -> BEEN. Rows are returned only to the
--     person who saved them; the author of the post gets a number only from 3 people up (C2, in code). No other table
--     or answer lists who saved what.
--   * places.city is computed by the server from the place's cell (CityAreas, C6); NULL = outside every launch city.
--     Never sent by the phone. OTHER is not a place city (it exists only for CITY-precision posts).
--   * Places created before V22 keep NULL until the code sets it (there were none outside test data).
--   * MySQL refuses CHECK constraints on columns used by a cascading foreign key (the V6 finding): "not your own post"
--     and "Experiences only" are enforced in code (ButterflyService) and tested there.

ALTER TABLE places
    ADD COLUMN city VARCHAR(20) COLLATE utf8mb4_bin NULL AFTER area_cell,
    ADD CONSTRAINT ck_places_city CHECK (city IS NULL OR city IN ('CHENNAI', 'COIMBATORE', 'MADURAI',
        'TIRUCHIRAPPALLI', 'SALEM', 'TIRUNELVELI', 'VELLORE', 'ERODE', 'THOOTHUKUDI', 'THANJAVUR', 'PUDUCHERRY',
        'BENGALURU')),
    ADD INDEX idx_places_city (city);

CREATE TABLE butterflies (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    user_id    BIGINT      NOT NULL,                          -- the person whose Cocoon this is
    post_id    BIGINT      NOT NULL,
    state      VARCHAR(4)  COLLATE utf8mb4_bin NOT NULL,      -- WANT = want to experience; BEEN = went
    created_at DATETIME(6) NOT NULL,
    been_at    DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_butterflies_user_post UNIQUE (user_id, post_id),
    CONSTRAINT fk_butterflies_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_butterflies_post FOREIGN KEY (post_id) REFERENCES posts (id) ON DELETE CASCADE,
    CONSTRAINT ck_butterflies_state CHECK (state IN ('WANT', 'BEEN')),
    CONSTRAINT ck_butterflies_been CHECK ((state = 'BEEN') = (been_at IS NOT NULL)
        AND (been_at IS NULL OR been_at >= created_at)),
    INDEX idx_butterflies_cocoon (user_id, state, created_at),
    INDEX idx_butterflies_post (post_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- The Edition, category pages and "From your connections" read posts by audience and the time they became visible.
CREATE INDEX idx_posts_audience_visible ON posts (audience, visible_from);
