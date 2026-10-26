# Phase 15 — Final integration, acceptance testing and documentation (2026-10-26)

**Status: PARTIAL.** Everything that could be run on this PC was run and passed after fixes: backend, Android, lint,
Python, MySQL, a 119-check live API run against real Spring Boot + MySQL + the real Python service, and an emulator
(API 37) walk-through of the whole product. **Not done, because it cannot be done here:** a real phone (none
connected), TalkBack speech by ear, a production HTTPS deployment. No feature, no product change, no migration (next
still **V25**), no new dependency, no backend change. Six Android integration defects were found and fixed (§5).

## 1. Baseline (inspected before any change)

| Item | Value |
|---|---|
| Branch / commit | `main` at `a81bfe0` (Phase 14), working tree clean, `0 0` ahead/behind `origin/main` (as last fetched) |
| Latest migration | V24 (`flyway_schema_history` 1–24 all `success = 1`); Flyway "Successfully validated 24 migrations" at start-up |
| Schema | 39 tables, 59 foreign keys, 185 CHECK constraints |
| Backend / Android / Python tests | 728 (2 skipped) / 686 / 38 |
| Tools | Java 17.0.20.1, Maven 3.9.16, MySQL 8.0 service running, Android SDK with AVDs, no physical device (`adb devices` empty) |

## 2. Acceptance matrix

PASS = checked in this phase (how is named); FAIL = still failing; NOT VERIFIED = not checked in this phase.
API = live run §4; EMU = emulator walk-through §6; UT = automated tests.

| # | Area | Check | Result |
|---|---|---|---|
| 1 | Auth | Register (18+ only; date of birth not stored), login, wrong password generic 401 | PASS (API AUTH-1..4, EMU sign-up) |
| 2 | Auth | Refresh rotates the secret; sign-out ends the session; its token and refresh then 401 `session_revoked` | PASS (API AUTH-5..8) |
| 3 | Auth | No token / `alg: none` token → 401 | PASS (API AUTH-9, 10) |
| 4 | Auth | Expired-not-revoked ("sign in to sync") on the phone | PASS (UT only; not re-run live) |
| 5 | Profiles | Create, edit, taken handle 409, links refused, exact-handle look-up | PASS (API PROF-1..6, EMU profile) |
| 6 | Connections | Invite code once, redeem, used code 404, request, silent decline, private list without counts | PASS (API CONN-1..7) |
| 7 | Connections | Connections screens on the phone | NOT VERIFIED (EMU: only the You rows) |
| 8 | Blocks / mutes | Block both ways invisible, idempotent, unblock does not reconnect; mute removes from the Edition only | PASS (API BLOCK-1..6, MUTE-1..2) |
| 9 | Experiences | Create (AREA / EXACT / CITY / only-me / connections), no default audience, edit marks edited, widening refused, delete only by author | PASS (API EXP-1..8, OWN-1..2, EMU compose) |
| 10 | Experiences | Exact place delayed 1–3 h; others never see times, coordinates, place of an AREA post or 7-char cell | PASS (API EXP-6, VIS-1..7) |
| 11 | Photos | Upload, unattached = owner only, non-image 400, served as JPEG with `no-store` + `nosniff`, metadata marker stripped | PASS (API MEDIA-0..6, EMU Photo Picker without permission) |
| 12 | Perspectives | Been here too, one-level reply, reply to reply refused, comment policy, hide/unhide, only writer deletes | PASS (API PERS-1..7, EMU add) |
| 13 | Places | Create in Chennai (`city`), same name merges, private names refused, nearby (6-char only), search, place page never lists AREA posts | PASS (API PLACE-1..7, EMU "Places near me") |
| 14 | Discover | Edition with content-only reasons, city OTHER 400, connections list, category page | PASS (API DISC-1..6; DISC-6 see §4), EMU cards / Why? / city chooser |
| 15 | Butterfly / Cocoon | Own post refused, Want → Been, author sees no number under 3, others only their own, Cocoon private, invisible posts skipped | PASS (API BUT-1..7, BLOCK-5, EMU Want / Been / Want again) |
| 16 | Moderation | Report (own 400, invisible 404, repeat same 202), Your reports RECEIVED → REVIEWED / NO_VIOLATION, author sees nothing | PASS (API MOD-1..6, 14, EMU report + Your reports) |
| 17 | Moderation | Queue 403 for members; moderator queue/detail with pseudonyms; decision; second decision 409; reporter cannot open the case (conflict of interest) | PASS (API MOD-7..13) |
| 18 | Moderation | Moderator screens on the phone | NOT VERIFIED (13D/13E emulator runs only) |
| 19 | AI check | Live service: clean text published, new account's photo post for everyone held (author sees why, others 404) | PASS (API AI-1..4, EMU notice) |
| 20 | AI check | Service down: established account published, new account `AWAITING_CHECK`; retried and published after recovery | PASS (API AI-5, 6; DB: all assessments `DONE` after restart) |
| 21 | AI check | Tamil / Tanglish normalisation | PASS (UT: Python 38/38, Kotlin parity vectors); not re-run live |
| 22 | Safety | You → Safety hub, status card, Ready check facts, SOS screen (not started), Safe Walk screen (not started) | PASS (EMU) |
| 23 | Safety | "Going there?" from a post → Safe Walk, nothing starts by itself | PASS (EMU) |
| 24 | Safety | Connections are not Safety Circle members; profiles carry no safety data | PASS (API SAFE-1..2, UT `SocialIsolationTest`) |
| 25 | Safety | SOS countdown, SMS, location, offline SOS, Safe Walk check-ins on a device | NOT VERIFIED in Phase 15 (no safety code changed; earlier phases' emulator runs only) |
| 26 | Safety | Banners (deletion / walk / SOS) on every tab | PASS after fix 15-5 (EMU deletion banner) |
| 27 | Account deletion | Request (UI and API), wrong-password cancel 409, erasure job (5 accounts incl. a moderator: 5 deleted, 0 anonymised): every social / moderation row and the photo files gone, sign-in and old token 401 `account_deleted` | PASS (API DEL-1..4, DB, disk) |
| 28 | Offline | Server unreachable: calm notice, still signed in, Safety hub and SOS screen open | PASS (EMU) |
| 29 | UI | Onboarding, sign-up, Discover, post, composer, Cocoon, You, Safety: no clipping or crash | PASS (EMU, after fixes 15-1..15-4) |
| 30 | UI | Dark mode and 2.0× font on Discover, post, You | PASS (EMU) |
| 31 | Accessibility | Clickables ≥ 48 dp and labelled on Discover, post, composer, Cocoon, You, Safety (uiautomator scan) | PASS (EMU) |
| 32 | Accessibility | Spoken labels without doubled full stops; failures announced | PASS after fixes 15-2, 15-4 |
| 33 | Accessibility | TalkBack speech by ear, keyboard / switch access | NOT VERIFIED |
| 34 | Database | Flyway valid, V1–V24, no orphans, no test data left | PASS (§3) |
| 35 | Release config | Cleartext debug-only, no secrets or logging, restricted data ignored | PASS (§7) |
| 36 | Real phone | Install and main flows on a physical device | NOT VERIFIED — device unavailable |

**Acceptance: 31 of 36 PASS (86 %), 0 FAIL, 5 NOT VERIFIED** (#7, #18, #25, #33, #36).

## 3. Database (MySQL 8.0, dev database `safesphere`)

- Flyway: 24 migrations, all successful, validated at start-up; no migration needed (the V13 NULL CHECK stays a V25
  item, §8). 39 tables / 59 FKs / 185 CHECKs.
- Orphans: no content case without a target, no unattached photo older than a day, no profile without a user.
- After the run: users **170** (as before), 0 `live15.*` / `emu15.*`, places 0, device sessions 1 (the owner's own),
  evaluation counters back to the 14 rows of week 2026-10-19 (this week's rows, written by the test run, deleted).
- **Found, reported, not changed** (not test data of this phase; deleting them is the user's call):
  - **168 leftover test accounts** from Phases 6–9 (`phase6.*` … `phase9.*@example.com`, created 2026-10-20/21).
  - **An ACTIVE SOS (id 24) since 2026-10-20 09:16 UTC on the owner's personal account** (probably from the Galaxy
    A23 test); it would block that account's deletion and password change. End it in the app or say so.

## 4. Live API acceptance (real Spring Boot + MySQL + Python service)

Script outside the repository (PowerShell + `HttpClient`), four throw-away accounts `live15.<run>.{a,b,c,m}`; test-only
SQL on those accounts: `a` moved past the 72 h probation, `m` made MODERATOR, deletions made due (the 7-day grace is not
waited for). Backend started with `-Dapp.account.deletion-interval=PT20S -Dapp.auth.registrations-per-address=50`;
a random 40-character service key in the environment only (deleted afterwards).

| Group | Checks | Result |
|---|---|---|
| Auth | 13 | 13 PASS |
| Profiles (9), places (7), photos (7), experiences + visibility + ownership (18) | 41 | 41 PASS |
| Connections, perspectives, butterfly / Cocoon | 21 | 21 PASS |
| Discover | 6 | 5 PASS, **DISC-6 FAIL → script fault**: the category page answers plain posts (`PostPage`, as `API.md` says); the script expected `{post, reasons}`. Re-checked by hand: it lists the Everyone post and not the Connections one |
| Report + moderation | 15 | 15 PASS |
| Block / mute, safety boundary | 10 | 10 PASS |
| AI check with service up / down | 6 | 6 PASS (+ retry after recovery checked in the DB) |
| Deletion | 7 | 7 PASS |

**119 checks, 118 PASS at the first assertion, 1 script fault re-verified by hand. 0 unhandled exceptions in the
server log** (only the two expected "service unavailable" warnings of the outage test). Disclosed script faults that
stopped earlier attempts before they recorded results: PowerShell's read-only `$home` (CLAUDE.md §12), a CRC helper
overflowing `0xEDB88320` in PowerShell 5.1.

## 5. Bugs found and fixed (all Android, all found in the emulator walk-through)

| ID | Symptom | Root cause | Fix | Regression test | Verification |
|---|---|---|---|---|---|
| 15-1 | Onboarding page dots drawn as three tall touching ovals | A `TabLayout` tab background is stretched to the whole 24 × 48 dp tab; the 8 dp oval had no fixed bounds | Dots are a `layer-list` item, 8 dp, centred | `Phase15IntegrationFixesTest.onboardingDots_stayDots_insideTheTab` | EMU screenshot |
| 15-2 | Tapping "Add" under a post seemed to do nothing (e.g. "Make your profile first") | The failure notice is at the top of a long page, off-screen | A new failure scrolls the notice into view and is announced for TalkBack | `…postScreen_bringsNewFailuresIntoView_…` | EMU: notice visible after Add |
| 15-3 | Cocoon → I've been → "Add a perspective" opened the post at the top, kind not chosen | The Cocoon path only opened the post (the post screen's own path preselected) | Post opened with `ARG_WRITE_BEEN_HERE`: once the perspectives' rows are committed, "Been here too" is chosen and the box is scrolled into view and focused | same test | EMU screenshot |
| 15-4 | "Your reports" rows said "Shared this week"; screen readers read "list.." / "end.. In an area" | The post band strings were reused; parts ending in "." were put into "%1$s. %2$s." | `TimeBandTexts.sent` ("Sent this week…"); `SpokenText.part` drops a part's own full stop (feature rows, Cocoon items, Nearby signals) | `…yourReports_sayWhenTheReportWasSent_notShared`, `…spokenParts_…` | EMU dumps |
| 15-5 | After asking to delete the account (or starting a Safe Walk) on a screen without banners, **no banner appeared on any tab** until the next state change or a restart | `sessionBanners` visibility was recomputed only when the banner state changed; returning to a tab did not re-apply it | The last "any banner due" is kept and re-applied on every screen change | `…sessionBanners_areReappliedOnEveryScreenChange` | EMU: deletion banner on Discover right after asking |
| 15-6 | The deletion explanation named only safety data | Text from Phase 10A, before the social layer | Names profile, experiences, photos, perspectives, connections and Cocoon too (all erased: §4 DEL, §3) | `…deletionExplainer_namesTheSocialContentToo` | Account screen |

15-5 is the only one touching the safety layer's visibility; the safety logic itself did not change. No new security
defect was found; Phase 14 was not reopened.

## 6. Emulator walk-through (Medium Phone API 37, fresh install, fresh account `emu15.a`)

Onboarding (3 pages, honest identity line) → sign-up with the date picker → Discover (city chooser, Chennai edition,
photo card, postmark, tip ticket, Why?) → post → 🦋 Want → Perspectives (failure without profile, 15-2) → You → profile
→ Share (Photo Picker without permission, place via "Places near me" with in-context location rationale and one-time
grant, no audience preselected, AREA default) → own post held for a moderator with the ochre notice and author-only
tally → Cocoon (Want, I've been, invitation, 15-3) → report → Your reports → You → Safety (hub, Ready check, SOS
screen, Safe Walk) → "Going there?" → Safe Walk → dark mode + 2.0× text → touch-target scan → server unreachable →
account deletion through the UI → banner (15-5). Every step PASS after the fixes. Not walked: Connections screens,
moderator screens, Private places, settings toggles, the SOS countdown itself (not started on purpose).

## 7. Release configuration

- Cleartext only in the **debug** source set (`10.0.2.2`, `localhost`, `127.0.0.1`); release has no network security
  config, so Android's default (no cleartext, targetSdk 35) applies. Release URL defaults to `http://10.0.2.2:8080/` and
  therefore fails closed. **Production HTTPS: NOT CONFIGURED — release deployment phase.** No production URL was invented.
- No `Log.*` / `println` in app code, no HTTP logging interceptor; backend `server.error.include-*=never`, show-sql off.
- Secrets only from the environment / git-ignored `backend/.env`; `android/local.properties` ignored (it currently
  points at `127.0.0.1` for adb-reverse testing); no keystore, `.pem`, `.env` or logs tracked.
- `moderation-service/restricted/*` git-ignored; only its README is tracked.
- **Release build not ready** (known): `isMinifyEnabled = false`, no signing config, `versionName "0.1.0-phase1"`.

## 8. Phase 14 remaining items — classification

| # | Item | Classification |
|---|---|---|
| 1 | Access token unencrypted on the phone (A14) | Fix before pilot/release |
| 2 | `AuthInterceptor` adds the token to any host | Fix before pilot/release (cheap defence in depth) |
| 3 | No app-level body cap; ~128 MB per image decode | Release configuration item (HTTPS proxy limits, D12) |
| 4 | In-memory rate limits per instance | Accepted V1 trade-off (single instance); revisit with more than one instance |
| 5 | Existence oracles (e-mail 409, handle 409, connection cap) | Accepted V1 trade-off |
| 6 | Butterfly 3 → 4 differencing | Accepted V1 trade-off (C2) |
| 7 | V13 `ck_moderation_cases_state` NULL weakness | Fix in V25 (needs a MySQL dry run; not required for acceptance) |
| 8 | Production HTTPS URL | Release configuration item |

## 9. Remaining risks

- **Release blockers:** production HTTPS + reverse proxy limits (D12); release signing and shrinking; token
  encryption (A14); real-phone verification; Kurinji trademark / domain / store checks (D1).
- **Pilot blockers:** the A23 / real-device sprint (D10); the lexicon's review by ≥ 2 native speakers (13E); TalkBack
  by ear; a deliberate decision about the 168 old test accounts and the open SOS in the dev database (§3).
- **Known technical debt:** toasts cut the report confirmation to two lines ("Nobody is told who …"); offline, the
  Discover city hint says the profile names no city (it could not be loaded); Discover does not retry by itself after
  the connection returns; perspective "More" buttons are all labelled "More"; V13 CHECK (V25); lint 94 warnings.
- **Accepted V1 trade-offs:** §8 items 4–6.
- **Not verified:** real phone; TalkBack speech; keyboard / switch access; SOS countdown, SMS, location and Safe Walk
  check-ins on a device in this phase; moderator and Connections screens on the phone in this phase; multi-instance
  behaviour; real-world moderation precision.

## 10. Results

| Suite | Result |
|---|---|
| Backend `mvn test` (H2) | **728 run, 0 failures, 0 errors, 2 skipped** (no backend change) |
| Android `testDebugUnitTest` (forced re-run) | **692 / 0** (686 + 6 in `Phase15IntegrationFixesTest`) |
| Android lint | **0 errors / 94 warnings**, 0 unused resources |
| Python `unittest` | **38 / 38 OK** |
| MySQL | Flyway 24 valid, no migration, no orphans, test data removed (§3) |
| Live API | **119 checks: 118 PASS + 1 script fault re-verified** (§4) |
| Emulator (API 37) | Walk-through PASS after 6 fixes (§5, §6) |
| Real phone | **NOT VERIFIED — device unavailable** |
| Accessibility | Labels, touch targets, 2.0× text, dark mode PASS; TalkBack speech NOT VERIFIED |

## 11. Documentation changes

`docs/PHASE15.md` (new), `CLAUDE.md` (status row, docs list), `docs/HANDOFF.md` (Phase 15 entry).

## 12. Git

Branch `main`; one commit on top of `a81bfe0` ("Phase 15: final integration and acceptance testing"), not pushed. Local
was level with `origin/main` before the commit (as last fetched), so it is 1 ahead afterwards.

**Ready for Phase 16?** Yes for work that does not need a device or a server (for example V25, A14, the
`AuthInterceptor` scope); **not** for a pilot or release until the release blockers in §9 are closed.
