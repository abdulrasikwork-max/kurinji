# Phase 3: Safety Circle and the manual SOS foundation

Phase 3 builds directly on the verified Phase 2 login. It adds two real features:

1. **Safety Circle**: the trusted people a user chooses (add, view, edit, delete, enable/disable, priority, one primary).
2. **Manual SOS**: a 5-second countdown, an emergency session that can be ended, and an emergency history.

**What SOS does and does not do in Phase 3.** It *records* a session and shows the Safety Circle on screen. It does **not**
send SMS or push notifications, share location, record audio or video, call anyone, or use any new Android permission. The
app says so on every emergency screen and never claims that contacts were notified.

```
Android UI -> ViewModel -> Repository -> Retrofit -> Spring Controller -> Service -> Repository -> MySQL
```
The same Retrofit client, JWT interceptor, DataStore session and error format from Phase 2 are reused.

## 1. Backend

New packages (same style as `auth` / `user`):

```
safetycontact/  controller, service (SafetyContactService, PhoneNumbers), repository, entity (SafetyContact, Relationship), dto
emergency/      controller, service (EmergencyService), repository, entity (EmergencyEvent, EmergencyStatus, TriggerType), dto
security/CurrentUser     reads the user id from the verified JWT
common/exception         + ConflictException (409), ResourceNotFoundException (404)
```
No existing Phase 2 class was changed. `SecurityConfig` already protects every URL except login and register, so the new
endpoints are protected without any change.

### Database (Flyway V2 and V3; V1 untouched)

`safety_contacts`

| Column | Notes |
|---|---|
| id, user_id | `user_id` is a foreign key to `users(id)` with `ON DELETE CASCADE`, so there are no orphans |
| name, phone_number, email, relationship, priority | `priority` has a CHECK 1 to 5; `relationship` is text (enum) |
| is_primary, is_enabled | booleans |
| created_at, updated_at | UTC |
| primary_marker | generated column: 1 if primary, else NULL |

Constraints: `UNIQUE (user_id, phone_number)` stops the same person being added twice (different users may share a number);
`UNIQUE (user_id, primary_marker)` makes the **database itself** allow only one primary per user (MySQL ignores NULLs in a unique index).

`emergency_events`: `id`, `user_id` (FK, cascade), `status` (ACTIVE/CANCELLED/RESOLVED), `trigger_type` (MANUAL now; SHAKE/VOICE/DURESS_PIN
reserved), `started_at`, `ended_at` (NULL while active), timestamps (UTC), generated `active_marker` with
`UNIQUE (user_id, active_marker)` so a user can have only **one ACTIVE emergency**, even if two requests arrive together.

### Business rules (SafetyContactService / EmergencyService)

- Phone numbers are cleaned to one form (`+91 98765-43210` becomes `+919876543210`) so formatting cannot create duplicates.
  Limitation: no country awareness, so `9876543210` and `+919876543210` are treated as different numbers.
- Maximum 10 contacts per user (`409` when full).
- Making a contact primary first clears the old primary (service) and the database guarantees there can never be two.
- A disabled contact cannot be primary; the server silently turns `primary` off.
- Emergencies: only MANUAL can be created; only ACTIVE ones can be cancelled or resolved; a second ACTIVE one is refused (`409`).

## 2. Ownership and security model

- The owner is read **only** from the JWT `sub` claim (`CurrentUser.id(jwt)`). No request contains a `userId`; if a client
  sends one in a body it is ignored (a test proves this).
- Every repository method that returns a record includes the user id (`findByIdAndUserId`, `findByUserId...`). There is no
  method that loads a contact or emergency by id alone, so ownership cannot be forgotten.
- Someone else's record answers **404**, identical to a missing one, so ids cannot be probed.
- DTOs hide the owner id; entities are never serialised; errors use the Phase 2 JSON format with no stack traces.
- Android: the Phase 2 session rules apply. A `401` from any Phase 3 call clears the saved session (`ApiCallHandler`), and
  the screen shows the "Session expired" dialog and returns to Login. Passwords and tokens are never logged or displayed.

## 3. Emergency state model (Android)

```
 Loading -> Idle --tap Start SOS--> Countdown(5..1) --reaches 0--> Starting --recorded--> Active
                                       |                                                   |
                                    CANCEL                       "I'm safe" -> Ended(RESOLVED)
                                       v                         "False alarm" -> Ended(CANCELLED)
                                     Idle
 Starting --fails--> StartFailed --Try again--> Starting     (or "Not now" -> Idle)
```
The countdown lives only on the phone. The server record is created when it reaches zero, so cancelling a countdown writes
nothing. If the app is closed during an ACTIVE emergency, reopening the SOS screen finds it on the server and shows it again.
While counting down or active, the screen is kept awake (no permission needed).

## 4. Android screens and navigation

| Screen | Reached from |
|---|---|
| Safety Circle (list, empty state, switch, menu with Edit/Remove, "Add Trusted Contact") | Home quick action, Safety Center, Profile |
| Add / Edit trusted contact (form with validation; Remove button when editing) | Safety Circle |
| Emergency SOS (idle, countdown, starting, active, ended, failed) | Home SOS tile, Safety Center |
| Emergency History | Safety Center, SOS screen |

All are secondary destinations in the existing single Navigation graph (toolbar with back arrow, no bottom bar). The Home SOS
tile badge now reads "No alerts yet" because SOS opens a real session that still alerts nobody.

New architecture pieces: `core/network/ApiCallHandler` + `ApiResult` (shared error handling), `feature/safetycircle` and
`feature/emergency` (domain, data, ui). Only one Retrofit instance exists; `AppContainer` creates three APIs from it.
No new libraries were added.

## 5. Tests

| Where | New tests | Covers |
|---|---|---|
| Backend `SafetyContactApiIntegrationTest` | 20 | create, list order, get, update, delete, 401 on every endpoint, user B cannot read/change/delete A's contact, body `userId` ignored, invalid phone/email/priority/missing fields, duplicate (also differently formatted), another user may reuse a number, 10-contact limit, primary moves / one per user / disabled never primary |
| Backend `EmergencyApiIntegrationTest` | 10 | start, default trigger, unsupported trigger, second start 409, cancel/resolve and no double end, new emergency after ending, history order, get, user B cannot see or end A's emergency, 401 |
| Backend `PhoneNumbersTest` | 3 | normalisation and rejection |
| Android `EmergencyViewModelTest` | 13 | countdown 5-4-3-2-1, nothing recorded during countdown, cancel returns to Idle, double tap, failure and retry, conflict recovery, resolve, cancel, end failure, restoring an active emergency, expired session |
| Android `ContactFormViewModelTest` / `SafetyCircleViewModelTest` | 9 / 7 | validation, create/update/delete, primary rule, duplicate message, double save, loading, failures, toggle, delete |
| Android `ContactFormValidatorTest`, `ApiErrorMappingTest`, `ApiCallHandlerTest`, `EmergencyTimeFormatterTest` | 8 / 2 / 7 / 1 | form rules, HTTP code mapping, 401 clears session, 409 message, no leaking server text |

### What was actually executed (and what was not)

| Check | Status |
|---|---|
| Flyway V1+V2+V3 on **real MySQL 8.0.46**, with constraint tests (duplicate phone per user, second primary rejected, same phone for another user allowed, second ACTIVE rejected, priority CHECK, foreign key, cascade delete) | **Executed: all behaved as designed** |
| 19 pure-Kotlin Android tests (validators, error mapping, greeting, time formatter) | **Executed: 19 of 19 passed** (with throwaway stubs for Android's `R`) |
| Backend phone normalisation and validation regexes (16 checks) | **Executed: all passed** |
| All backend Java (43 main + 4 test files) | Statically checked with `javac`: no syntax errors, no unresolved symbol belongs to our own classes |
| All Android Kotlin (84 main + 24 test files) | Statically checked with `kotlinc`: no syntax errors; resource, id and import cross-checks pass; contrast unchanged |
| `mvn test` (the 33 new backend tests), starting Spring Boot, Flyway through Spring | **Not executed** (no Maven repositories here) |
| Gradle build, the other 36 new Android tests, emulator, the full Android to Spring to MySQL flow | **Not executed** |

## 6. Manual test procedure

Setup: backend running, emulator open, two spare emails (user A and user B).

1. Log in as A. Open **Safety Circle** from Home (the "Safety Circle" tile).
2. Empty state shows "Your trusted people" and the text does not claim anyone is notified.
3. **Add Trusted Contact**: leave Name empty and tap Save (error). Enter a bad phone (error). Then add "Mum", phone `+91 98765 43210`, Family, priority 1, **Primary on**.
4. Contact appears with Primary and Priority 1 badges. In MySQL:
   `SELECT id,user_id,name,phone_number,relationship,priority,is_primary,is_enabled FROM safety_contacts;`
   The phone is stored as `+919876543210` and `user_id` is A's id.
5. Add a second contact "Dad" (different number, priority 2, Primary on). Mum loses Primary; the table shows exactly one `is_primary = 1`.
6. Add Mum's number again for a new contact: "This phone number is already in your Safety Circle".
7. Edit Dad (menu, Edit): change name and priority; Save. MySQL shows the update.
8. Toggle Dad's switch off: the card dims with a Disabled badge; `is_enabled = 0` and `is_primary = 0`.
9. Remove Dad: confirmation dialog appears ("Remove Dad from your Safety Circle?"); confirm; the row is gone in MySQL.
10. Close and reopen the app: the contacts are still there.
11. Home, tap the red **SOS** tile (badge "No alerts yet"). Read the screen: it states nobody is contacted.
12. **Start SOS**: countdown 5, 4, 3 ... then tap **CANCEL**: back to the idle screen. In MySQL `emergency_events` has no new row.
13. Start SOS again and let it reach zero: "Emergency mode activated", the start time, a note that nobody has been notified, and Mum listed as your Safety Circle. MySQL: one row, `status = ACTIVE`, `trigger_type = MANUAL`, `ended_at` NULL.
14. Leave the screen and reopen SOS: the active emergency is shown again.
15. Tap **False alarm**: Ended screen; MySQL `status = CANCELLED`, `ended_at` set. Start another and tap **I'm safe**: `RESOLVED`.
16. Open **Emergency History** (Safety Center): both sessions, newest first, with start/end times and status badges.
17. **Multi-user check:** log out (Profile, Log out). Register or log in as **B**.
18. B's Safety Circle is empty and B's Emergency History is empty. B starts and cancels an emergency of their own (this must not affect A).
19. Log out, log in as A: A's contacts and history are unchanged.
20. Log out, close the app, reopen: the Login screen appears.

API-level ownership test (does not rely on the app). With tokens `$tokenA` and `$tokenB` and A's contact id `$id`:

```powershell
$base = "http://localhost:8080"
function Try-Call($method, $path, $token, $json) {
  try { Invoke-RestMethod -Method $method -Uri "$base$path" -Headers @{ Authorization = "Bearer $token" } -ContentType "application/json" -Body $json }
  catch { "$($_.Exception.Response.StatusCode.value__): $($_.ErrorDetails.Message)" }
}
Try-Call Get    "/api/safety-contacts/$id" $tokenB      # 404
Try-Call Put    "/api/safety-contacts/$id" $tokenB '{"name":"x","phoneNumber":"9111111111","relationship":"OTHER","priority":5}'   # 404
Try-Call Delete "/api/safety-contacts/$id" $tokenB      # 404
Try-Call Get    "/api/safety-contacts"     $tokenB      # [] (empty)
Try-Call Get    "/api/emergencies/$emergencyIdOfA" $tokenB   # 404
Try-Call Put    "/api/emergencies/$emergencyIdOfA/cancel" $tokenB   # 404
```
Also test the same endpoints with no `Authorization` header: every one answers `401`.

## 7. Known limitations

- Contacts are not notified in any way; SOS only records a session (by design).
- Phone numbers have no country-code awareness (see above); no phone-number ownership verification.
- The 5-second countdown is not a lock-screen or hardware trigger: the app must be open and the screen on.
- If recording fails (offline) the SOS cannot be recorded; the screen says so and tells the user to call 112. There is no offline queue.
- No pagination for contacts (capped at 10) or history (latest 50).
- The Home status card still shows the labelled sample state; it does not react to an active emergency yet.
- Everything from Phase 2 limitations still applies (24-hour JWT with no refresh or server-side logout, plain HTTP in development, unencrypted DataStore token).
- Spring Boot 3.5.16 (final free release of that line), see docs/PHASE2.md.

## 8. Deliberately deferred (not implemented, no placeholders pretending to work)

Shake and voice triggers, stress detection, duress PIN (the enum value exists only as a reserved name, and the server rejects it),
location capture and live location sharing, SMS and push notifications, calling, audio and video evidence, Safe Walk,
route features, community reports and the safety map, risk scoring, AI/ML, admin and moderation.

## 9. Phase 3 to Phase 4 boundary

Phase 3 ends with a *recorded, ownership-protected* emergency and a Safety Circle. Phase 4 is where an active emergency
starts to *do* things, and each step needs a deliberate permission and design: capturing a location (location permission),
telling the Safety Circle (SMS fallback or push), then live location sharing; shake / voice triggers come after that.
