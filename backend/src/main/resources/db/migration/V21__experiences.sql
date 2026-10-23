-- Phase 13B: experiences (docs/PHASE13B_PLAN.md, docs/EXPERIENCE_PLATFORM_DESIGN.md §3.2-§3.5, §4).
-- Design rules:
--   * No coordinates anywhere. A place is a name, a category and a ~150 m geohash cell (7 characters) chosen on the
--     phone; an AREA post keeps only the 6-character area (~1.2 km), never the 7-character cell.
--   * Places record no creator (Q12): only the day they were created. Creating a place is not a record of who was where.
--   * Exact times are stored for ordering and moderation; the API returns bands to everyone but the author.
--   * visible_from: an EXACT-place post appears to others a random 1-3 hours after it was written (Q1); otherwise it is
--     the creation time.
--   * Photos are files in app.social.media-dir with random UUID names (storage_key), re-encoded by the server without
--     metadata; a row without post_id is an upload not yet attached (deleted after 24 h by SocialMediaCleanupJob).
--   * No counts of anything are stored.
--   * Column names: "precision" and "position" are MySQL keywords, so they are location_precision and sort_order.
--   * MySQL refuses CHECK constraints on columns used by a cascading or SET NULL foreign key (the V6 finding), so
--     "EXACT/AREA need a place", "a photo has a position exactly when it is attached", "replies are one level deep",
--     "REPLY needs a parent" and "not merged into itself" are enforced in code (PostService, CommentService,
--     PlaceService) and tested there.

CREATE TABLE places (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    public_id       CHAR(36)    COLLATE utf8mb4_bin NOT NULL,
    name            VARCHAR(60) NOT NULL,                        -- cleaned (PlaceNamePolicy), as the person wrote it
    normalized_name VARCHAR(60) COLLATE utf8mb4_bin NOT NULL,    -- lower-case, letters and digits only: de-duplication
    category        VARCHAR(20) COLLATE utf8mb4_bin NOT NULL,
    cell            CHAR(7)     COLLATE utf8mb4_bin NOT NULL,    -- geohash, ~150 m
    area_cell       CHAR(6)     COLLATE utf8mb4_bin NOT NULL,    -- = LEFT(cell, 6)
    created_on      DATE        NOT NULL,                        -- UTC day; no time, no creator
    merged_into     BIGINT      NULL,                            -- set by moderators (13D) when two places are one
    PRIMARY KEY (id),
    CONSTRAINT uk_places_public_id UNIQUE (public_id),
    CONSTRAINT uk_places_cell_name UNIQUE (cell, normalized_name),
    CONSTRAINT fk_places_merged_into FOREIGN KEY (merged_into) REFERENCES places (id) ON DELETE SET NULL,
    CONSTRAINT ck_places_public_id CHECK (
        REGEXP_LIKE(public_id, '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$', 'c')),
    CONSTRAINT ck_places_name CHECK (CHAR_LENGTH(name) BETWEEN 2 AND 60 AND CHAR_LENGTH(TRIM(name)) = CHAR_LENGTH(name)),
    CONSTRAINT ck_places_normalized_name CHECK (CHAR_LENGTH(normalized_name) BETWEEN 1 AND 60),
    CONSTRAINT ck_places_category CHECK (category IN ('FOOD_CAFES', 'NATURE_OUTDOORS', 'CULTURE_HERITAGE',
        'MUSIC_NIGHTLIFE', 'STUDY_WORK', 'DAY_TRIPS', 'ACTIVITIES_SPORT', 'HOBBIES_MAKING', 'CITY_LIFE', 'HIDDEN_GEMS')),
    CONSTRAINT ck_places_cell CHECK (REGEXP_LIKE(cell, '^[0-9b-hjkmnp-z]{7}$', 'c')),
    CONSTRAINT ck_places_area_cell CHECK (area_cell = LEFT(cell, 6)),
    INDEX idx_places_area (area_cell)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE posts (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    public_id          CHAR(36)     COLLATE utf8mb4_bin NOT NULL,
    author_id          BIGINT       NOT NULL,
    post_type          VARCHAR(10)  COLLATE utf8mb4_bin NOT NULL,
    body               TEXT         NOT NULL,
    tip                VARCHAR(300) NULL,                         -- "Tip for the next person" (Experience only)
    category           VARCHAR(20)  COLLATE utf8mb4_bin NULL,
    place_id           BIGINT       NULL,                         -- EXACT and AREA only (code)
    location_precision VARCHAR(5)   COLLATE utf8mb4_bin NOT NULL,
    area_cell          CHAR(6)      COLLATE utf8mb4_bin NULL,     -- AREA only: the place's 6-character area
    city               VARCHAR(20)  COLLATE utf8mb4_bin NULL,     -- CITY only (closed list)
    experienced_month  DATE         NULL,                         -- first day of the month (Experience only)
    daypart            VARCHAR(9)   COLLATE utf8mb4_bin NULL,
    audience           VARCHAR(11)  COLLATE utf8mb4_bin NOT NULL,
    comment_policy     VARCHAR(11)  COLLATE utf8mb4_bin NOT NULL,
    created_at         DATETIME(6)  NOT NULL,
    visible_from       DATETIME(6)  NOT NULL,                     -- when others may see it (Q1 delay for EXACT)
    edited_at          DATETIME(6)  NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_posts_public_id UNIQUE (public_id),
    CONSTRAINT fk_posts_author FOREIGN KEY (author_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_posts_place FOREIGN KEY (place_id) REFERENCES places (id) ON DELETE SET NULL,
    CONSTRAINT ck_posts_public_id CHECK (
        REGEXP_LIKE(public_id, '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$', 'c')),
    CONSTRAINT ck_posts_type CHECK (post_type IN ('EXPERIENCE', 'ASK')),
    CONSTRAINT ck_posts_body CHECK (CHAR_LENGTH(body) BETWEEN 1 AND 5000
        AND (post_type <> 'ASK' OR CHAR_LENGTH(body) BETWEEN 10 AND 1000)),
    CONSTRAINT ck_posts_tip CHECK (tip IS NULL OR (post_type = 'EXPERIENCE' AND CHAR_LENGTH(tip) BETWEEN 1 AND 300)),
    CONSTRAINT ck_posts_category CHECK (category IS NULL OR category IN ('FOOD_CAFES', 'NATURE_OUTDOORS',
        'CULTURE_HERITAGE', 'MUSIC_NIGHTLIFE', 'STUDY_WORK', 'DAY_TRIPS', 'ACTIVITIES_SPORT', 'HOBBIES_MAKING',
        'CITY_LIFE', 'HIDDEN_GEMS')),
    CONSTRAINT ck_posts_precision CHECK (location_precision IN ('EXACT', 'AREA', 'CITY', 'NONE')),
    CONSTRAINT ck_posts_area_cell CHECK ((location_precision = 'AREA') = (area_cell IS NOT NULL)
        AND (area_cell IS NULL OR REGEXP_LIKE(area_cell, '^[0-9b-hjkmnp-z]{6}$', 'c'))),
    CONSTRAINT ck_posts_city CHECK ((location_precision = 'CITY') = (city IS NOT NULL)
        AND (city IS NULL OR city IN ('CHENNAI', 'COIMBATORE', 'MADURAI', 'TIRUCHIRAPPALLI', 'SALEM', 'TIRUNELVELI',
            'VELLORE', 'ERODE', 'THOOTHUKUDI', 'THANJAVUR', 'PUDUCHERRY', 'BENGALURU', 'OTHER'))),
    CONSTRAINT ck_posts_month CHECK ((post_type = 'EXPERIENCE') = (experienced_month IS NOT NULL)
        AND (experienced_month IS NULL OR DAY(experienced_month) = 1)),
    CONSTRAINT ck_posts_daypart CHECK (daypart IS NULL OR daypart IN ('MORNING', 'AFTERNOON', 'EVENING', 'NIGHT')),
    CONSTRAINT ck_posts_audience CHECK (audience IN ('EVERYONE', 'CONNECTIONS', 'ONLY_ME')),
    CONSTRAINT ck_posts_comment_policy CHECK (comment_policy IN ('EVERYONE', 'CONNECTIONS', 'NOBODY')
        AND (audience <> 'ONLY_ME' OR comment_policy = 'NOBODY')),
    CONSTRAINT ck_posts_times CHECK (visible_from >= created_at AND (edited_at IS NULL OR edited_at >= created_at)),
    INDEX idx_posts_author (author_id, created_at),
    INDEX idx_posts_area (area_cell, visible_from)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE post_media (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    public_id   CHAR(36)    COLLATE utf8mb4_bin NOT NULL,
    owner_id    BIGINT      NOT NULL,
    post_id     BIGINT      NULL,                                -- NULL = uploaded, not attached yet
    sort_order  TINYINT     NULL,                                -- 0-5 once attached (code: set exactly then)
    storage_key CHAR(36)    COLLATE utf8mb4_bin NOT NULL,        -- random file name chosen by the server
    width       INT         NOT NULL,
    height      INT         NOT NULL,
    bytes       INT         NOT NULL,
    sha256      CHAR(64)    COLLATE utf8mb4_bin NOT NULL,        -- of the stored (re-encoded) file
    created_at  DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_post_media_public_id UNIQUE (public_id),
    CONSTRAINT uk_post_media_storage_key UNIQUE (storage_key),
    CONSTRAINT uk_post_media_position UNIQUE (post_id, sort_order),
    CONSTRAINT fk_post_media_owner FOREIGN KEY (owner_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_post_media_post FOREIGN KEY (post_id) REFERENCES posts (id) ON DELETE CASCADE,
    CONSTRAINT ck_post_media_public_id CHECK (
        REGEXP_LIKE(public_id, '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$', 'c')),
    CONSTRAINT ck_post_media_storage_key CHECK (
        REGEXP_LIKE(storage_key, '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$', 'c')),
    CONSTRAINT ck_post_media_sort_order CHECK (sort_order IS NULL OR sort_order BETWEEN 0 AND 5),
    CONSTRAINT ck_post_media_size CHECK (width BETWEEN 1 AND 4096 AND height BETWEEN 1 AND 4096
        AND bytes BETWEEN 1 AND 5242880),
    CONSTRAINT ck_post_media_sha256 CHECK (REGEXP_LIKE(sha256, '^[0-9a-f]{64}$', 'c')),
    INDEX idx_post_media_owner (owner_id, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE comments (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    public_id           CHAR(36)      COLLATE utf8mb4_bin NOT NULL,
    post_id             BIGINT        NOT NULL,
    author_id           BIGINT        NOT NULL,
    parent_id           BIGINT        NULL,                      -- NULL = top level; replies are one level deep (code)
    kind                VARCHAR(9)    COLLATE utf8mb4_bin NOT NULL,
    body                VARCHAR(1000) NOT NULL,
    hidden_by_author_at DATETIME(6)   NULL,                      -- the post's author hid it (its writer is told)
    helped_at           DATETIME(6)   NULL,                      -- the asker marked it "This helped" (ANSWER only)
    created_at          DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_comments_public_id UNIQUE (public_id),
    CONSTRAINT fk_comments_post FOREIGN KEY (post_id) REFERENCES posts (id) ON DELETE CASCADE,
    CONSTRAINT fk_comments_author FOREIGN KEY (author_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_comments_parent FOREIGN KEY (parent_id) REFERENCES comments (id) ON DELETE CASCADE,
    CONSTRAINT ck_comments_public_id CHECK (
        REGEXP_LIKE(public_id, '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$', 'c')),
    CONSTRAINT ck_comments_kind CHECK (kind IN ('BEEN_HERE', 'QUESTION', 'REPLY', 'ANSWER')),
    CONSTRAINT ck_comments_body CHECK (CHAR_LENGTH(body) BETWEEN 1 AND 1000),
    CONSTRAINT ck_comments_helped CHECK (helped_at IS NULL OR kind = 'ANSWER'),
    CONSTRAINT ck_comments_times CHECK ((hidden_by_author_at IS NULL OR hidden_by_author_at >= created_at)
        AND (helped_at IS NULL OR helped_at >= created_at)),
    INDEX idx_comments_post (post_id, created_at),
    INDEX idx_comments_author (author_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
