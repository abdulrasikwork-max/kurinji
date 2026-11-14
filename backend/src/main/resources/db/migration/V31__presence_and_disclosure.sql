-- Phase 18A-2 (trust rules A3 + A5, docs/PHASE18_TRUST_PLAN.md): two yes/no facts, nothing else.
--
-- place_visits.at_place  "Been here" was tapped while the phone was in the place's ~150 m cell or one of the cells around it.
--                        The phone MAY send its 7-character cell with the tap; the server compares it with the place's cell and
--                        keeps only this one bit. The cell itself is never stored. Private like the rest of the row: served to
--                        nobody (not even to its owner); read only by PlaceRatings, where a confirmed visit weighs more.
--                        Optional and self-reported (a mock location defeats it): it raises the cost of a fake rating, it is
--                        not proof. 0 = not confirmed, which is also what every existing row becomes.
--
-- posts.disclosed        The author said "I was paid, or got this free". Shown as a label to everyone who can read the post;
--                        the author's rating for that place is then not counted in the place's public average (still saved,
--                        still shown to the author). Self-declared. Can be turned on later, never off (enforced in code).
--
-- Both columns are NOT NULL (no NULL case to write out) and neither is part of a foreign key, so MySQL accepts the CHECKs next to
-- the ON DELETE CASCADE keys of these tables. No new table: account erasure already covers both tables.

ALTER TABLE place_visits
    ADD COLUMN at_place TINYINT(1) NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_place_visits_at_place CHECK (at_place IN (0, 1));

ALTER TABLE posts
    ADD COLUMN disclosed TINYINT(1) NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_posts_disclosed CHECK (disclosed IN (0, 1));
