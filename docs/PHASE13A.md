# Phase 13A — Identity foundation: results

**Date:** 2026-10-22 · **Plan:** `docs/PHASE13A_PLAN.md` (approved by the user with P1–P8 as recommended) ·
**Design:** `docs/EXPERIENCE_PLATFORM_DESIGN.md`.

| Part | Status |
|---|---|
| V20 + backend `social` package + age gate | **Implemented, compiled, tested; V20 dry run 30/30 on MySQL, applied (v20); live API 12/12** |
| Android: date of birth at sign-up, `feature/social` (4 screens) | **Implemented, compiled, tested; emulator (API 37) VERIFIED E1–E7** (one bug found and fixed, §5) |
| Real phone (Galaxy A23), TalkBack speech, dark mode, 320 dp / large fonts | **NOT VERIFIED** (A23 sprint before any pilot, D10) |
| Content (posts, places, butterflies), reports, AI, new UI | Not part of 13A (13B–13F) |

Nothing is committed (git is not on the PATH of this environment).

## 1. What was built

**Age (D6).** Registration requires a date of birth; `AgePolicy` (18+ on the server's UTC date, not in the future, not
over 120 years) runs on the phone and the server; the date is checked and **discarded** — only `users.age_confirmed_at`
is kept. Accounts created before V20 confirm once (`POST /api/users/me/age-confirmation`). Social endpoints refuse an
unconfirmed account (409); the safety features never ask.

**Profiles.** Unique lower-case ASCII handle (look-alike letters impossible), changeable once per 30 days, the old one
reserved for its owner for 30 days; display name (separate from the account name, which stays in Safety Circle SMS);
bio, city, closed lists of languages and interests. Text cleaned (NFC, invisible characters removed), links refused.
Look-up by exact handle or public id only, 120 per hour.

**Connections.** Mutual, one row per pair (`UNIQUE (user_low, user_high)`). Request / accept / silent decline / cancel /
silent remove; asking back connects. Invite codes: 128-bit, Crockford base 32, shown once, SHA-256 stored, single use,
7 days, ≤ 5 active. Blocks (two-way invisible, remove connection and requests, silent) and mutes (one-sided, silent).

**The one visibility rule** (`social/policy/Visibility`, design §11.2): a profile is visible to its owner; to anyone else
only while the owner's account is active and neither has blocked the other. Every 13A read path uses it.

**Android.** Register screen date picker (text-entry mode, opens at the latest 18+ date, future dates disabled). Profile
tab row "Your profile and connections" (P7) → Connections (own profile, find by handle, requests both ways, connections,
invite codes with share/copy, redeem, blocked & muted) → Profile form (age confirmation for old accounts, handle,
name, bio, city, language/interest chips) → Person profile (Connect / Accept / Request sent / Remove / Mute / Block) →
Blocked and muted. Nothing is stored on the phone; no new dependency or permission.

## 2. Deviations from the plan (and why)

| # | Plan said | Built | Reason |
|---|---|---|---|
| A1 | CHECKs "not self", "low < high", "requester in pair" | Enforced in code (entities + services) and tested | MySQL refuses CHECKs on columns of a cascading FK (the V6 finding) |
| A2 | Generated `pair_low/pair_high` columns | Plain `user_low/user_high` set by the entity | Same uniqueness on MySQL; H2 (tests) now enforces it too |
| A3 | `connection_invites.used_by` | Not stored; `used_at` only | The connection created is the record; one fewer reference to a person |
| A4 | Blocks/mutes PK `(a, b)` | Surrogate `id` + `UNIQUE (a, b)` | Simpler JPA mapping; same guarantee |
| A5 | Relationship NONE/REQUESTED/REQUEST_RECEIVED/CONNECTED | + `SELF` | Looking up one's own handle needs an honest answer |
| A6 | Crossing requests connect | Also when the caller earlier declined the other's request | Changing one's mind should connect, not create a second row |
| A7 | — | Sending a request, creating or redeeming an invite needs a profile | The other person must see who it is |
| A8 | — | Blocking works on any existing profile id, even someone who blocked you | A protective act must not depend on visibility |
| A9 | "today" | The server's UTC date (phone uses the same) | Phone and server never disagree; a birthday after midnight IST may wait until 05:30 |
| A10 | `AgePolicy` in `social.policy` | Yes; the HTTP wrapper `AgeGate` is in `user.service` | Registration lives in `auth`/`user` |

## 3. Tests

| Suite | Result |
|---|---|
| Backend `mvn test` | **569 tests, 0 failures, 0 errors, 2 skipped** (501 before; +68: AgePolicy 4, AgeGate API 6, HandlePolicy 4, ProfileTextPolicy+Visibility 7, Profile API 10, InviteCodes 3, Connection API 10, Invite API 7, BlockMute API 6, FourUser 6, Isolation 3, AccountDeletion 2) |
| Android `testDebugUnitTest` | **592 tests, 0 failures** (561 before; +31: Register 2 new, SocialPolicy 7, SocialDtos 3, SocialViewModels 15, SocialTexts 4) |
| Android lint | **0 errors, 89 warnings** (85 before; +4 = `accessibilityHeading` unused below API 28 in the new layouts, the same warning the existing layouts carry). One lint error (the attribute inside a style) was fixed with a `values-v28` style |
| `AccountEraserCoverageTest` | Passes with the 8 new references; deleted and anonymised accounts leave no social rows |

## 4. MySQL and live API

- **V20 dry run (MySQL 8.0, scratch `dry_*` tables, 2 throwaway users, all dropped): 30/30** — 5 valid rows accepted;
  24 violations each rejected by the intended constraint (handle: upper-case, leading digit, `..`, trailing `.`, too
  short, Tamil letters; duplicate handle; second profile; previous-handle pair and ≠ current; blank name; unknown and
  upper-case language; lower-case interest; upper-case public id; lower-case status; answered pair; pending with answer;
  answer before request; second row for a pair; upper-case hash; expiry before creation; duplicate block; unknown user);
  deleting a user cascades its connections, blocks and mutes and keeps the other person's rows. One case was first
  written so that two CHECKs failed at once (MySQL names the first); rewritten to isolate the status CHECK.
- **Applied:** Flyway "Successfully applied 1 migration … now at version v20". Next migration **V21**.
- **Live API on MySQL: 12/12** (under-18 refused with no account; adults `ageConfirmed`; profiles with a Tamil name and a
  lower-cased handle; exact look-up with only the allowed fields; no prefix search; request → silent decline → sender
  still sees REQUESTED; asking back connects; invite stored only as SHA-256, redeem connects, second use 404; block hides
  both ways and removes the connection; V20 in Flyway, no date-of-birth column, 18 CHECKs; MySQL refuses a bad handle;
  `age_confirmed_at` set). A first run failed 5 checks because the script was given the wrong folder for `.env`
  (script fault, disclosed); its accounts were removed by the second run's clean-up.
- **Clean-up:** every `live13a.*` and `emu13a.*` account deleted; afterwards profiles, connections, invites, blocks and
  mutes are all 0. One older device session of a non-test account (created before this work) was left untouched.

## 5. Emulator (API 37, Medium Phone), driven by adb

| # | Check | Result |
|---|---|---|
| E1 | Sign-up shows the date field and "…is not stored"; picker opens in text mode; Oct 23 2008 → "You must be 18 or older"; May 14 1998 → account created, Home | PASS |
| E2 | No profile → "Make your profile first"; handle `1asha` refused on the phone; valid profile saved; Connections shows name and handle, empty lists without counts | PASS |
| E3 | Find `@bala_emu13a` → profile → Connect → disabled "Request sent"; the server shows the incoming request | PASS |
| E4 | Bala accepts (API) → Connections lists Bala; row reads "Open the profile of Emu bala" for TalkBack | PASS |
| E5 | Create invite → dialog with the code in groups of five, Share / Copy / Done, "cannot be shown again"; Chitra redeems it typed in lower case with spaces → connected; Bala re-using it → 409 (already connected) | PASS |
| E6 | Block from a profile → confirmation ("They are not told") → back to Connections; Blocked list with labelled Unblock; unblock confirmation "Your connection does not come back" | PASS **after a fix** |
| E7 | Mute from a profile → Muted list with Unmute; the unblocked person shows "Connect" (not reconnected) | PASS |

**Bug found on the emulator and fixed:** after Block, the app went back two screens (to Profile) instead of one. The
`closed` one-shot was never consumed, so the next state (the toast marked shown) closed a second screen.
`PersonProfileViewModel.onClosedHandled()` now consumes it; regression assertion in `SocialViewModelsTest`; re-checked
on the emulator (back to Connections).

During the run the per-address sign-up limit (Phase 7, 10 per hour) was reached by the test accounts; the backend was
restarted to clear the in-memory counter. This is the existing limit working, not a 13A issue.

**Not verified:** TalkBack speech (only labels were read from the accessibility tree), dark mode, 320 dp / 2.0× font,
the share sheet's target apps, the real phone.

## 6. Security and privacy review

- Identity only from the JWT; another person's private lists have no endpoint; invisible = 404 everywhere (four-user test).
- No coordinates, no counts, no presence anywhere in 13A answers (field allow-list tests).
- The date of birth is never stored or logged (`RegisterRequest.toString`, `AgeConfirmationRequest.toString`, DTOs on the
  phone redact it; a test checks no `%birth%` column exists).
- Invite codes: 128 bits, hashed, single use, expiring, rate-limited; every unusable code answers the same 404.
- Handle look-up: exact only, rate-limited, so the member list cannot be scraped; a blocked person can infer a block
  from a 404 (the limitation every platform has; documented).
- Profile text is unmoderated until 13D/13E: mitigated by length limits, no links, closed lists, and no pilot before 13D.
- `SocialIsolationTest`: `social` never references the safety packages and the reverse; connections are never trusted people.
- Account deletion: the eraser and its coverage test include every new reference.

## 7. Viva notes

- *Why store no date of birth?* Only "is this person 18+" is needed; a date of birth is identifying data with no use after the check.
- *Why ASCII handles?* So `аsha` (Cyrillic а) cannot pose as `asha`; display names may use any script.
- *Why is a decline silent?* A visible decline is a signal someone can watch and pressure; the sender just keeps waiting.
- *Why one row per pair?* The unique index makes "both people ask at the same moment" impossible to turn into two rows.
- *Why hash invite codes?* A stolen table gives nobody usable codes; 128 random bits make a fast hash enough.
