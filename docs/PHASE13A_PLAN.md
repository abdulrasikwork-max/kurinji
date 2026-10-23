# Phase 13A — Identity foundation: implementation plan

**Date:** 2026-10-22 · **Status: approved by the user (P1–P8 as recommended) and implemented the same day; results and
the deviations from this plan (A1–A10) are in `docs/PHASE13A.md`.**
Design: `docs/EXPERIENCE_PLATFORM_DESIGN.md` (§3.1, §4, §11, §12). Decisions used: D4, D5, D6, D7, D11, D13, D14.

## 1. Goal and scope

After 13A, a signed-in person can: confirm they are 18+ (new sign-ups at registration), create a profile with a
handle, find another person by exact handle, send / accept / decline / cancel a connection request, connect through a
one-time invite code, remove a connection, block and mute people — with **one visibility rule** deciding who can see a
profile, tested with four users (owner, connection, stranger, blocked).

**Not in 13A:** posts, places, photos, comments, butterflies, Discover (13B/13C); reporting people (13D); AI (13E); the
new design system and navigation (13F); QR codes (§8, P1). Nothing in the safety stack changes.

## 2. Plan-level choices (recommended defaults; the user may change any)

| # | Choice | Recommendation | Why |
|---|---|---|---|
| **P1** | QR codes for connecting | **Defer** (invite code + share sheet + exact handle instead) | QR needs a new dependency (generation + scanning) and scanning needs the camera or Play services' code scanner; only Coil is approved (D7) |
| **P2** | What the server keeps from the date of birth | **Nothing but `age_confirmed_at`**: the DOB is checked (≥ 18) and discarded | Minimum data; the product only needs to know the person declared 18+ |
| **P3** | Display name | **Separate from the account name** (copied from it at first, editable) | The account name is used in Safety Circle SMS and should stay the real name; the social name is the person's choice |
| **P4** | Handles | **ASCII `a–z 0–9 _ .`, 3–20 chars, starts with a letter; change once per 30 days; the old handle stays reserved for 30 days** | ASCII stops look-alike impersonation (Tamil/Latin/Cyrillic homoglyphs); reservation stops grabbing a handle someone just left |
| **P5** | Invite codes | **Redeeming = connected at once** (the inviter approved by sharing); single use, 7 days, ≤ 5 active | Fastest honest path for friends; codes are 128-bit random, stored hashed |
| **P6** | Declining a request | **Silent**: the requester keeps seeing "Requested" until they cancel | Declining must not be a signal anyone can watch |
| **P7** | Where it lives in the app until 13F | **A new row "Your profile and connections" in the current Profile tab**, screens in the current design system | 13F rebuilds navigation; 13A must still be testable on the emulator |
| **P8** | Profile of an account pending deletion | **Still visible; hidden once disabled or erased** | A pending deletion can be cancelled |

## 3. Database: migration V20 (`V20__social_identity.sql`)

All tables InnoDB, utf8mb4_unicode_ci, `DATETIME(6)` UTC, FKs to `users` `ON DELETE CASCADE`.
**MySQL dry run before applying** (H2 does not check CHECKs, generated columns or REGEXP).

| Change | Columns and constraints |
|---|---|
| `users.age_confirmed_at` | `DATETIME(6) NULL`. Set when the person declared a date of birth ≥ 18 years ago. **No date of birth column.** |
| `profiles` | `id`, `public_id CHAR(36)` (random UUID, unique, binary), `user_id` (unique, FK), `handle VARCHAR(20) COLLATE utf8mb4_bin` (unique; CHECK `^[a-z][a-z0-9_.]{2,19}$`, no `..`, not ending in `.`), `previous_handle` + `previous_handle_until` (both-or-none CHECK; unique `previous_handle`), `handle_changed_at`, `display_name VARCHAR(40)`, `bio VARCHAR(160) NULL`, `languages VARCHAR(40)` and `interests VARCHAR(200)` (closed code lists, comma-separated, CHECK pattern), `city VARCHAR(60) NULL`, `created_at`, `updated_at` |
| `connections` | `id`, `public_id` (unique), `requester_id` FK, `addressee_id` FK, generated `pair_low = LEAST(...)`, `pair_high = GREATEST(...)`, **UNIQUE (pair_low, pair_high)** (one row per pair, under concurrency), status `PENDING` / `ACCEPTED` / `DECLINED` (CHECK), CHECK `requester_id <> addressee_id`, `requested_at`, `responded_at` (NULL ⇔ PENDING, CHECK), index `(addressee_id, status)` |
| `connection_invites` | `id`, `public_id`, `owner_id` FK, `code_hash CHAR(64) COLLATE utf8mb4_bin` (unique, lower-case hex SHA-256 CHECK), `created_at`, `expires_at` (> created_at CHECK), `used_at NULL`, `used_by` FK `NULL` (both-or-none CHECK) |
| `blocks` | `blocker_id` FK, `blocked_id` FK, PK `(blocker_id, blocked_id)`, CHECK not self, `created_at`, index `blocked_id` |
| `mutes` | `muter_id` FK, `muted_id` FK, PK `(muter_id, muted_id)`, CHECK not self, `created_at` |

`AccountEraser`: new `DELETED_REFERENCES` (`profiles.user_id`, `connections.requester_id`, `connections.addressee_id`,
`connection_invites.owner_id`, `connection_invites.used_by`, `blocks.blocker_id`, `blocks.blocked_id`,
`mutes.muter_id`, `mutes.muted_id`) and matching `DELETE` statements in `OWNED_DATA` (run for deleted **and**
anonymised accounts). `AccountEraserCoverageTest` fails until this is done (by design).

## 4. Backend (`com.safesphere.backend.social`)

One new package, the usual layout:

| Area | Files (planned) |
|---|---|
| `entity` | `Profile`, `Connection`, `ConnectionStatus`, `ConnectionInvite`, `Block`, `Mute` |
| `repository` | One per entity; owned reads always filtered by user |
| `policy` (pure, unit-tested) | `HandlePolicy` (format, reserved words such as admin / moderator / support / safesphere / kurinji / official, normalisation), `AgePolicy` (≥ 18 on the server's UTC date, not in the future, not > 120 years ago), `ProfileTextPolicy` (NFC, control and zero-width characters removed, no URLs in name or bio, lengths, closed language/interest codes), `Visibility` (the ONE rule: `canSeeProfile(viewer, owner)` = not blocked either way, and the owner is enabled and not erased; later extended in 13B to content audiences) |
| `service` | `ProfileService`, `ConnectionService` (requests, accept, decline, cancel, remove, invites), `BlockService` (block removes the connection and pending requests in both directions, in one transaction), `MuteService`, `SocialLimits` (`AttemptLimiter` instances, §5) |
| `controller` | `ProfileController`, `ConnectionController`, `BlockController` (blocks + mutes) |
| `dto` | Java records; **no count fields anywhere** (checked by a test) |

Other changes:
- `auth/dto/RegisterRequest`: required `dateOfBirth` (`yyyy-MM-dd`); `AuthService.register` checks `AgePolicy`, sets
  `age_confirmed_at`, never stores or logs the date. Under 18 → 400 with our message.
- `user`: `UserResponse` (`/me`, login, register) adds `ageConfirmed`; new `POST /api/users/me/age-confirmation`
  for accounts created before V20.
- `User`: `ageConfirmedAt` field + `confirmAge(now)`.
- `config/SocialProperties` (limits, invite lifetime, handle cooldown) with defaults in `application.properties`.

## 5. API

All authenticated (JWT); identity only from the token; another person's private records → **404**; ids in URLs are
random `public_id`s; errors use the existing JSON shape; only our own 409 messages reach users.

| Method and path | Body / result | Errors |
|---|---|---|
| `POST /api/auth/register` | + `dateOfBirth` | 400 under 18 / invalid date |
| `POST /api/users/me/age-confirmation` | `{dateOfBirth}` → 204 | 400 under 18 |
| `GET /api/profiles/me` | own profile | 404 no profile yet |
| `PUT /api/profiles/me` | `{handle, displayName, bio?, languages[], interests[], city?}` → profile (create or update) | 400 rules; 409 handle taken / reserved / changed < 30 days ago; 409 "confirm your age first" |
| `GET /api/profiles/by-handle/{handle}` | `{publicId, handle, displayName, bio, languages, interests, city, relationship}`; `relationship` = NONE / REQUESTED / REQUEST_RECEIVED / CONNECTED | 404 missing, blocked (either way), disabled, erased; 429 |
| `GET /api/profiles/{publicId}` | same | same |
| `POST /api/connections/requests` | `{profileId}` → 202 `{status: REQUESTED}` (if the other person already asked you: `CONNECTED`) | 400 self; 404 not visible; 409 already connected / too many pending; 429 |
| `GET /api/connections/requests?direction=incoming\|outgoing` | list (a DECLINED request shows as REQUESTED to its sender) | — |
| `POST /api/connections/requests/{id}/accept` · `/decline` | 200 / 204 | 404 |
| `DELETE /api/connections/requests/{id}` | cancel own request → 204 | 404 |
| `GET /api/connections` | own connections only: `{profileId, handle, displayName}` | — |
| `DELETE /api/connections/{profileId}` | remove, silent → 204 | 404 |
| `POST /api/connections/invites` | → 201 `{id, code, expiresAt}` (code shown **once**) | 409 five active; 429 |
| `GET /api/connections/invites` · `DELETE /api/connections/invites/{id}` | active invites without codes / revoke | 404 |
| `POST /api/connections/invites/redeem` | `{code}` → 200 connection | 404 for invalid, expired, used, own or blocked (same answer); 429 |
| `POST /api/blocks` · `GET /api/blocks` · `DELETE /api/blocks/{profileId}` | block / own list / unblock | 400 self; 404 |
| `POST /api/mutes` · `GET /api/mutes` · `DELETE /api/mutes/{profileId}` | mute / own list / unmute | 400 self; 404 |

Limits (in memory, per user, `AttemptLimiter`): profile look-ups 120/h; connection requests 30/day and ≤ 50 pending
outgoing; invites 10/day, ≤ 5 active; redeem attempts 10/h; connections ≤ 1000. No public search by prefix or name
(exact handle only), so the member list cannot be scraped.

## 6. Android (`com.safesphere.app.feature.social`)

| Area | Files (planned) |
|---|---|
| `data/remote` | `SocialApi` (created from the one Retrofit instance in `AppContainer`), `SocialDtos` (invite codes redacted in `toString()`) |
| `data` | `RemoteSocialRepository` (`ApiCallHandler` → `ApiResult`) |
| `domain` | `SocialModels`, `SocialRepository`, pure `HandlePolicy` and `AgePolicy` (mirror the server, shared test cases) |
| `ui` | `ProfileSetupFragment` (create/edit: handle, display name, bio, language and interest chips, city; asks for the date of birth once if the account predates V20), `PersonProfileFragment` (Connect / Requested / Accept, Block, Mute), `ConnectionsFragment` (connections, incoming and outgoing requests, invites: create + share sheet, redeem), `BlockedMutedFragment`; ViewModels with `factory(container)` |
| Changed | `RegisterFragment` + `RegisterViewModel` (date of birth with `MaterialDatePicker`, ≥ 18 check, message), `AuthDtos`/`RemoteAuthRepository`/`AuthRepository` (`dateOfBirth`), `ProfileFragment` (new row, P7), `nav_graph.xml` (four secondary destinations), `AppContainer`, `strings.xml` |

Nothing is stored on the phone (no DataStore keys); everything loads when a screen opens. Sign-out and account
switch need no new clean-up. No new permission, no new dependency (Coil arrives in 13B).

## 7. Tests

**Backend** (`mvn test`; existing tests updated where registration now needs `dateOfBirth`: `TestAccounts`,
`CommunityClient` and five integration tests post to `/api/auth/register`):

| Test | Covers |
|---|---|
| `AgePolicyTest`, `HandlePolicyTest`, `ProfileTextPolicyTest`, `VisibilityTest` (pure) | Birthdays and 29 Feb, today = 18th birthday, future, > 120 y; handle format, reserved words, case; NFC, zero-width, URLs, lengths; the visibility matrix |
| `AgeGateApiIntegrationTest` | Under 18 refused; 18 accepted; the date is not stored (no column) or echoed; pre-V20 accounts confirm once; profile refused before confirmation |
| `ProfileApiIntegrationTest` | Create, update, uniqueness (case), reserved and previous handles, 30-day cooldown, exact look-up only, 429, disabled/erased hidden, **no count fields in any response** |
| `ConnectionApiIntegrationTest` | Request, accept, silent decline, cancel, remove, crossing requests become CONNECTED, self, limits, lists only for their owner |
| `InviteApiIntegrationTest` | Create (≤ 5), shown once, stored hashed, single use, expiry, own code, blocked pair → same 404 |
| `BlockMuteApiIntegrationTest` | Block removes connection and requests both ways; profile 404 both ways; unblock; lists private; mute has no effect on the other person |
| `SocialFourUserTest` | Owner / connection / stranger / blocked on every 13A endpoint |
| `SocialIsolationTest` | `social` imports no safety package (emergency, journey, escalation, safetycontact, circlealert, evidence, emergencycontext) and none of them imports `social` |
| `AccountEraserCoverageTest` + account-deletion test | New references handled; deleted and anonymised accounts leave no social rows |

**Android** (`testDebugUnitTest`): `HandlePolicyTest`, `AgePolicyTest`, `RegisterViewModelTest` (date of birth),
ViewModel tests for the four screens with a `FakeSocialRepository`, DTO redaction, `SocialTextsTest` (no counts,
no "follower"/"like" words, honest wording), `ResourceEncodingTest` still passing.

**Verification after tests:** lint (0 errors); V20 **dry run on MySQL** with negative inserts for every CHECK and
unique rule, then applied; a live API script against MySQL with three accounts (try/finally clean-up); emulator
(API 37) walk-through: register with date of birth, create profile, connect by handle and by invite, decline, block,
mute, delete an account. Real phone and TalkBack: not part of 13A (A23 sprint before any pilot, D10).

## 8. Risks

| Risk | Mitigation |
|---|---|
| Registration change breaks many tests and the current app build | One shared helper update; Android and backend change in the same sub-phase |
| Self-declared age is easy to lie about | Stated honestly in the app and docs; no ID checks in a student project |
| Bios and display names are unmoderated until 13D/13E | Length limits, no URLs, closed lists for languages/interests/city text rules; no pilot before 13D (D12) |
| Handle look-up confirms an account exists | Exact match only, rate-limited; blocked pairs get 404 (a blocked person can infer the block — the same limitation every platform has) |
| Blocked person makes a new account | Probation and moderation come in 13D; documented |
| Concurrent requests or crossing requests | One row per pair via the generated-column unique index; crossing requests resolve to CONNECTED |
| MySQL-only DDL not exercised by H2 | Mandatory dry run with negative cases |
| Scope creep into 13B | Visibility rule only for profiles now; content audiences extend it in 13B |

## 9. Order of work (each step buildable)

1. V20 + entities + `AccountEraser` update (coverage test green) → MySQL dry run.
2. Age gate: registration + confirmation endpoint + tests; update test helpers.
3. Profiles + handle rules + visibility + tests.
4. Connections + invites + tests.
5. Blocks + mutes + four-user and isolation tests.
6. Android: register date of birth, then data layer, then the four screens, then tests.
7. Lint, MySQL apply, live API script, emulator walk-through, security/privacy review, `docs/PHASE13A.md` with
   results, CLAUDE.md and `docs/API.md` updates.
