-- Phase 17G-2 (U5, "I went" + guarded ratings): a private record that a person went to a place, with an optional 1-5 rating on
-- the same row.
--
-- One row = "I went" (private to its owner, like saved_posts: nobody, not even a moderator screen, lists who went where). The optional
-- rating lives on the same row, so a rating cannot exist without "I went" BY CONSTRUCTION. A person has at most one row per place.
-- The public number is only ever an average computed from these rows (RatingPolicy: at least 5 raters, Bayesian, one decimal,
-- recomputed at most once a day, no count, no names, no times); nothing here is served to anybody but its owner.
--
-- Both CHECKs are on columns that are not part of a foreign key, so MySQL accepts them next to ON DELETE CASCADE (a CHECK on a
-- cascading-FK column is forbidden). The two CHECKs together: the rating is NULL or 1..5, and rated_at is set exactly when a
-- rating is. "I went" is self-declared: there is no proof of a visit (documented limit).
--
-- Existing data is untouched; nothing else in the schema changes.

CREATE TABLE place_visits (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    user_id    BIGINT      NOT NULL,
    place_id   BIGINT      NOT NULL,
    rating     TINYINT     NULL,
    created_at DATETIME(6) NOT NULL,
    rated_at   DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_place_visits_user_place UNIQUE (user_id, place_id),
    CONSTRAINT fk_place_visits_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_place_visits_place FOREIGN KEY (place_id) REFERENCES places (id) ON DELETE CASCADE,
    CONSTRAINT ck_place_visits_rating CHECK (rating IS NULL OR rating BETWEEN 1 AND 5),
    CONSTRAINT ck_place_visits_rated_at CHECK ((rating IS NULL AND rated_at IS NULL) OR (rating IS NOT NULL AND rated_at IS NOT NULL)),
    INDEX idx_place_visits_place_rating (place_id, rating)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
