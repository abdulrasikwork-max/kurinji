-- Phase 17G-1 (Explore, decision G-1): optional tags ("Good for...") on experiences, so Explore has something to match.
--
-- One row = one tag on one post. The tag list is CLOSED: the CHECK below lists the same 13 codes as
-- explore/explore-facets-v1.tsv (ExploreFacetsParityTest compares the two). At most 4 per post, and Experience posts only,
-- are enforced in code: a CHECK cannot count rows or read another table. The CHECK is on a column that is not part of the
-- foreign key, so MySQL accepts it next to ON DELETE CASCADE. No user id and no time are stored: a tag is a property of
-- the post, and the post (with its audience, moderation state and visibility) stays the one place that decides who may
-- see anything. Explore only ever reads tags of posts the viewer may read.
--
-- Existing posts simply have no tags. Nothing else in the schema changes.

CREATE TABLE post_facets (
    id      BIGINT      NOT NULL AUTO_INCREMENT,
    post_id BIGINT      NOT NULL,
    facet   VARCHAR(20) COLLATE utf8mb4_bin NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_post_facets_post_facet UNIQUE (post_id, facet),
    CONSTRAINT fk_post_facets_post FOREIGN KEY (post_id) REFERENCES posts (id) ON DELETE CASCADE,
    CONSTRAINT ck_post_facets_facet CHECK (facet IN ('QUIET', 'LIVELY', 'BUDGET_FRIENDLY', 'GOOD_WITH_FRIENDS',
        'GOOD_ALONE', 'FAMILY_FRIENDLY', 'OUTDOOR_SEATING', 'VEGETARIAN_OPTIONS', 'EASY_TO_REACH', 'STEP_FREE_ACCESS',
        'OPEN_LATE', 'SCENIC', 'GOOD_FOR_WORK')),
    INDEX idx_post_facets_facet (facet, post_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
