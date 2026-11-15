-- Phase N1 (docs/PHASE_N1.md, decisions N-9 to N-12): the new product direction retires Nearby signals with their trust
-- and moderation cases, safety intelligence, the evaluation counters, the Evidence Vault, and the social add-ons
-- (Explore tags, visits and ratings, reactions, saved posts, today's state, the disclosure flag).
--
-- This migration only removes. It cannot be undone: take a backup first (deploy/scripts/restore-rehearsal.sh on the
-- pilot; mysqldump on a development database). Children are dropped before their parents. Nothing that the kept
-- features use is touched: users, sessions, phone verification, safety contacts, emergencies, journeys, escalations,
-- circle alerts, profiles, connections, blocks, mutes, places, posts, media, comments, content moderation.

-- ---- B1: Nearby signals (V12, V13), safety intelligence (V14), evaluation (V15) --------------------------------------

DROP TABLE moderation_cases;
DROP TABLE signal_flags;
DROP TABLE signal_contributions;
DROP TABLE contributor_standing;
DROP TABLE place_signals;
DROP TABLE area_patterns;
DROP TABLE evaluation_counters;

-- The audit log is shared with content moderation and stays. Its Phase 7 entries (decisions about Nearby signals and
-- community pauses) refer to cases that no longer exist; they go, and their appeals go with them
-- (fk_moderation_appeals_action cascades). Role changes and every content decision stay.
DELETE FROM moderation_actions WHERE action IN ('QUARANTINE', 'DISMISS', 'REMOVE', 'RESTRICT');
ALTER TABLE moderation_actions DROP COLUMN signal_category;
