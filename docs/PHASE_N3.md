# Phase N3: verified MIET community membership

Status: **approved by the owner on 2026-11-16** (plan + the decisions below). Paused after B3 and resumed the same day; B1–B4 done (B4 = V34 on the development MySQL only); A1, A2, C follow. Units B1, B2, B3, B4, A1, A2, C; one
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

### B2 backend: decisions, ID-photo review, name lock, verifiers, log (2026-11-16)
- `DecisionService`: `lookup` (the code → name, masked number, programme, year; never the roll number; claims the
  request for the one who typed it; wrong codes 404 like expired ones, then 429 per decider), `decideInPerson` (only the
  claimer, within `claim-lifetime`), `photoQueue` / `photo` / `decideByPhoto` (admins only). One decision path: approve
  needs the roll number typed from the card (HMAC must match the applicant's, else 409 and a counted try), a corrected
  name is checked by `RealNamePolicy`; then automatic refusals `BANNED` (roll or phone) and `ALREADY_MEMBER` (roll held by
  another account); a verifier's approvals are capped per 24 h (admins exempt); membership created or the person's ended
  row re-used; `NAME_CORRECTED` logged with the earlier spelling; `users.name_checked_at` set; the request deleted and its
  photo file deleted after commit. Nobody decides their own request (also at lookup and photo view). A unique-key race
  on the roll number rolls the whole decision back and answers 409 (Phase 14 rule).
- `CommunityAdminService`: verifiers list / appoint / remove by phone number (active members only; logged), admin name
  correction (logged, shown), the log (50 per page, names as they are now). `VerifierController` (`/api/verifier/**`)
  and `CommunityAdminController` (`/api/admin/community/**`; the photo with `no-store`, `nosniff`, inline).
- Security: `RoleAuthorization.verifier()` = ADMIN or an active verifier, read from the database per request;
  `SecurityConfig` maps `/api/verifier/**` to it. `AccountService.changeName` refuses a checked name: 409 `NAME_LOCKED`
  (`NameLockedException`).
- Tests: `CommunityDecisionApiTest` (9: approval with a corrected name + lock + log; wrong roll 409 then refusal shown;
  claim belongs to the typer and ends after 15 min; wrong-code limit; only verifiers/admins, removal effective at once,
  bootstrap of two root admins verifying each other, nobody verifies themselves, verifiers must be members;
  ALREADY_MEMBER and BANNED; daily cap with admins exempt; an ended membership re-verified on the same row; ID photo
  admin-only, no-store, deleted at approval and at refusal; admin name correction).
- Result: backend **525 run, 0 failures, 0 errors, 2 skipped**.

### B3 backend: bans, membership end, re-link, upkeep job (2026-11-16)
- `BanService`: `POST /api/admin/community/bans {phone?, rollNumber?, reason, note?}` (only HMACs stored; ends the
  membership of every account it hits as BANNED, removes a verifier flag, deletes an open request + its photo, logs
  BANNED; an admin cannot ban themselves), `GET .../bans` (never the numbers; the current member hit by the roll number
  is named), `POST .../bans/{id}/lift` (logged; the person may ask again, the membership stays ended until a new
  decision), `POST .../members/end {phone}` (REMOVED, logged). A ban never touches the account, its sessions or safety.
- `RelinkService` (N-25): `POST /api/community/relink {ticket, rollNumber}` (public; the new number's sign-up ticket is
  the credential and is used up; answers a code alive 1 h), `POST /api/admin/community/relinks {code, rollNumber}` (the
  roll typed from the ID must match the person's AND a member's; refused for a banned roll and for the admin's own
  account; wrong tries limited) → `AccountService.moveToNumber` (refused while an SOS or a Safe Walk runs; another
  account on the number refused; new credentials stamp + every device session revoked) and RELINKED logged.
- `CommunityUpkeepJob` (the only `@Scheduled` in `community`, hourly, `app.community.job-enabled`): expired requests
  (+ photos), expired re-link codes, graduates marked GRADUATED at their `ends_at` (verifier flag removed, logged with no
  actor), stray ID-photo files older than 1 h swept. Counts-only logging.
- `SecurityConfig`: `POST /api/community/relink` permitted (ticket = credential).
- Tests: `CommunityBanRelinkApiTest` (7: roll ban ends the membership while the same token, a new sign-in, SOS, Safe
  Walk and a Safety Circle contact keep working, and the roll cannot be verified again; phone ban refuses asking,
  deletes the open request + photo, safety works, bad ban input refused, non-admin 403; lift → ENDED → re-verified;
  the ban survives the account's deletion and the same number can sign up again (safety works) but not be verified;
  admin end → REMOVED, logged; the upkeep job: graduate + verifier flag, expired request + photo, stray file, each
  graduate once; re-link: ticket used up, wrong roll 409, non-admin 403, refused during an SOS, moved after it, old
  token 401, new number signs in to the same member account, roll without member refused).
- Result: backend **532 run, 0 failures, 0 errors, 2 skipped**.

### B4 Migration V34 (2026-11-16, DEVELOPMENT MySQL 8.0.46 only; the pilot is NOT migrated)
- **Backup first:** `C:\Users\ABDUL RAFIQ\Desktop\SafeSphere-Phase3\db-backups\safesphere-dev-before-v34-20261116.sql`
  (`mysqldump --single-transaction`, 29 tables, 208 905 bytes; outside the repository; plain, same disk).
- **Pre-check (read-only):** Flyway at V33, 29 tables; none of the six new tables and no `users.name_checked_at`
  exist. Data: 207 accounts, 25 profiles, 12 posts, 75 sessions, 73 SOS records, 5 Safety Circle contacts.
- **Dry run on scratch copies** (`zz_v34_*` = V34's DDL with renamed tables and constraint names, plus a full copy of
  `users` with the V34 `ALTER`s; all dropped afterwards, table count back to 29): **84 cases, 21 accepted and 63 refused
  as expected, 1 unexpected** (below). Accepted: a name check after creation, an erased account without one, a second
  community, memberships running / ended (GRADUATED) / with a verifier flag, requests in person with a code and by photo
  with and without the file, a re-link, bans on a roll / a phone / both (one lifted), log rows (VERIFIED,
  NAME_CORRECTED with its old spelling, REFUSED, ENDED without an actor, BANNED). Refused, each by the intended
  constraint: a name check before creation or on an erased account; a duplicate or lower-case or digit community
  code, a blank name; a second membership per account, a roll key held by another account, an upper-case / short roll
  key, an unknown or lower-case programme, an unknown method, year 1999, `ends_at = verified_at`, `updated_at` before
  `verified_at`, `ended_at` without a reason and the reverse, reason `EXPIRED`, an end before verification, a verifier
  flag without its date and the reverse, on an ended membership, value 2; requests: a second per account, upper-case or
  duplicate public id, method `PHONE`, year 2101, no lifetime, a code hash without expiry and the reverse, a code on an
  ID_PHOTO request, an upper-case and a duplicate code hash, a photo on an IN_PERSON request, a photo name without its
  time, a path-shaped photo name, a non-hex roll key; re-links: duplicate number or code, a 5-series and a US number,
  non-hex code / upper-case roll key, no lifetime; bans without any key, with a plain roll number or phone number as the
  key, reason `SPAM` / lower case, a blank or 201-character note, a lift before the ban; log: NAME_CORRECTED without
  detail, detail on VERIFIED, unknown / lower-case action, method `EMAIL`, reason `SPAM`, an unknown actor; deleting an
  account that decided something (no cascade: `AccountEraser` tombstones it). Cascades: deleting a claimer set
  `claimed_by` to NULL; deleting a member removed their membership, request and the log rows about them.
- **The unexpected case:** `programme = 'MBA '` (trailing space) was ACCEPTED. Cause: `utf8mb4_bin` is a PAD SPACE
  collation, so `'MBA ' IN ('MBA', ...)` is true. The same holds for every closed list since V20; only hand-written SQL
  can produce it (the backend writes enum names; Hibernate would fail loudly reading it). Not changed; recorded as a
  gotcha in `.claude/rules/database.md` and for the owner in `HANDOFF.md`.
- **Applied** by starting the N3 jar once (its V34 compared byte for byte with the source): "Successfully validated 34
  migrations", "Migrating schema to version 34 - community membership", "now at version v34", "Started" (one harmless
  MySQL warning: integer display width of `TINYINT(1)`). The development-key WARN for `COMMUNITY_SECRET` appeared, as
  expected. Afterwards 35 tables, the 30 new CHECKs present, MIET seeded, data counts unchanged (207 / 25 / 12 / 75 /
  73 / 5), no membership, no name checked.
- **Entities against the migrated schema:** all 55 columns of the six community entities (42 named + 13 implicit) and
  `users.name_checked_at` exist in MySQL.
- **API on real MySQL (curl):** existing account 397 ("Mysql Check") signs in → `/api/community/me` NONE, 14
  programmes, `/api/connections` 403 `MEMBERSHIP_NEEDED`, `/api/safety-contacts` 200 → IN_PERSON request with
  `E 999-0001` → PENDING (row: lower-case hex roll key and code hash, no readable roll number) → a new account 400
  ("Admin Check", made-up number ending 3333) made ADMIN by SQL → a wrong code 404, the applicant on the verifier route
  403 → lookup 200 (name, masked number) → a wrong roll typed 409 → approval with `e9990001` and the corrected name
  "Mysql Checked" 200 VERIFIED → `/me` MEMBER, `nameLocked` true, corrected from "Mysql Check" → `/api/connections`
  200 → `PUT /api/users/me/name` 409 `NAME_LOCKED` → the admin log shows VERIFIED + NAME_CORRECTED; `ends_at`
  2027-07-31 18:30 UTC; the request row deleted. (A first run of the script read the community's `"code":"MIET"` as the
  request code: its lookup answered 404, nothing was decided, and the still-unlocked name of 397 was changed by its
  name step; restored by SQL before the second run.)
- **Real-MySQL test:** `MySqlTimestampStorageTest` with `SAFESPHERE_MYSQL_TESTS=true`: 2 run, 0 failures.
- Development data now: account 397 is a MIET member (BTECH_IT 2027, name "Mysql Checked"); account 400 "Admin Check"
  is ADMIN; accounts 396 "Nila Check" and 399 "Meera Test" stay unverified for the emulator walk (unit C). An older
  jar (V33 or before) now refuses to start against the development database. The pilot stays on V30.

### A1 Android: the applicant side (2026-11-16)
- New package `feature/membership` (not `feature/community`, which was Nearby): `domain` (`MembershipState` NONE /
  PENDING / MEMBER / ENDED / BANNED, `VerificationMethod`, `Programme`, `CommunityStatus`, `OpenRequest` (code hidden in
  `toString`), `Outcome`, `NameCorrection`, `RelinkCode`, `MembershipRepository`; pure `RollNumberPolicy` = the server's
  normalising with the server's test cases, `ShortCode`), `data` (`MembershipApi` = `/api/community/me|programmes|
  verification|verification/code|verification/photo|relink`; `RemoteMembershipRepository`, DTOs fail closed: an unknown
  state reads NONE, an unknown method drops the request, a malformed code is not shown, staff flags default false; a roll
  number that fails the phone's check is never sent; `IdPhotoFiles`), `ui` (`MembershipViewModel` + `MembershipFragment`,
  `RelinkViewModel` + `RelinkFragment`, `MembershipLabels`, pure `MembershipDates`).
- **Membership screen** (`membershipFragment`, task screen, FLAG_SECURE): NONE / ENDED = the form: ONE way, in person
  preselected ("recommended") or a photo of the ID card; roll number (no autofill, no suggestions), programme and year
  chosen from lists (years of the chosen programme only), a line on how the roll number is kept. PENDING in person = the
  code large (spoken letter by letter), "This code works for N more minutes", "Show a new code"; the code lives only in
  the view model and is dropped once the server says it ran out. PENDING by photo = "Take the photo" / "Take it again",
  what happens to the photo; the request's end date; "Withdraw the request" (confirmed). MEMBER = community, programme,
  year, "runs until 31 July YYYY" (the India date, whatever the phone's zone), the name correction ("it was …") and the
  name-lock line. Refusals, ends and bans are told with their reason; a ban adds "Sign-in, SOS, Safe Walk and your Safety
  Circle keep working". Moderation-style amber notices only.
- **ID photo:** `ActivityResultContracts.TakePicture` into `cache/id-photo/<uuid>.jpg` through a NEW `FileProvider`
  (`${applicationId}.idphoto`, not exported, `res/xml/id_photo_paths.xml` = that one cache folder); NO `CAMERA`
  permission. `PhotoPreparer.prepare` re-encodes (upright, ≤ 3072 px, no metadata); the file is deleted in a `finally`
  right after preparing (sent or not), on a cancelled camera, and by `sweep()` when the screen opens with no camera in
  progress (a process death while the camera was open). Only when no camera app exists is the Photo Picker offered (then
  the app writes nothing). Upload = `PUT /api/community/verification/photo` (JPEG).
- **You:** a membership card under the profile head (Not verified yet / Waiting for verification / Verified MIET member
  + programme and year / Membership ended / No access to the MIET community + the safety line) with "Get verified" or
  "Open". Only the person's own state.
- **403 `MEMBERSHIP_NEEDED`:** `core/network` `MEMBERSHIP_NEEDED` + `Failure.needsMembership()`; Home, the composer,
  Connections and a person's profile show the server's words plus a "Get verified" button (Home hides "Try again" then).
  The social code only navigates to `R.id.membershipFragment`; no social or safety file imports `feature.membership`.
- **Name lock (N-24):** `AccountViewModel` reads `nameLocked` from `/api/community/me` at each start (new optional
  `membership` parameter); locked = the name field disabled, "Save name" hidden, the helper says an IRL admin corrects it;
  a 409 `NAME_LOCKED` also locks it.
- **Re-link (N-25):** "I'm moving from another number" on "About you" → `relinkFragment` (signed out, FLAG_SECURE,
  added to `NavigationRules.signedOutDestinations`): the roll number → `POST /api/community/relink` with the sign-up
  ticket held in `PendingVerification` (used up; cleared on success, on 400 = ticket ended, and on 409) → the code (1 h)
  + "sign in with this number when the admin has moved your account" → "Sign in" goes to the number screen.
- Palette: `membership` joined the social palette list (`SocialPalette.socialFeatures`).
- Guards changed (inside the approved plan, to be recorded in C): `Phase17FGuardsTest` "no file provider is left" →
  exactly ONE provider, the ID-photo one, not exported; `SocialTextsTest` keeps forbidding "verified" on social texts
  except the membership words (`action_get_verified`, `membership_*`, `you_membership_*`), which `MembershipTextsTest`
  guards instead (no "safe" claim outside "Safe Walk" / "Safety Circle", no "trusted member", "guarantee", "background
  check", "police", "aadhaar", "report").
- Tests: new `MembershipTest` (14: roll rules with the server's cases, short codes, the 31 July date, the gate code only
  on a 403, DTOs fail closed, the form never sends a bad roll, the code shown once / kept while alive / dropped / renewed,
  the ID photo prepared + sent + its file deleted also when unreadable, unsent or cancelled, withdraw, re-link with and
  without a ticket, the name lock from `/me` and from a 409) and `MembershipTextsTest` (6); fakes in
  `testing/FakeMembershipParts.kt`.
- Result: Android **786 run, 0 failures, 0 errors, 0 skipped**; `assembleDebug` OK; lint **0 errors, 95 warnings**
  (89 before; the 6 new are `accessibilityHeading` below API 28, the pattern used on every screen). Not seen on a screen
  yet (unit C).

### A2 Android: the staff side (2026-11-16)
- Entry: "Settings and more" gains a **Community staff** section, shown only when `/api/community/me` says `staff`
  (convenience only; the server checks the role on every call). A verifier sees one row, **Verify someone**; an admin
  also sees **ID photos to check**, **Verifiers**, **Bans**, **Other admin actions** and **Community log**. No list of
  verifiers exists anywhere else (owner's item 7).
- `domain/Staff.kt` (`RequestDetail` (name hidden in `toString`), `RefusalReason` = the six a person may choose,
  `BanReason`, `Decision`, `VerifierRow`, `BanRow`, `LogRow`, `StaffRepository`); `data/remote/StaffApi.kt` (the
  `/api/verifier/...` and `/api/admin/community/...` endpoints); `RemoteStaffRepository`: codes accepted as `ABCD-2345`
  or loosely typed and checked against the server's alphabet, roll numbers and mobile numbers normalised on the phone
  (a bad one is never sent), a ban needs a number or a roll number, the note trimmed and capped at 200; answers fail
  closed (an unknown outcome is a failure, never an approval; unreadable rows dropped). No answer carries a roll number.
- **Verify someone / an ID photo** (`staffDecideFragment`, `DecideFragment` + `DecisionViewModel`, FLAG_SECURE): in
  person the code first (the request is then claimed for this person for 15 minutes by the server), then the name,
  programme, year, masked number and a check list ("the photo is this person, the name matches, it is a MIET ID card"),
  the roll number typed from the card, the name as on the card only when spelled differently (sent only if it differs),
  Approve, or Refuse with one of six reasons; a 409 (wrong roll) keeps the details to retype or refuse; done = the
  outcome, then "Verify the next person" (nothing about the last person kept). By photo (admins, opened from the
  queue): the photo is fetched as bytes, decoded into the view with `BitmapFactory.decodeByteArray`, never written,
  never through Coil, and dropped from memory at the decision and when the screen closes; Approve stays disabled until
  the photo is shown (Refuse, e.g. "cannot be read", works without it); a request decided or withdrawn meanwhile = 404.
- **Lists** (`staffListFragment`, `StaffListFragment` by mode, FLAG_SECURE, title from the row): Verifiers (number →
  "Make a verifier" / "Remove as verifier", confirmed; the list with masked numbers and "since"), Bans (number and/or
  roll number, reason chosen from the nine, optional note; confirmed; the list with what it is on, the member it hit,
  who and when, "Lift" confirmed), ID photos (oldest first; a row opens the decision), Community log (50 a page, "Show
  more", actions in words, "automatic" for the upkeep job, the old spelling of a corrected name).
- **Other admin actions** (`staffActionsFragment`, FLAG_SECURE, each confirmed): move an account to a new number (the
  code on the new phone + the roll number from the card, N-25), end a membership (by number), correct a checked name.
- Strings: `staff_*` (the ban text says sign-in, SOS, Safe Walk and the Safety Circle keep working and the person sees
  the reason). Guards: `MembershipTextsTest` +1 (staff screens FLAG_SECURE; no file, cache, Coil, MediaStore or
  DataStore in the decision code; ban and verifier texts; no "safe" claim; no roll number in any staff answer).
- Tests: new `StaffTest` (8: codes, nothing malformed sent + numbers normalised + note capped, unknown outcome =
  failure + rows dropped + photo bytes, in person flow with and without a corrected name, 409 keeps the details then a
  refusal with its reason, by photo the photo held then dropped and a vanished request = 404, admin checks on the phone
  + ban reloads the list, the log pages until a short page); fake `testing/FakeStaffParts.kt`.
- Result: Android **795 run, 0 failures, 0 errors, 0 skipped**; `assembleDebug` OK; lint **0 errors, 97 warnings**
  (+2: `accessibilityHeading` below API 28, `Overdraw` on the ripple background of a staff row). Not seen on a screen
  yet (unit C).
