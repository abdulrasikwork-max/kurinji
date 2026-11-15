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
-- The log entries of decided Phase 7 appeals: a content appeal always names what it was about (target_kind), a
-- Phase 7 one never did.
DELETE FROM moderation_actions WHERE action IN ('APPEAL_UPHELD', 'APPEAL_OVERTURNED') AND target_kind IS NULL;
ALTER TABLE moderation_actions DROP COLUMN signal_category;

-- ---- B2: the Evidence Vault (V9, V10, V11) ---------------------------------------------------------------------------
-- Children first: the custody ledger refers to the items. The encrypted files themselves are not in the database; the
-- evidence folder (EVIDENCE_STORAGE_DIR) is removed by hand, after this migration, where one exists.

DROP TABLE evidence_custody_events;
DROP TABLE evidence_recovery_failures;
DROP TABLE evidence_recovery_envelopes;
DROP TABLE evidence_items;

-- ---- B3: the social add-ons (V22, V28, V29, V30, V31) ----------------------------------------------------------------
-- Reactions, saved posts, today's state, Explore tags, visits with their ratings, and the disclosure flag on posts.
-- Each of these tables only refers to users, posts or places; nothing refers to them.

DROP TABLE butterflies;
DROP TABLE saved_posts;
DROP TABLE daily_states;
DROP TABLE post_facets;
DROP TABLE place_visits;

-- The CHECK is dropped by name first, so nothing depends on MySQL removing a one-column CHECK together with its
-- column (it does: seen in the dry run of 2026-11-15).
ALTER TABLE posts DROP CHECK ck_posts_disclosed;
ALTER TABLE posts DROP COLUMN disclosed;
