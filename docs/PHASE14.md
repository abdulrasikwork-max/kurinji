# Phase 14 — Security, privacy and abuse-resistance audit (2026-10-26)

**Status: implemented, compiled, tested, live-verified on MySQL** (backend, Android, Python suites; live check §4). An audit and hardening
phase: no new feature, no product change, no migration (next migration still **V25**), no new dependency, no Android
change. Real phone, TalkBack and an emulator walk-through were **not** part of this phase (no app code changed).

## 1. Method

Inspected the repository (not the docs alone): `SecurityConfig`, `JwtConfig`, `GlobalExceptionHandler`; every social
service (profiles, connections, invites, blocks/mutes, places, posts, media, perspectives, butterflies/Cocoon,
Discover); `Visibility` / `Relationships`; moderation v2 (`ReportService`, `ContentModerationService`,
`AssessmentService`) and the Python service (`server.py`, `service.py`); `ImageSanitizer` / `MediaStorage`;
`AccountEraser`; every JPQL/native query; the Android manifest, network security config, `AuthInterceptor`, logging,
intent handling and Coil set-up. For every finding: reproduce with a test that fails on the old code → fix → the test
passes → full suites. Severity is not inflated: none of the findings is an IDOR, account takeover or data exposure.

## 2. Findings

| ID | Severity | Finding | Status |
|---|---|---|---|
| P14-1 | Low | Someone's profile post list was ordered and paged by `created_at`, so a delayed exact-place post (Q1, visible 1–3 h later) appeared **below** posts written after it, telling a watcher roughly when the author was at the place | **Fixed** |
| P14-2 | Low | A moderator who wrote a post could open and decide the case about a perspective on that post, and its appeal (conflict of interest: they are a party and can hide it themselves) | **Fixed** |
| P14-3 | Low | Simultaneous writes answered **500**: the same block / mute sent twice at once (unique-key error caught inside a transaction already marked rollback-only), and on MySQL simultaneous reports on one target (InnoDB **deadlocks** on the one-open-case index, measured live; a 500 also lost the report) | **Fixed** |

No Critical, High or Medium findings.

### P14-1 — profile list leaked the hidden creation order
- **Where:** `social/repository/PostRepository.visiblePage`, `PostService.byProfile`.
- **Attack:** watch a person's profile. They share an exact-place Experience at 10:00 (hidden until 11:00–13:00) and a
  no-place post at 10:10 (visible at once). When the exact post appears it is listed under the 10:10 post, so the
  watcher learns it was written before 10:10, i.e. the person was at that named place about then. The repository's
  own comment says others' posts are ordered by `visible_from`, "never by the hidden creation time"; this one list
  was not.
- **Fix:** order and page others' profile lists by `(visible_from, id)` like every other list. The author's own list
  keeps creation order.
- **Test:** `Phase14SecurityRegressionTest.profileList_ordersOthersPostsByWhenTheyCouldSeeThem_notByTheHiddenCreationTime`
  (failed before: the two ids came in creation order).

### P14-2 — post author deciding about a perspective on their post
- **Where:** `ContentModerationService.Loaded.tookPart`, `reportedIt`.
- **Attack:** a moderator is harassed in a perspective on their own post (or dislikes one); they open its case and
  remove it, warn or suspend its writer, and could also decide the writer's appeal.
- **Fix:** for a COMMENT case the post's author "took part" (same 403 and `youTookPart` as the person concerned or a
  reporter); the appeal check uses the same rule.
- **Test:** `moderatorWhoWroteThePost_takesNoPartInACaseAboutAPerspectiveOnIt` (failed before: 200 instead of 403).

### P14-3 — double submissions answering 500
- **Where:** `BlockService.block/mute`, `ReportService.report/openCase`.
- **Cause (blocks/mutes):** `saveAndFlush` breaking a unique key inside the request's transaction marks it
  rollback-only; the code caught the error ("it exists, which is what was asked") and carried on, and the commit then
  failed with `UnexpectedRollbackException` → 500. Reproduced in H2 in 10 of 12 rounds of two simultaneous requests.
- **Cause (reports, MySQL only):** two or more first reports on one post inserted the case at the same moment; InnoDB
  resolved the lock conflict on `uk_content_cases_open_post` as a deadlock (`CannotAcquireLockException`), never
  handled → 500 and the report lost; a same-person double report could also answer 409 instead of the designed
  "always the same 202". First live run (with only a unique-key retry added): 9 of 12 report requests 500; with retries
  alone the retries collided again (409 after three attempts), because each retry read its snapshot before the winner
  committed.
- **Fix:** blocks and mutes write the row on its own first (from then on the Visibility rule already hides
  everything), then remove the connection in a transaction. A report runs in its own READ COMMITTED transaction that
  first locks the reported row (`PostRepository/CommentRepository/ProfileRepository.lockById`, `SELECT … FOR UPDATE`),
  so reports about one target run one after the other and each sees the case and reports committed before it; a
  transaction that still loses a race is retried in a new one (at most three attempts). No migration.
- **Tests:** `doubleBlocksAndMutes_atTheSameMoment_areAllAccepted` (failed before). The report race needs MySQL's V23
  unique keys and InnoDB locking, which the H2 test schema (Hibernate `create-drop`) does not have (H2 even admitted a
  second open case), so it was checked live (§4, L1): 24/24 × 202, exactly one case and three reports per post.

## 3. Verified areas (explicitly checked, no vulnerability)

- **Authentication:** only register/login/refresh/sign-out are public; HS256 only (`macAlgorithm`), issuer + expiry +
  `ActiveAccountCheck` + revoked sessions; a forged/`alg: none` token and no token answer 401 (live L6/L7); generic
  login errors; error bodies never carry exception text (`server.error.include-*=never`, own handler).
- **Authorization / IDOR:** every social read goes through `Visibility` (author / connection / stranger / blocked,
  delay, moderation state); every write is owner-checked (`requireOwn`, `findByPublicIdAndOwnerId`, `onOwnPost`,
  `incomingRequest`, own butterfly rows, own invites); foreign = 404. Media: unattached = owner only, attached = whoever
  can see the post; moderators only through a case they are not part of. Moderation endpoints: role from the DB per
  request (`/api/moderation/**`), plus conflict checks; reports/standing/appeals only for the caller's own rows.
  Live L3–L5.
- **Privacy:** no coordinates in social APIs (cells only; AREA posts never return the place or the 7-char cell; place
  pages list EXACT + Everyone only, after the delay); bands instead of timestamps for others; audience/times only for
  the author; butterfly count author-only from 3; reporters never visible to the reported person; collapse wording
  without reports; held/removed content author-only.
- **Media:** magic bytes only, header size check before decoding (≤ 4096 px), decoder warnings refused, re-encoded JPEG
  without metadata, ≤ 5 MB read with a hard cap, random UUID storage keys validated by regex (no path traversal),
  `no-store`, `nosniff`; files deleted after commit; orphans cleaned up.
- **Input:** all queries are parameterised JPQL / bound native SQL (no string-built SQL); Bean Validation sizes on
  every body; enums parsed with fixed messages; place search normalised to letters/digits.
- **Abuse:** per-person limits for look-ups, requests, invites, redeems, places, posts, photos, perspectives,
  butterflies, Discover reads, searches, reports (20/day); probation limits; sign-in/sign-up limits (Phase 7).
- **Moderation:** the machine never removes or restricts (`DecisionPolicy`, `moderateBySystem`); service outage =
  publish; the Python service binds 127.0.0.1, compares the key with `hmac.compare_digest`, caps bodies at 32 KiB,
  rejects non-string text, never logs text.
- **Account erasure:** `AccountEraser` removes every social/moderation row by or about the person (and others'
  perspectives/butterflies/cases on their posts) and their photo files before the rows; `AccountEraserCoverageTest`
  still passes.
- **Safety boundary:** `SocialIsolationTest` (social never reads safety data and the reverse) passes; social profiles
  expose no Safety Circle, SOS, walk or evidence data; Connections are never Safety Circle members.
- **Android:** only the launcher activity and the QS tile (system-bound permission) are exported; SOS entry not
  exported; no WebView, no logging of requests or tokens, no HTTP logging interceptor; cleartext only in debug builds
  for development hosts; `allowBackup=false` + extraction rules; Coil memory cache only; FLAG_SECURE on sensitive
  screens.

## 4. Live check (real MySQL 8.0, backend with the fixes)

Script outside the repository (PowerShell + `HttpClient`, simultaneous requests); throw-away `live14.*` accounts (30
over all runs), all deleted afterwards with their rows (users back to 170, no orphan cases). For these runs only, the
sign-up limit per address was lifted on the command line (`-Dapp.auth.registrations-per-address=50`, as the tests do).

| Check | Final run |
|---|---|
| L1 six rounds of four simultaneous reports on a new post (two people + one person twice) all 202; one case and 3 reports per post (DB) | **PASS** (24/24; 6 × 1 case / 3 reports) |
| L2 six rounds of simultaneous double blocks and double mutes all 204 | **PASS** (24/24) |
| L3 stranger reading an only-me post → 404 | **PASS** |
| L4 stranger deleting someone's post → 404 | **PASS** |
| L5 ordinary user opening the moderation queue → 403 | **PASS** |
| L6 no token → 401 | **PASS** |
| L7 `alg: none` token → 401 | **PASS** |
| Unhandled exceptions in the server log | **0** |

Earlier runs (disclosed): run 1 L1 FAIL (9 × 500 deadlocks; rounds 4–6 had no post because new accounts may post only
3 times a day, script fault); run 2/3 L1 FAIL (409 after colliding retries; 429s in run 3 because the script ran twice
against one server and the in-memory 20 reports/day carried over). Each led to the fix described in P14-3.

## 5. Remaining risks and recommendations (informational, not changed)

1. **Access token unencrypted on the phone** (known A14); the refresh secret is Keystore-sealed.
2. **`AuthInterceptor` adds the token to any host.** Today every URL is built from the API base URL and OkHttp drops
   `Authorization` on cross-host redirects; scoping the header to the API host is cheap defence in depth.
3. **No application-level JSON body cap** (Jackson's own limits apply) and **image decoding uses up to ~128 MB per
   upload** (4096² ARGB + RGB copy): put a body-size limit and upload concurrency limit on the HTTPS reverse proxy
   required before a pilot (D12).
4. **Rate limits are in memory, per instance**, reset on restart (documented since Phase 7).
5. **Small existence oracles accepted by design:** sign-up 409 for a used e-mail (10 sign-ups/address/hour), handle
   409 when choosing a taken handle, "too many connections" 409 at the other person's cap.
6. **Butterfly differencing:** an author watching 3 → 4 learns that someone new saved it (C2 accepted the floor of 3).
7. **V13 `ck_moderation_cases_state`** has the NULL weakness noted in 13D (Phase 7 frozen; needs a V25 + MySQL dry run).
8. **Release build URL** defaults to `http://10.0.2.2:8080/`; release builds forbid cleartext, so it fails closed. An
   HTTPS URL must be set before any release.

## 6. Results (2026-10-26)

| Suite | Result |
|---|---|
| Backend `mvn test` (H2) | **728 run, 0 failures, 0 errors, 2 skipped** (13F: 725; +3 = `Phase14SecurityRegressionTest`) |
| Regression tests on the code before each fix | P14-1, P14-2, P14-3 (blocks) **failed**, as expected |
| Android `testDebugUnitTest` (forced re-run) | **686 / 0** (no Android change) |
| Android lint | **0 errors / 94 warnings** (unchanged) |
| Python `unittest` (moderation service) | **38 / 38 OK** |
| Live MySQL 8.0 (§4) | **7/7 PASS**, 0 unhandled exceptions |
| Migrations | none added; Flyway validated 24, schema up to date (dev DB v24, next **V25**) |
| Emulator / real phone / TalkBack | **not run** (no app code changed) |

**Not verified:** the report fix under a multi-instance deployment (one server here); MySQL lock behaviour under real
load; anything on a real phone.
