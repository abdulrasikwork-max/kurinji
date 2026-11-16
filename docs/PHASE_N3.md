# Phase N3: verified MIET community membership

Status: **approved by the owner on 2026-11-16** (plan + the decisions below). Units B1, B2, B3, B4, A1, A2, C; one
local commit each; never pushed, never deployed. Results per unit under "As built". Direction: `IRL_DIRECTION.md`
§2.5; decisions N-2 to N-6, N-24, N-25; new decisions of this phase: `DECISIONS.md` N-27 onwards.

## What was there before N3
- Identity (N2): an account = a verified phone number + a real name + 18+. `users.role` USER / MODERATOR / ADMIN, read
  from the database per request (`RoleAuthorization`); ADMIN only via SQL; role changes by phone number.
- `SocialAccess.requireMember` checked only "active account + 18+": every signed-up phone could see and post everything.
  `Visibility` knew nothing about a community.
- No community, membership or verification code anywhere (`feature/community` on Android was Nearby, removed in N1).

## Owner's decisions (2026-11-16)
1. **Roll number:** the college roll number printed on the MIET ID card (e.g. `E1236003`). No fixed pattern is
   assumed (NOT the 12-digit university register number). Kept only as an HMAC, never as plain text.
2. **Programmes (closed list, no seat counts):** UG: B.E. Computer Science & Engineering, B.Tech Information
   Technology, B.E. Mechanical Engineering, B.E. Electrical & Electronics Engineering, B.E. Electronics &
   Communication Engineering, B.Tech Artificial Intelligence & Data Science, B.E. Civil Engineering, B.E. Biomedical
   Engineering. PG: M.E. Structural Engineering, M.E. Manufacturing Engineering, M.E. Power Electronics & Drives,
   M.E. VLSI Design, M.E. Computer Science & Engineering, MBA.
3. **Two alternative ways, the applicant chooses ONE:** in person (preferred, the default) or an ID photo (fallback,
   an admin decides). Not a combined process.
4. Membership ends on **31 July of the graduation year**; graduated members lose access (no alumni at launch).
5. A **ban removes MIET community access only**; sign-in, SOS, Safe Walk and the Safety Circle keep working.
6. Unverified people may prepare their own profile draft; no access to the community.
7. No list of verifiers in the app.
8. Verifiers may correct the spelling of the name at approval: logged and shown to the person.
9. Re-linking a phone number is in N3: admin + in person only.
10. Nobody approves their own request.

## Design (as approved, with the details fixed while building)

### States the person sees (`GET /api/community/me`)
| State | Meaning | May use |
|---|---|---|
| `NONE` | signed up, not verified (maybe with the last refusal and its reason) | safety, account, settings, own profile draft, "Get verified" |
| `PENDING` | one open request (method, in-person code or photo uploaded, expiry) | the same + withdraw |
| `MEMBER` | active membership (programme, year, verified on, ends on, staff flags) | the community; name locked |
| `ENDED` | graduated (`GRADUATED`) or ended by an admin (`REMOVED`) | as `NONE`; may ask again (a new year after a PG admission, or after a wrong verification) |
| `BANNED` | an admin's ban on the roll number or the phone number, reason shown | as `NONE`, cannot be verified; **sign-in and safety unchanged** |

### Keys (`community/service/CommunityKeys`)
`COMMUNITY_SECRET` (env, ≥ 32 characters) → four sub-keys by HMAC-SHA256 with fixed labels: roll key, phone key, code
key, photo key (AES-256-GCM). Not derived from `JWT_SECRET` in production (rotating the JWT secret would break every ban
and the one-account-per-roll-number rule); in development an empty secret falls back to a key derived from `JWT_SECRET`
with a warning, and the `prod` profile refuses to start without it (`ProductionGuard`). Losing the secret breaks bans and
uniqueness: it must be backed up apart from the database (N8). Honest limit: roll numbers are short and structured, so
someone holding BOTH the database and the secret could recover them by trying every number; either one alone cannot.

Roll number normalising: upper-case, spaces / `-` / `/` / `.` removed; then 4–15 characters `A–Z 0–9` with at least
one digit. `roll_key = HMAC(rollKey, "MIET:" + normalised)`; `phone_key = HMAC(phoneKey, e164)`.

### In person (default)
1. The applicant: roll number, programme, graduation year → `POST /api/community/verification {method: IN_PERSON, ...}`.
   The app shows an 8-character code (`XXXX-XXXX`, alphabet without 0/O/1/I/L), alive 10 minutes, renewable
   (`POST .../verification/code`); only its HMAC is stored. Screenshots blocked.
2. A verifier (or an admin) types the code: `POST /api/verifier/lookup {code}` → the request's ref, the applicant's
   real name, masked number, programme, year. The lookup **claims** the request for that verifier for 15 minutes; only
   the claimer can decide it. Wrong codes: 10 per 15 minutes per verifier, then 429.
3. The verifier holds the ID card, types the roll number from the card, may correct the name, and decides:
   `POST /api/verifier/requests/{ref}/decision {approve, rollNumber, name?, reason?}`. A roll number that does not match
   the applicant's → 409 (retype or refuse `ROLL_MISMATCH`). Approve → membership; refuse → reason.

### ID photo (fallback)
The same request with `method: ID_PHOTO`, then `PUT /api/community/verification/photo` (JPEG/PNG body): re-encoded
(`ImageSanitizer`, no metadata), encrypted with AES-256-GCM (random 96-bit nonce per file), written to
`app.community.photo-dir` under a random name. Admins only: `GET /api/admin/community/photo-requests` (oldest first),
`GET .../requests/{ref}/photo` (decrypted, `Cache-Control: no-store`), `POST .../requests/{ref}/decision` (same body).
The file is deleted after the decision commits, at withdrawal, at expiry (7 days), and by the hourly sweep for any file
without a request.

### Decisions (both ways)
- Nobody decides their own request; a verifier can decide in-person requests only (their own claim); an admin both.
- A roll number already held by another account → refused `ALREADY_MEMBER` (only after a trusted person saw the ID, so
  nobody can probe roll numbers); a banned roll number or phone → refused `BANNED`.
- Approve: membership (or the person's ENDED row re-used), `users.name_checked_at` set (the name is locked, N-24), a
  corrected name logged as `NAME_CORRECTED` with the old spelling and shown on `/me`.
- The request row is deleted at the decision; the log (`community_actions`) keeps who decided what, when, why.
- Limits: approvals per verifier per day (`app.community.approvals-per-day`, 50), requests per account per day (3).

### Staff
- **Verifier** = a flag on an active membership (`memberships.verifier`), appointed / removed by an ADMIN; ends with the
  membership. `/api/verifier/**` is open to an ADMIN or an active verifier, read from the database per request.
- **ADMIN** stays SQL-only. Root admins verify each other in person (nobody decides their own request).
- No list of verifiers in the app (owner, item 7).

### Bans
`bans` rows hold `roll_key` and/or `phone_key` (never a plain number), a reason code, an optional short note, who and
when, lifted by whom and when. A ban ends the membership at once (`BANNED`), refuses verification for that roll number
or phone, survives the person's account deletion (only the keyed forms remain). It never touches the account, sign-in,
sessions or any safety feature.

### Re-linking a number (N-25)
A person with a new SIM signs in with the new number, gets `SIGN_UP_NEEDED`, and chooses "I'm moving from another
number": `POST /api/community/relink {ticket, rollNumber}` consumes the sign-up ticket and returns a code (1 hour). An
admin, in person with the ID, types the code and the roll number from the card (`POST /api/admin/community/relinks`): the
member's account moves to the new number, every session ends (credentials stamp), the old number is free. The person
then signs in with the new number.

### Membership end
At the end of 31 July of the graduation year, India time (= 18:30 UTC that day): access stops at once (the check reads
`ends_at`); the hourly job marks the row `GRADUATED` and removes a verifier flag.

### Access
`SocialAccess.requireMember` = active account + 18+ + **active membership** (403 `MEMBERSHIP_NEEDED`). Account-level
only (no membership): the caller's own profile and photo (the draft; a suspension still stops changes), the moderation
standing / appeal / review of their own content (no shadow bans, even after a membership ends). `Visibility.Facts` gains
`sameCommunity`: nobody outside the viewer's community sees a profile, post or perspective. Safety never asks
(`CommunityIsolationTest`).

## Migration V34 (development only; the pilot stays on V30)
`communities` (MIET seeded), `memberships`, `verification_requests`, `number_relinks`, `bans`, `community_actions`,
`users.name_checked_at`. Detail in `.claude/rules/database.md`.

## Units (one local commit each)
| Unit | Content |
|---|---|
| B1 | V34, entities, keys, `/api/community/me`, programmes, requests (both ways, code renewal, withdraw), membership gate in `SocialAccess` / `Visibility`, test helpers |
| B2 | Verifier lookup / decision, admin photo route (encryption, serving, deletion), name lock and correction, verifier appointment, log, limits |
| B3 | Bans, membership end (admin, graduation job), re-link, request / photo expiry job, eraser, safety-unchanged tests |
| B4 | V34 on the development MySQL (backup, pre-check, dry run, apply) |
| A1 | Android applicant side |
| A2 | Android staff side |
| C | Documents, full runs, emulator walk |

## As built

### B1 backend: V34, the community core, the membership gate (2026-11-16)
- V34 written (all six tables + `users.name_checked_at`; not applied anywhere yet: B4). Package `community`:
  entities `Community`, `Membership`, `VerificationRequest`, `NumberRelink`, `Ban`, `CommunityAction`, `CommunityEnums`
  (programmes = the owner's 14, methods, end / refusal / ban reasons, log kinds); repositories; `CommunityKeys` (one
  secret, four HMAC sub-keys, AES-256-GCM with the file name as AAD); pure `CommunityRules` (roll normalising without a
  fixed format, 8-character codes without 0/O/1/I/L, 31 July end in India, accepted graduation years); `IdPhotoStore`
  (encrypted `.idp` files, delete after commit, sweep); `MembershipLookup` (THE active-membership answer); applicant
  `VerificationService` + `CommunityController` (`/api/community/me|programmes|verification|verification/code|
  verification/photo`). `config/CommunityProperties` (`app.community.*`), `ProductionGuard.communityProblems`.
- Gate: social knows membership only through its own port `social/service/MembershipGate` (implemented by
  `MembershipLookup`), so `social` never imports `community`. `SocialAccess.requireMember` now needs an active membership
  (403 `MEMBERSHIP_NEEDED`); new `requireAccount` (own profile read / photo removal, moderation standing, appeal, review
  request) and `requireCanEditOwnProfile` (the draft; a suspension still stops it). `Visibility.Facts.sameCommunity`;
  `Relationships` fills it per person and per list.
- `User.nameCheckedAt` (the lock itself is used from B2); `AccountEraser`: the person's membership, request (+ its ID
  photo file), log rows about them and claims they held go; deciders (`memberships.verified_by`, `bans.banned_by|
  lifted_by`, `community_actions.actor_id`) keep a tombstone like moderators (`AccountEraserCoverageTest`).
- Tests: `TestAccounts.registerVerifiedAndLogin` now also seeds an active MIET membership (so every older community test
  keeps its meaning); `registerUnverified` + `support/CommunityClient` for N3; the MIET row comes from
  `src/test/resources/data.sql` (tests run without Flyway). New: `CommunityRulesTest` (5), `CommunityKeysTest` (4),
  `CommunityApplicantApiTest` (8: default in person, code once, no plain roll number or code in the row, one request at
  a time, code expiry, wrong input, ID photo re-encoded + encrypted + replaced + deleted at withdrawal, expired request
  replaced, member refused, banned number refused and shown, the gate both ways, graduation at the minute),
  `CommunityIsolationTest` (5). `VisibilityTest` gained the outsider case.
- Result: backend **516 run, 0 failures, 0 errors, 2 skipped**.
