---
paths:
  - "backend/src/main/resources/db/**"
  - "backend/sql/**"
  - "backend/src/main/java/**/entity/**"
---
# Database migrations (moved verbatim from CLAUDE.md §7 on 2026-11-03)

The key rules of every applied migration. Conventions (next file **V30**) stay in `CLAUDE.md` §7. Never edit an applied
migration; verify each new one on real MySQL (backup, CHECK dry run on a scratch copy, apply).

| Migration | Table | Key rules |
|---|---|---|
| V1 | `users` | unique email (case-insensitive collation, stored lower-case), `password_hash` (BCrypt), `enabled` |
| V2 | `safety_contacts` | FK user (cascade); unique `(user_id, phone_number)` with normalized phone; generated `primary_marker` + unique ⇒ max one primary per user; priority CHECK 1–5; max 10 contacts (service rule); disabled contact never primary |
| V3 | `emergency_events` | FK user (cascade); status ACTIVE/CANCELLED/RESOLVED; trigger MANUAL (SHAKE, VOICE, DURESS_PIN reserved and **rejected**); generated `active_marker` + unique ⇒ max one ACTIVE per user; `started_at`/`ended_at` |
| V4 | `emergency_locations` | FK emergency (cascade), **no user_id**; lat/lng `DECIMAL(9,6)` + range CHECKs; `accuracy_meters DECIMAL(7,1)`, `source` CHECK, `battery_percent` 0–100; `recorded_at`/`received_at`; **UNIQUE (emergency_id, recorded_at)** (idempotent re-send; also the read index) |

| V5 | `safe_journeys`, `journey_events` | FK user cascade; status ACTIVE/COMPLETED/CANCELLED/EMERGENCY; generated `active_marker` + unique ⇒ one ACTIVE walk per user; interval CHECK 2–60; escalation 0–3; deadline/ended CHECKs tied to status; last location only (all-or-nothing CHECK) |
| V6 | `escalation_events` + `emergency_events.journey_id` | level 0–4 (5 never stored), reason CHECK; journey/emergency FKs cascade ("at least one" enforced in code: MySQL forbids CHECKs on cascading-FK columns); `journey_id` on emergencies `ON DELETE SET NULL` |

| V7 | `safe_journeys.destination_latitude/longitude` + reasons | `DECIMAL(9,6)`, range CHECKs, both-or-none CHECK; reason CHECK replaced (+`ROUTE_DEVIATION`, `UNEXPECTED_STOP`); coordinates + last location deleted when a walk ends COMPLETED/CANCELLED (kept for EMERGENCY) |

| V8 | `circle_alerts` + reason `DIAL_PROMPT_OPENED` | emergency/journey FKs cascade (exactly one, enforced in code), `contact_id` FK `ON DELETE SET NULL`; kind/channel/status CHECKs; `UNIQUE client_ref` (UUID from the phone); no number, no text |

| V9 | `evidence_items`, `evidence_custody_events` | items: FK emergency cascade, **no user_id**; kind/status/size/lower-case-hex-hash/capture-order CHECKs; SEALED ⇒ sealed_at + all bytes; DELETED ⇔ deleted_at ⇔ no storage_key, no escrow key; `UNIQUE client_ref`, sequence_no NOT unique. Custody: per-user hash chain, `UNIQUE (user_id, sequence_no)`, action CHECK, append-only. Ciphertext lives in files (`app.evidence.storage-dir`), never in MySQL |

| V10 | `evidence_custody_events` | action CHECK replaced (+`VERIFIED`, `DELETE_REFUSED`); index `(user_id, action, occurred_at)` for the deletion lockout count |

| V11 | `evidence_recovery_envelopes`, `evidence_recovery_failures`, `evidence_items.recovery_rk_version/recovery_wrapped_key` | envelope: one per user, kdf CHECK `PBKDF2-SHA256`, iterations 600 000–10 000 000, salt 16–32 B, wrap = 60 B; item copy both-or-none + 60 B; shredded CHECK now also requires no recovery copy; failures cascade from users |

| V12 | `place_signals`, `signal_contributions` | signal: random `public_id`, category CHECK (10 place-only categories), `cell` CHAR(7) geohash (REGEXP CHECK), `area_cell` = LEFT(cell,6) CHECK, **UNIQUE (cell, category)** (reports merge); **no coordinates, no author, no time**. Contribution: FK signal + user cascade, kind REPORT/CONFIRM/NO_LONGER, **UNIQUE (signal_id, user_id)**, `counts_from` = server time + random delay (+ probation), the only time stored. Rows deleted at the end of their lifetime |

| V13 | `users.role`, `contributor_standing`, `signal_flags`, `moderation_cases`, `moderation_actions`, `moderation_appeals`; `place_signals.moderation/moderation_until`; `signal_contributions.credited` | role CHECK (ADMIN only via SQL); standing = counters only (no places/times), halved every 90 days; one flag per person per signal; generated `open_marker` + unique ⇒ one OPEN case per signal; REMOVED ⇔ moderation_until; audit append-only, actor FK without cascade; one appeal per decision; `version` on cases/appeals. Applied + constraint-checked on MySQL 8.0 |

| V14 | `area_patterns`, `place_signals.reached_confirmed` | one decayed value per (6-char area, INCIDENT/CONDITION), PK (area_cell, signal_group), **no FK, no user/signal id**, `DATE` columns (day precision, never returned), CHECKs cell/group/value 0–4/next ≥ decayed; fed only by finished, independently confirmed, unmoderated episodes (≤ 1/week), forgotten < 0.25, reset by any REMOVE. Applied + constraint-checked on MySQL 8.0 |

| V15 | `evaluation_counters`, `moderation_cases.coordination_rule` | counters: PK (week_start, metric, dimension, model_version), `occurrences` ≥ 0; **no user/cell/signal/case column**; metric closed list (binary collation), dimension `^(-\|[A-Z_]+([.][A-Z_]+)?)$` case-sensitive (a geohash cannot be stored), version `^(-\|[a-z]+-[0-9]+([.][0-9]+)?)$`, week_start a Monday; 104-week retention in code. `coordination_rule` LINKED_GROUP/SUPPORT_CORE/EXCLUSIVE_PAIR, COORDINATION cases only, binary. Dry run 19/19 rejected, applied on MySQL 8.0 |

| V16 | `users.credentials_changed_at`, `deletion_requested_at`, `deletion_due_at`, `erased_at` | CHECKs: deletion pair both-or-none, due > requested, erased ⇒ disabled, ⇒ no pending deletion, ⇒ role USER, ⇒ email `erased-%@erased.invalid`; index on `deletion_due_at`. Dry run 13/13, applied on MySQL 8.0 (2026-10-22) |

| V17 | `emergency_events.started_session`, `ended_elsewhere` | session id CHAR(36) binary, lower-case UUID CHECK; only while ACTIVE (CHECK); ended_elsewhere only when not ACTIVE (CHECK). Dry run 7/7, applied on MySQL 8.0 (2026-10-22) |

| V18 | `emergency_events.client_ref`, `started_offline` | Phase 11: phone reference (lower-case UUID, binary, `UNIQUE (user_id, client_ref)`), offline ⇒ reference (CHECK), `ended_at >= started_at` (CHECK). Dry run 10/10 (incl. the real rows), applied on MySQL 8.0 (2026-10-22) |

| V19 | `device_sessions` | C1: one row per sign-in (`session_id` = token `sid`, binary, UUID CHECK, unique), FK user cascade, `secret_hash` + `previous_secret_hash` lower-case hex SHA-256 (never the secret), rotation pair CHECK, `authenticated_at`, sliding `expires_at` > `last_used_at` ≥ `created_at`, revoked pair + reason CHECK (SIGNED_OUT, PASSWORD_CHANGED, REUSE_DETECTED, ACCOUNT_DISABLED, EXPIRED). Dry run 3/3 + 14/14, applied (2026-10-22) |

| V20 | `users.age_confirmed_at`, `profiles`, `connections`, `connection_invites`, `blocks`, `mutes` | 13A: no date-of-birth column; profile handle binary + REGEXP CHECK (ASCII, 3–20, no `..`), previous handle reserved (pair CHECK, ≠ current, unique), text length CHECKs, closed language/interest lists; connections `UNIQUE (user_low, user_high)`, status + responded CHECKs; invites store only a lower-case SHA-256; "not self"/"low < high" in code (MySQL forbids CHECKs on cascading-FK columns). All FKs cascade; `AccountEraser` covers 8 new references. Dry run 30/30, applied (2026-10-22) |

| V21 | `places`, `posts`, `post_media`, `comments` | 13B: places with no creator (`created_on DATE`), unique (cell, normalised name), cell/area CHECKs; posts: type, precision (`location_precision`), area only for AREA, closed city list only for CITY, month (day 1) only and always for EXPERIENCE, ONLY_ME ⇒ NOBODY, `visible_from ≥ created_at`; media: random `storage_key`, `sort_order` 0–5, unique (post, position), ≤ 4096 px, ≤ 5 MiB, SHA-256; comments: kinds, 1–1000 chars, helped only on ANSWER; "place for EXACT/AREA", "one level", "position iff attached" in code. Dry run 79/79, applied (2026-10-22) |

| V22 | `butterflies`, `places.city`, index `posts (audience, visible_from)` | 13C: one row per (person, post) = the Cocoon item, FKs cascade, `state` WANT/BEEN binary CHECK, `(state = BEEN) = (been_at IS NOT NULL)` + `been_at >= created_at` CHECK, UNIQUE (user, post); `places.city` closed list without OTHER (binary CHECK), set by the server from the cell (`CityAreas`); "not own post" and "Experiences only" in code. Dry run 14/14, applied (2026-10-23) |

| V23 | `posts`/`comments` moderation columns, `post_revisions`, `content_cases`, `content_reports`, `social_strikes`, `social_standing`, `moderation_actions.target_kind` | 13D: state NONE/COLLAPSED/HELD/REMOVED + reason + decision UUID (CHECK: NONE ⇒ neither, COLLAPSED ⇒ both or neither, HELD/REMOVED ⇒ both), `approve_first` ⇒ comment policy EVERYONE, `awaiting_approval`; one OPEN case per post / comment / person (`open_marker` + unique), outcome NO_VIOLATION/ACTION_TAKEN with `outcome IS NOT NULL` written out; one report per reporter and target, note only for OTHER (1–200), `counts_from ≥ created_at`; strike weight 1–3, decision unique; one standing row per person, until > decided; audit action/reason lists extended + kind-matches CHECK with `COALESCE` (NULL makes `IN` UNKNOWN, which a MySQL CHECK lets pass — found in the dry run). "Exactly one target" in code. Dry run 51/51 + 18/18, applied (2026-10-24) |

| V24 | `posts`/`comments` `moderation_source`, `moderation_spans`, `strong_language`; `content_cases.origin`; `content_assessments`; V15 metric list + `AI_CHECKED`, `AI_REVIEWED` | 13E: state CHECK replaced (NONE ⇔ no source/reason/decision/spans; REPORTS ⇒ COLLAPSED without reason/decision/spans; SYSTEM ⇒ COLLAPSED/HELD + reason, no decision; MODERATOR ⇒ reason + decision, no spans; `COALESCE(source,'-')` against UNKNOWN), reason list + `AWAITING_CHECK`, spans `start-end` list; origin REPORTS/SYSTEM/AUTHOR_REQUEST; assessments: one per post/comment (unique, FK cascade), status PENDING/DONE/UNAVAILABLE with consistent times/rule, action + rule + reason (`(action = 'PUBLISH') = (reason IS NULL)`), evidence `CATEGORY:sev:T|U:Q|-` list whose categories exclude self-harm, versions V15 pattern, **no text column**. Dry run 59/59 + 19/19, applied (2026-10-25) |

| V26 | `users.phone_e164`, `phone_verified_at`, generated `verified_phone`; `phone_challenges` | Phase 17B: `+91[6-9]` + 9 digits (binary, CHECK), verified ⇒ number, verified ≥ created, tombstone keeps no number; UNIQUE `verified_phone` = one verified account per number; challenges: PK user_id (one per account), lower-case hex SHA-256 token hash (unique), code only as HMAC hex + expiry + send time + number (CHECK together), attempts/sends 0–20, expires > created, FK cascade; **no code, no IP, no text**. Dry run 15/15 rejected + 7/7 accepted + cascade on scratch copies, applied on MySQL 8.0 (2026-10-27, backup taken first) |

| V25 | `users.email_verified_at`, `email_verification_tokens` | E-mail verification: NULL = unverified; existing non-erased accounts set verified at the migration (no production users); CHECK verified ≥ created; index (email_verified_at, created_at); tokens: PK user_id (one live token), UNIQUE lower-case hex SHA-256 (CHECK), expires > created (CHECK), FK cascade, **no token, no IP**. Dry run 11/11 rejected + 6/6 accepted, applied on MySQL 8.0 (2026-10-26, backup taken first) |

| V27 | `post_media.kind`, `duration_ms`, `poster_bytes` | Phase 17C: kind PHOTO/VIDEO (binary, default PHOTO); size CHECK per kind: photo ≤ 5 MiB with no duration/poster, video ≤ 50 MiB with 1–60 500 ms and poster NULL or ≤ 5 MiB, ≤ 4096 px; every NULL case written out; "a video needs its poster before it is attached" in code (no CHECK on the cascading `post_id`). Dry run 15/15 refused + 5/5 accepted on a scratch copy, applied on MySQL 8.0 (2026-11-01, backup taken first) |

| V28 | `cities`, `saved_posts`, `daily_states`, `profiles.photo_key`; `butterflies` = reactions; `places.city`/`posts.city` → FK | Phase 17E: `cities` (code PK binary REGEXP, box CHECKs, OTHER without box) seeded line for line from `cities-v1.tsv` (`CityCatalogTest`); the closed city CHECKs replaced by FKs without referential actions (`ck_places_city` never OTHER, `ck_posts_city` precision rule); `saved_posts` UNIQUE (user, post), FKs cascade, Cocoon rows copied in; `butterflies` emptied, `state`/`been_at` dropped (no reaction invented); `photo_key` lower-case UUID CHECK, unique; `daily_states` user PK, closed mood/intent list, `set_on DATE` (IST day, no time); audit lists + `PERSON_REMOVE_PHOTO` (kind PERSON, COALESCE). Backup first; dry run 8/8 refused; constraint checks 26/26 refused + 11/11 accepted; applied on MySQL 8.0 (2026-11-02) |
| V29 | `post_facets` | Phase 17G-1: one row per (post, tag): `id`, `post_id` FK `posts` ON DELETE CASCADE, `facet` VARCHAR(20) binary NOT NULL with a CHECK of the 13 closed codes (same list and order as `explore-facets-v1.tsv`, `ExploreFacetsParityTest`), UNIQUE (post, facet), index (facet, post); the CHECK is on a non-FK column (MySQL forbids CHECKs on cascading-FK columns); ≤ 4 per post and Experiences only are enforced in code; no user id, no time. Backup first; dry run on scratch tables 14/14 accepted + 9/9 refused; applied on MySQL 8.0.46 (2026-11-03) |

Conventions: never edit an applied migration; next file is **V30**; snake_case; `DATETIME(6)` stored in UTC; constraint
prefixes `uk_`, `fk_`, `ck_`, `idx_`; FKs `ON DELETE CASCADE` (no orphans); InnoDB, utf8mb4_unicode_ci.
