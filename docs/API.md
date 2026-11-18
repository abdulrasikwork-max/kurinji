# SafeSphere API (Phase 2)

Base URL (local development): `http://localhost:8080`
(From the Android **emulator** the same server is `http://10.0.2.2:8080`.)

All requests and responses are JSON (`Content-Type: application/json`).
The examples use made-up values only. Never put real credentials in documentation.

> ## Removed in N1 (2026-11-15, `PHASE_N1.md`): read this first
> The sections of this file are kept per phase, as history. **The endpoints and fields below no longer exist in the
> code** (branch `irl-next`; they answer 404). The pilot server still runs the old jar and still has them.
>
> | Gone | Was described under |
> |---|---|
> | `POST /api/emergencies/{id}/evidence`, every `/api/evidence/**` route (vault key, upload, seal, download, verify, delete, custody, recovery) | Phase 5 |
> | every `/api/community/**` route (reports, area, contributions, mine, flags, standing, appeals) | Phases 6, 7 |
> | `/api/moderation/cases/**`, `/api/moderation/appeals/**` (Nearby signal cases); `GET /api/admin/evaluation` | Phases 7, 9 |
> | every `/api/intelligence/**` route; the `intelligence` part of the area answer | Phase 8 |
> | `PUT`/`DELETE /api/posts/{id}/reaction`, `PUT`/`DELETE /api/posts/{id}/save`, `GET /api/saved` | Phases 13C, 17E |
> | `PUT`/`DELETE /api/profiles/me/daily-state` | Phase 17E |
> | `POST /api/explore/search`, `POST /api/explore/understand`, `GET /api/explore/understanding` | Phases 17G-1, 17G-3 |
> | `PUT`/`DELETE /api/places/{id}/went`, `PUT`/`DELETE /api/places/{id}/rating` | Phases 17G-2, 18A |
>
> **Fields gone from answers that stay:** the owner's Emergency Context Packet has no `evidence`; a post has no
> `youReacted`, `saved`, `butterflies`, `facets`, `disclosed` (and create / edit ignore `facets` and `disclosed`); the
> place page (`GET /api/places/{id}/posts`) has no `rating` and no `yourVisit`; both profile answers have no
> `dailyState`; an audit-log entry (`GET /api/admin/moderation-log`) has `targetKind` (POST, COMMENT, PERSON, PLACE or
> null) in place of `category`.
>
> **Still there:** `GET /api/moderation/access`, `PUT /api/admin/roles`, `GET /api/admin/moderation-log`, every
> `/api/moderation/content-*` route, reports, the automatic text check, and everything of auth, account, Safety Circle,
> SOS, Safe Walk, circle alerts, profiles, connections, blocks, places, posts, media, perspectives and Discover.
> The Python service: `POST /v1/assess` and `GET /v1/health` (without `understanding`); `/v1/understand` is gone.

> ## Changed in N2 (2026-11-16, `PHASE_N2.md`): phone number + texted code are the only way in
> **Gone (404):** `POST /api/auth/register`, `POST /api/auth/login` (Phase 2, 17B), `PUT /api/users/me/password`
> (Phase 10A). **Changed:** `/api/auth/phone/start` and `/phone/verify` (one flow for sign-in and sign-up), deletion,
> its cancel and ending an SOS from another sign-in take `{challenge, code}` instead of `{password}`, `PUT /api/admin/roles`
> takes `{phone, role}`, the `user` object has `phoneHint` instead of `email`, a profile's `displayName` is the real
> name (`PUT /api/profiles/me` ignores a sent name). **New:** `POST /api/auth/sign-up`, `POST /api/users/me/confirmation`,
> `POST /api/users/me/sessions/sign-out-others`, `PUT /api/users/me/name`. Details: "Phase N2" before the appendix.
> The Phase 2, 10A and 17B sections below are history where they mention an e-mail or a password.

> ## Changed in N3 (2026-11-16, `PHASE_N3.md`): the verified MIET community
> **Changed:** every community read and write now needs an active MIET membership (403 `MEMBERSHIP_NEEDED`); the own
> profile draft, the own moderation standing and everything of safety and the account do not. `PUT /api/users/me/name`
> answers 409 `NAME_LOCKED` after the ID check. **New:** `/api/community/me|programmes|verification|verification/code|
> verification/photo|relink`, `/api/verifier/lookup|requests/{ref}/decision`, `/api/admin/community/photo-requests|
> requests/{ref}/photo|requests/{ref}/decision|verifiers|members/name|bans|bans/{id}/lift|members/end|relinks|log`.
> Details: "Phase N3" before the appendix.

## Error format (every endpoint)

```json
{
  "timestamp": "2026-10-15T10:30:00.123Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Validation failed",
  "path": "/api/auth/register",
  "fieldErrors": { "email": "Enter a valid email address" }
}
```
`fieldErrors` only appears for validation failures. Stack traces and internal details are never returned.

| Status | When |
|---|---|
| 400 | Invalid or malformed request body |
| 401 | Wrong credentials, or missing / invalid / expired token |
| 403 | Authenticated but not allowed (not used by any endpoint yet) |
| 404 | Unknown URL |
| 405 | Wrong HTTP method |
| 409 | Email already registered |
| 500 | Unexpected server error (details are only in the server log) |

---

## POST /api/auth/register

Creates an account and signs the user in (returns a token straight away).

- **Authentication:** none
- **Request body**

```json
{ "name": "Asha Devi", "email": "asha@example.com", "password": "correct-horse-battery" }
```

| Field | Rules |
|---|---|
| name | required, max 100 characters |
| email | required, valid format, max 255 characters. Stored in lower case; must be unique |
| password | required, 8 to 72 characters (BCrypt limit; also at most 72 bytes) |

- **Success: 201 Created**

```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiJ9.<payload>.<signature>",
  "tokenType": "Bearer",
  "expiresInSeconds": 86400,
  "user": { "id": 1, "name": "Asha Devi", "email": "asha@example.com", "createdAt": "2026-10-15T10:30:00Z" }
}
```

- **Errors:** `400` validation failed, `409` email already registered:

```json
{ "timestamp": "2026-10-15T10:30:00Z", "status": 409, "error": "Conflict",
  "message": "An account with this email already exists", "path": "/api/auth/register" }
```

---

## POST /api/auth/login

- **Authentication:** none
- **Request body**

```json
{ "email": "asha@example.com", "password": "correct-horse-battery" }
```

- **Success: 200 OK:** same body as register.
- **Errors**
  - `400` a field is missing.
  - `401` wrong email **or** wrong password (the same message for both, so attackers cannot discover which emails exist):

```json
{ "timestamp": "2026-10-15T10:31:00Z", "status": 401, "error": "Unauthorized",
  "message": "Invalid email or password", "path": "/api/auth/login" }
```

---

## GET /api/users/me

Returns the profile of the signed-in user.

- **Authentication:** required. Header `Authorization: Bearer <accessToken>`
- **Request body:** none
- **Success: 200 OK**

```json
{ "id": 1, "name": "Asha Devi", "email": "asha@example.com", "createdAt": "2026-10-15T10:30:00Z",
  "deletionRequestedAt": null, "deletionDueAt": null }
```
Phase 10A: `deletionRequestedAt` / `deletionDueAt` are set while an account deletion is pending (both or neither); the
same `user` object is in the register, login and password-change answers. See "Phase 10A endpoints" at the end.

- **Errors:** `401` token missing, malformed, expired, wrongly signed, or the account no longer exists:

```json
{ "timestamp": "2026-10-15T10:32:00Z", "status": 401, "error": "Unauthorized",
  "message": "Authentication required or token invalid", "path": "/api/users/me" }
```

Never returned by any endpoint: `password`, `passwordHash`, the JWT secret, internal security details.

---

## The token (JWT)

Signed with HS256 using the server's `JWT_SECRET`. Claims: `iss` (issuer), `sub` (user id), `iat` (issued at), `exp` (expires at; 24 hours by default).
A JWT is **signed, not encrypted**: anyone holding it can read these claims, which is why no password or email is inside.

---

# Phase 3 endpoints

All endpoints below **require a JWT** (`Authorization: Bearer <accessToken>`). The caller's identity always comes from
the token. There is no `userId` anywhere in any request, and a `userId` sent in a body is ignored.
Records that belong to another user answer **404 Not Found**, exactly like records that do not exist.

## Safety Circle: `/api/safety-contacts`

Contact object (response):

```json
{
  "id": 3, "name": "Asha Devi", "phoneNumber": "+919876543210", "email": "asha@example.com",
  "relationship": "FAMILY", "priority": 1, "primary": true, "enabled": true,
  "createdAt": "2026-10-16T09:00:00Z", "updatedAt": "2026-10-16T09:00:00Z"
}
```

| Field (request) | Rules |
|---|---|
| name | required, max 100 characters |
| phoneNumber | required; digits, spaces, `( ) . -` and an optional leading `+`; after removing separators 7 to 15 digits. Stored in the cleaned form |
| email | optional (empty = none); valid format when given |
| relationship | required: `FAMILY`, `PARTNER`, `FRIEND`, `COLLEAGUE`, `NEIGHBOR`, `OTHER` |
| priority | required, 1 (contact first) to 5 |
| primary | optional, default `false`. At most one primary per user: setting it moves the flag from the old one |
| enabled | optional, default `true`. A disabled contact is never primary (the server turns `primary` off) |

| Method and URL | Purpose | Success | Errors |
|---|---|---|---|
| `GET /api/safety-contacts` | Your contacts: primary first, then priority, then name | `200` list | `401` |
| `GET /api/safety-contacts/{id}` | One of your contacts | `200` | `401`, `404` |
| `POST /api/safety-contacts` | Add a contact | `201` contact | `400` validation, `401`, `409` duplicate number or circle full (max 10) |
| `PUT /api/safety-contacts/{id}` | Replace a contact's fields | `200` contact | `400`, `401`, `404`, `409` |
| `DELETE /api/safety-contacts/{id}` | Remove a contact | `204` no body | `401`, `404` |

Example `POST` body:

```json
{ "name": "Asha Devi", "phoneNumber": "+91 98765-43210", "email": "", "relationship": "FAMILY",
  "priority": 1, "primary": true, "enabled": true }
```

Example validation error (`400`):

```json
{ "timestamp": "2026-10-16T09:00:00Z", "status": 400, "error": "Bad Request", "message": "Validation failed",
  "path": "/api/safety-contacts", "fieldErrors": { "phoneNumber": "Enter a valid phone number" } }
```

Example duplicate (`409`): `"message": "This phone number is already in your Safety Circle"`.

## Emergencies: `/api/emergencies`

Emergency sessions are only **recorded**. Nothing is sent to anyone (Phase 4A adds location points, below).

```json
{ "id": 12, "status": "ACTIVE", "triggerType": "MANUAL",
  "startedAt": "2026-10-16T09:10:00Z", "endedAt": null }
```

| Method and URL | Purpose | Success | Errors |
|---|---|---|---|
| `POST /api/emergencies` | Record a new ACTIVE emergency. Optional body `{"triggerType":"MANUAL"}` (default MANUAL; **`SHAKE` accepted since Phase 17F** - the phone's opted-in shake gesture, after its own 5 s countdown; `VOICE` and `DURESS_PIN` are reserved and rejected) | `201` | `400` unsupported trigger, `401`, `409` one is already active |
| `GET /api/emergencies` | Your history, newest first, at most 50 | `200` list | `401` |
| `GET /api/emergencies/{id}` | One of your emergencies | `200` | `401`, `404` |
| `PUT /api/emergencies/{id}/cancel` | "False alarm": ACTIVE to CANCELLED | `200` | `401`, `404`, `409` already ended |
| `PUT /api/emergencies/{id}/resolve` | "I'm safe": ACTIVE to RESOLVED | `200` | `401`, `404`, `409` already ended |

State machine: `ACTIVE` can become `CANCELLED` or `RESOLVED`; both are final. The app's 5-second countdown happens on the
phone only: the emergency is recorded when it reaches zero.

# Phase 4A endpoints

## Emergency locations: `/api/emergencies/{id}/locations`

Location points recorded by the phone during an emergency. They are stored for the **owner only**; nothing is sent to
anyone. JWT required; another user's emergency (or an unknown id) answers `404 Emergency not found`.

Upload body (1 to 50 points, so an offline phone can send what it collected in one request):

```json
{ "points": [
  { "latitude": 12.971599, "longitude": 77.594563, "accuracyMeters": 8.5,
    "source": "FUSED", "batteryPercent": 64, "recordedAt": "2026-10-19T12:19:47.682Z" }
] }
```

| Field | Rules |
|---|---|
| `latitude` / `longitude` | required; -90..90 / -180..180; stored with 6 decimals |
| `accuracyMeters` | optional; 0..100000 |
| `source` | optional; `FUSED`, `LAST_KNOWN`, `GPS`, `NETWORK`, `UNKNOWN` (default `UNKNOWN`) |
| `batteryPercent` | optional; 0..100 |
| `recordedAt` | required; ISO-8601 instant, when the phone took the fix |

| Method and URL | Purpose | Success | Errors |
|---|---|---|---|
| `POST /api/emergencies/{id}/locations` | Store a batch; **all-or-nothing** | `201` `{"received":2,"stored":2}` | `400` validation / time window, `401`, `404`, `409` not accepting / limit reached |
| `GET /api/emergencies/{id}/locations/latest` | Newest point (by `recordedAt`) | `200` point | `401`, `404` emergency not found **or** `No location recorded for this emergency` |
| `GET /api/emergencies/{id}/locations` | Newest 100 points, newest first | `200` list | `401`, `404` |

Rules, all checked against the **server's** clock:

- Accepted while the emergency is `ACTIVE`, and for **15 minutes after it ends** (so points collected offline can still
  be uploaded). After that: `409 This emergency is no longer accepting locations`.
- `recordedAt` must lie inside the emergency, with a 5-minute tolerance for phone clock drift: not more than 5 minutes in
  the future, not more than 5 minutes before `startedAt`, not more than 5 minutes after `endedAt`. Otherwise `400`.
- **Re-sending is safe**: a point with the same `recordedAt` as one already stored (or twice in one batch) is skipped,
  so `stored` can be smaller than `received`. Enforced by `UNIQUE (emergency_id, recorded_at)`.
- At most `app.emergency.locations.max-per-emergency` points per emergency (default 1000, env `EMERGENCY_LOCATION_LIMIT`):
  `409 Location limit reached for this emergency`.
- Concurrent uploads for one emergency are serialised (`SELECT ... FOR UPDATE` on the emergency row).

Point returned by the GET endpoints:

```json
{ "latitude": 12.971599, "longitude": 77.594563, "accuracyMeters": 8.5, "source": "FUSED",
  "batteryPercent": 64, "recordedAt": "2026-10-19T12:19:47.682Z", "receivedAt": "2026-10-19T12:19:47.802553Z" }
```

# Phase 4B endpoints

> **N6-B1 (2026-11-18):** the walk has no location, destination, escalation or alert endpoints any more; the current
> walk API is under "Phase N6" near the end of this file. The text below is history where it differs.

## Safe Walk: `/api/journeys`

A journey is a period during which the person checks in regularly. **The server computes every deadline with its own
clock**; the phone runs the timer and the escalation policy and reports what it reached. Owner only (another user's id
answers `404 Safe Walk not found`), JWT required. Nothing here contacts anyone.

```json
{ "id": 1, "status": "ACTIVE", "destinationLabel": "Home", "checkInIntervalMinutes": 10, "escalationLevel": 0,
  "startedAt": "2026-10-19T13:27:10.866858Z", "endedAt": null, "nextCheckInDueAt": "2026-10-19T13:37:10.866858Z",
  "lastLocation": { "latitude": 12.9716, "longitude": 77.5946, "accuracyMeters": 6.0, "recordedAt": "..." },
  "serverTime": "2026-10-19T13:27:10.870Z" }
```

`serverTime` lets the phone count down `nextCheckInDueAt - serverTime` on its own clock, so a wrong phone clock does not matter.

| Method and URL | Purpose | Success | Errors |
|---|---|---|---|
| `POST /api/journeys` `{"destinationLabel"?, "checkInIntervalMinutes": 2..60}` | Start; first deadline = now + interval | `201` | `400`, `401`, `409` a walk is already active / an emergency is active |
| `GET /api/journeys` | Your walks, newest first, at most 20 | `200` list | `401` |
| `GET /api/journeys/{id}` | One walk | `200` | `401`, `404` |
| `POST /api/journeys/{id}/check-ins` | "I'm OK": level back to 0, new deadline = now + interval | `200` | `401`, `404`, `409` ended |
| `PUT /api/journeys/{id}/complete` | "I've arrived" (final) | `200` | `401`, `404`, `409` ended |
| `PUT /api/journeys/{id}/cancel` | Cancel (final) | `200` | `401`, `404`, `409` ended |
| `POST /api/journeys/{id}/escalations` `{"level": n}` | Record the level the phone's policy reached | `200` | see below |
| `PUT /api/journeys/{id}/location` | Replace the **last known** location (older fixes ignored) | `204` | `400`, `401`, `404`, `409` ended |

Escalation Ladder rules (server side):

- `level` ≤ current → `200`, no change (an offline phone can safely re-send).
- Skipping a level → `400 Escalation levels cannot be skipped`.
- `1` (check-in missed) only when the server's own deadline has passed (30 s allowance for network delay), else `409 The check-in is not due yet`.
- `2` (no response) after `1`.
- `3` → `400 Level 3 is an emergency: start SOS to escalate`. `4` → `400` not available in this version (4D). `5` → `400` reserved.

## Phase 4C additions (Safety Bubble, unexpected stop)

- `POST /api/journeys` also accepts optional `"destinationLatitude"` (−90..90) and `"destinationLongitude"` (−180..180),
  both or neither (`400 Destination latitude and longitude must be sent together`); stored rounded to 6 decimals.
- Responses include `"destination": {"latitude", "longitude"}` (owner only) or nothing. When a walk is **completed or
  cancelled**, its destination coordinates **and last location are deleted**; a walk that ended as `EMERGENCY` keeps them.
- `POST /api/journeys/{id}/escalations` accepts an optional `"reason"`:
  - level 1: `CHECK_IN_MISSED` (default; still only after the server's own deadline), `ROUTE_DEVIATION` (needs a walk with
    a destination, else `400 A route check needs a Safe Walk with a destination`) or `UNEXPECTED_STOP`. The last two may
    arrive before the deadline: the phone saw them.
  - level 2: only `NO_RESPONSE` (default).
  - any other pair → `400 This reason does not match the escalation level`; unknown text → `400 Unknown escalation reason`.

```json
{ "level": 1, "reason": "ROUTE_DEVIATION" }
```

**SOS during a walk:** `POST /api/emergencies` finds the caller's ACTIVE walk (by the JWT user, never by an id from the
client), ends it as `EMERGENCY`, records level 3 (`SOS_CONFIRMED`) for both, and returns `"journeyId"` in the emergency.
`POST /api/journeys` while an emergency is active → `409`.

# Phase 4D endpoints (escalation communication)

The **phone** sends SMS to the Safety Circle (only if the person opted in) or opens the SMS app; the **server sends
nothing** and only records what happened. JWT required; another user's emergency / walk answers `404`.

## Circle alert log

`POST /api/emergencies/{id}/circle-alerts` and `POST /api/journeys/{id}/circle-alerts`, 1–20 reports:

```json
{ "reports": [ { "clientRef": "3f0c…-uuid", "contactId": 3, "kind": "SOS_STARTED", "channel": "SMS_AUTO",
                 "status": "SENT", "occurredAt": "2026-10-20T13:31:05Z" } ] }
```

→ `200 {"received": 1, "stored": 1, "updated": 0}`

| Field | Values / rule |
|---|---|
| `kind` | emergency: `SOS_STARTED`, `SOS_LOCATION`, `SOS_RESOLVED`, `SOS_CANCELLED`; walk: `WALK_NO_RESPONSE`, `ALL_CLEAR` (wrong target → `400`) |
| `channel` | `SMS_AUTO` (phone sent it) · `SMS_COMPOSER` (SMS app opened, the person decides) |
| `status` | `SENT` (left the phone, **not** received) · `DELIVERED` (network delivery report) · `FAILED` · `COMPOSER_OPENED` |
| `clientRef` | UUID per attempt and contact: re-sending is harmless; a later report may only move the status forward (`SENT` → `DELIVERED`); a reference used for another emergency/walk → `409` |
| `contactId` | optional; a contact that is not the caller's is stored as unknown (never linked) |
| `occurredAt` | not in the future (5-min tolerance), not before the start |

All-or-nothing batch; at most 200 reports per emergency or walk (`409`). No phone number and no message text is ever sent or stored.

## Level 4: dial prompt

`POST /api/emergencies/{id}/dial-prompts` → `204`. The person tapped "Call 112" and the phone's dialer opened (the app
never calls). Recorded once per emergency as escalation level 4, reason `DIAL_PROMPT_OPENED`; only while ACTIVE
(`409` after it ended). `POST /api/journeys/{id}/escalations {"level": 4}` → `400 Level 4 is the emergency pathway: start SOS first`.

## Emergency Context Packet

`GET /api/emergencies/{id}/context?audience=OWNER` (default) — assembled on demand, no table:

```json
{ "audience": "OWNER", "generatedAt": "…",
  "emergency": { "id": 12, "status": "RESOLVED", "triggerType": "MANUAL", "startedAt": "…", "endedAt": "…" },
  "journey": { "id": 21, "status": "EMERGENCY", "destinationLabel": "Station", "startedAt": "…", "endedAt": "…" },
  "latestLocation": { "latitude": 12.9716, "longitude": 77.5946, "accuracyMeters": 5.0, "batteryPercent": 64,
                      "recordedAt": "…", "mapLink": "https://www.google.com/maps/search/?api=1&query=12.9716,77.5946" },
  "locationCount": 4, "highestLevel": 4,
  "escalations": [ { "level": 3, "reason": "SOS_CONFIRMED", "at": "…", "source": "EMERGENCY" } ],
  "circleAlerts": [ { "kind": "SOS_STARTED", "channel": "SMS_AUTO", "status": "DELIVERED", "contactName": "Ravi",
                      "occurredAt": "…", "source": "EMERGENCY" } ] }
```

`?audience=SAFETY_CIRCLE` → exactly `audience, personName, type, status, startedAt, endedAt, locationLink,
locationRecordedAt` (the allow-list; a preview for the owner — there is no contact-facing endpoint).
`OFFICIAL_PATHWAY`, `VERIFIED_NEARBY_ASSISTANCE` → `400 This audience is not available`; anything else → `400 Unknown audience`.
Phase 5: the OWNER projection also has `"evidence": { "items": 3, "sealed": 2, "uploading": 1, "deleted": 0 }`
(counts only); the SAFETY_CIRCLE projection has no evidence field.

# Phase 5 endpoints (Emergency Evidence Vault)

All need the JWT; another person's emergency or item → `404` (same as missing). The server only ever receives
ciphertext produced by the phone (SSEV1, see docs/PHASE5.md); it never decrypts anything.

## Vault recovery key

`GET /api/evidence/vault-key` → `{ "available": true, "keyId": "<sha-256 of the key>", "algorithm": "RSA-OAEP-SHA256",
"publicKey": "<base64 X.509>" }`, or `{ "available": false, ... null }` when no escrow key is configured.

## Declare an item: `POST /api/emergencies/{id}/evidence`

```json
{ "clientRef": "3f1c…uuid", "kind": "AUDIO", "mediaType": "audio/aac", "sequenceNo": 0, "recordingRef": "…uuid",
  "sizeBytes": 185159, "ciphertextSha256": "64 hex", "plaintextSha256": "64 hex", "encryption": "SSEV1",
  "capturedStartedAt": "2026-10-20T00:18:48.811Z", "capturedEndedAt": "2026-10-20T00:19:19.700Z",
  "escrowKeyId": "64 hex", "escrowWrappedKey": "<base64 RSA-OAEP output>" }
```
- `201` new, `200` already declared (same clientRef + hash + size).
- Kinds and types: AUDIO→`audio/aac`, PHOTO→`image/jpeg`, VIDEO→`video/mp4` (400 otherwise).
- Capture must lie inside the emergency (±5 min), not in the future, and last ≤ 3 h (400).
- Size ≤ 200 MiB (400). ≤ 500 items per emergency and a 2 GiB per-person quota (`409 Your evidence vault is full`).
- Escrow: both fields or neither. The key id must be the current one (`409 The vault recovery key has changed; fetch it
  again`), and the length must fit the key (400).
- Allowed any time after the emergency ended (an offline phone uploads later).

Response (`EvidenceResponse`, also from GET / PUT / seal / delete): `id, clientRef, emergencyId, kind, mediaType,
sequenceNo, recordingRef, status (UPLOADING|SEALED|DELETED), sizeBytes, receivedBytes, ciphertextSha256,
plaintextSha256, escrowed, capturedStartedAt, capturedEndedAt, receivedAt, sealedAt, deletedAt, uploadChunkBytes`.
Never the escrow key or the storage file name.

## Upload, seal, download

- `PUT /api/evidence/{id}/content?offset=N`, `Content-Type: application/octet-stream`, at most `uploadChunkBytes` (1 MiB).
  `N` must equal `receivedBytes`, otherwise `409 Upload must continue at byte M`. Pieces that are empty, too large or
  past the declared size get `400`. Sealed or deleted items get `409`.
- `POST /api/evidence/{id}/seal`: the server recomputes size + SHA-256 over every byte.
  - Match → `SEALED` (repeat calls are harmless).
  - Incomplete → `409 Upload is not complete: X of Y bytes received`.
  - Mismatch → `409 The uploaded bytes do not match the evidence hash; upload it again`. The bytes are discarded and
    `INTEGRITY_FAILED` is logged in custody.
- `GET /api/evidence/{id}/content` → the sealed ciphertext (`application/octet-stream`, `X-Evidence-Sha256`,
  `Cache-Control: no-store`). Logged as `DOWNLOADED`. Not sealed / deleted → 409.
- `GET /api/evidence/{id}`, `GET /api/emergencies/{id}/evidence` (capture order).

## Re-check the stored copy: `POST /api/evidence/{id}/verify` (Phase 5 hardening, V10)

The server re-hashes its STORED ciphertext now and compares it with the declared hash. Always `200` for a sealed item:
```json
{ "intact": true, "verifiedAt": "2026-10-20T06:05:40Z", "item": { …EvidenceResponse… } }
```
- `intact: true` → custody `VERIFIED`.
- `intact: false` (damaged or missing on disk) → custody `INTEGRITY_FAILED`; the bytes are discarded and `item.status`
  is `UPLOADING` from byte 0 (`sealedAt` null). A phone that still has the item uploads it again; only the original
  bytes can seal it (the declared hash never changes). Until then download answers 409.
- Not sealed yet / deleted → `409`. Another person's item → `404`. The phone calls this before "Remove from this phone".

## Delete: `POST /api/evidence/{id}/delete` `{ "password": "…" }`

Checked in this order (nothing about the password is revealed before the rules allow deletion):
1. `409 Evidence cannot be deleted while the emergency is active`
2. `409 Evidence can only be deleted 24 hours after the emergency ended` (`app.evidence.delete-protection`, default 24h)
3. `429 Too many wrong passwords. Deleting evidence is locked; try again in 15 minutes` — 5 wrong passwords
   (`delete-max-failures`) within 15 minutes (`delete-failure-window`) per person, counted from the custody ledger;
   while locked even the right password is refused and attempts are not counted.
4. `409 Password is incorrect` — recorded in custody as `DELETE_REFUSED` (committed although the request fails).
- On success: status `DELETED`. The tombstone keeps kind, sizes, hashes and times; the ciphertext file is removed
  after commit and the escrow key copy is erased. Repeat calls are harmless.
- The phone shows our own 409 and 429 messages as they are (Android `ApiError.TOO_MANY_ATTEMPTS` for 429).

## Chain of custody: `GET /api/emergencies/{id}/evidence/custody`

```json
{ "chainIntact": true, "brokenAtSequence": null, "chainLength": 10, "latestEntryHash": "64 hex",
  "verifiedAt": "…", "entries": [ { "sequenceNo": 1, "evidenceId": 6, "action": "DECLARED",
  "ciphertextSha256": "…", "occurredAt": "…", "entryHash": "…" } ] }
```
The person's whole hash chain is re-verified on every call; `entries` are this emergency's. Actions: DECLARED,
SEALED, INTEGRITY_FAILED, DOWNLOADED, DELETED, and since V10 VERIFIED and DELETE_REFUSED.
The phone keeps the newest `(sequenceNo, entryHash)` it has seen per emergency and checks it is still there on the
next call ("custody anchor"): a complete rewrite of the chain, invisible to `chainIntact`, is then reported.

## Restore on a new phone (recovery passphrase, V11; docs/PHASE5.md §11)

The passphrase never reaches the server. Every key value below is opaque base64 made by the phone; the server only
checks formats. Wrong account passwords on `PUT /recovery` and `POST /recovery/envelope` are counted together:
5 within 15 minutes (`delete-max-failures`, `delete-failure-window`) → `429 Too many wrong passwords. Recovery is
locked; try again in 15 minutes` (even with the right password). A wrong one → `409 Password is incorrect`.

- `GET /api/evidence/recovery` → `{ "configured": true, "rkVersion": 1, "envelopeVersion": 2, "updatedAt": "…",
  "lastDownloadedAt": "…" }` (no key material; all null / false when not set up).
- `PUT /api/evidence/recovery`
  ```json
  { "password": "…", "expectedEnvelopeVersion": null, "rkVersion": 1, "kdf": "PBKDF2-SHA256",
    "kdfIterations": 600000, "kdfSalt": "<16-32 bytes>", "wrappedRecoveryKey": "<60 bytes>" }
  ```
  `expectedEnvelopeVersion: null` creates (409 if one exists); otherwise it must equal the current version
  (`409 The recovery passphrase was changed elsewhere; check it again`) and `envelopeVersion` goes +1. `rkVersion` must
  be 1 on creation and unchanged afterwards (`400 The recovery key cannot be replaced`; reset is not available yet).
  400 for another kdf, iterations outside 600 000 – 10 000 000, salt length, wrap length ≠ 60. Answers the status.
- `POST /api/evidence/recovery/envelope` `{ "password": "…" }` → `{ rkVersion, envelopeVersion, kdf, kdfIterations,
  kdfSalt, wrappedRecoveryKey, updatedAt }` (`Cache-Control: no-store`), and records `lastDownloadedAt`.
  `404 No recovery passphrase is set up`.
- `GET /api/evidence/recovery/items?page=N` → `{ "items": [ { "item": {…EvidenceResponse…}, "recoveryRkVersion": 1,
  "recoveryWrappedKey": "<60 bytes>" } ], "page": 0, "hasMore": false }`: the caller's SEALED items that have a
  recovery copy, 200 per page, oldest first (`no-store`). Never DELETED or another person's items.
- `PUT /api/evidence/{id}/recovery-key` `{ "rkVersion": 1, "wrappedKey": "<60 bytes>" }` → `EvidenceResponse`.
  Attaches the copy to an item declared earlier; write-once per `rkVersion` (a repeat answers the existing state).
  `409 No recovery passphrase is set up`, `409 The recovery key has changed; fetch it again`, `409 This evidence was
  deleted`; another person's item → 404.
- Declaration (`POST /api/emergencies/{id}/evidence`) also accepts `recoveryRkVersion` + `recoveryWrappedKey` (both
  or neither, same checks as above). `EvidenceResponse` gains `recoverable` (never the copy itself). Delete everywhere
  erases the recovery copy together with the escrow copy.

# Phase 6 endpoints (Community Safety Intelligence: place signals, V12, docs/PHASE6.md)

JWT required for all of them. **No coordinate is ever sent**: the phone sends geohash cells (`cell` = 7 characters,
~150 m; `area` = 6 characters, ~1.2 × 0.6 km), lower-case base32 `0-9 b-h j k m n p-z`. No response contains a user
id, name, exact count or a timestamp of anyone else. Categories: `HARASSMENT`, `FOLLOWING`, `THEFT_OR_SNATCHING`
(INCIDENT, 3 days), `POOR_LIGHTING`, `ISOLATED_STRETCH`, `UNSAFE_CROSSING`, `BROKEN_PATH` (CONDITION, 30 days),
`WELL_LIT_BUSY`, `OPEN_LATE_STAFFED`, `HELP_POINT` (HAVEN, 60 days).

## Report: `POST /api/community/reports` `{ "category": "POOR_LIGHTING", "cell": "tdr1v9q" }`
→ `202 { "status": "RECORDED", "appearsWithinMinutes": 30 }`. The SAME answer for a new signal, a merge into the
existing signal for that cell + category, and a re-send of the caller's own report (idempotent). It starts counting
after a random 10–30 min delay (plus what is left of the 24 h new-account probation). Unknown fields (e.g.
coordinates) are ignored and never stored. `400` invalid category/cell; `429` limits: 10 reports / 24 h, 3 reports in
one area cell / 24 h, 30 contributions of any kind / 24 h (our message shown).
**Phase 9D (neighbour join):** once a report COUNTS, the clean-up job (every 15 min) moves a LONE concern report (its
signal's only contribution, never confirmed, no flag, no open case; never a safe place) onto an OLDER visible signal
of the same category in a block sharing a SIDE with its own, as a `CONFIRM` with the same counting time. Nothing in
any answer changes before that. A re-send of a report that was joined answers `202` and adds nothing.

## Look at your area: `GET /api/community/area?cell=tdr1v9`
→ `{ "area": "tdr1v9", "signals": [ { "id": "<uuid>", "category": "HELP_POINT", "group": "HAVEN",
"precision": "BLOCK", "cell": "tdr1v9y", "latitude": <centre of the shown cell>, "longitude": <centre of the shown cell>, "support": "SEVERAL",
"freshness": "TODAY", "disputed": false, "yourContribution": null } ] }`. Visible signals in the area cell and its 8
neighbours, nearest first, at most 100. `precision` AREA ⇒ `cell` is the 6-character area and the coordinates are its
centre (one supporter); BLOCK ⇒ 7 characters (≥ 2 supporters). `support` SINGLE | SEVERAL (2–4) | MANY (5+);
`freshness` TODAY | THIS_WEEK | EARLIER; `yourContribution` = the caller's own kind or null. `400` not a 6-character
cell; `429` more than 120 look-ups or more than 15 different areas per hour.

## Answer: `POST /api/community/signals/{id}/contributions` `{ "kind": "CONFIRM" | "NO_LONGER", "near": "tdr1v9q" }`
→ `202` as for a report. `near` = the caller's current 7-character cell, checked (never stored) against the signal at
the precision it is SHOWN at. `400` kind REPORT; `404` unknown, pending, retired or expired signal; `409 You have
already added to this signal` (incl. your own report), `409 You can only answer signals near where you are`; `429`.

## Your own: `GET /api/community/mine`, `DELETE /api/community/mine/{id}`
`GET` → `[ { "signalId", "category", "kind", "cell", "status": "PENDING" | "LIVE" | "NOT_SHOWN" } ]` (only the
caller's live contributions; `cell` = your own report's block, or the shown precision for an answer). `DELETE` →
`204`, withdraws the caller's contribution (a signal nobody supports any more is deleted); someone else's → `404`.
Phase 9D: a joined report appears here as `kind: CONFIRM` with the neighbouring signal's `signalId` and its shown cell
(never the hidden block of a single report).

# Phase 7 endpoints (Verification, Trust and Moderation, V13, docs/PHASE7.md)

Rules for everything below: JWT required; the caller is the JWT subject; **roles are read from the database on every
request** (never from the token or a request field); reason codes only, no free text; people are never identified to
moderators (case-scoped pseudonyms). New status codes: `403` = signed in but not allowed (our message is shown by the
app), `429` limits. Trust tiers and scores are **never** returned by any endpoint except to moderators (as a band).

## Sign-in / sign-up limits (changes to Phase 2 endpoints)
- `POST /api/auth/login` → `429 { "message": "Too many sign-in attempts. Please wait a few minutes and try again." }`
  after 10 failed sign-ins for one e-mail or 30 from one network address within 15 minutes. Checked BEFORE the
  password (a correct password is refused too while blocked); unknown e-mails are counted exactly like known ones.
  A successful sign-in clears the account counter.
- `POST /api/auth/register` → `429 { "message": "Too many new accounts from this network. Please try again later." }`
  after 10 registrations from one address per hour. A `role` field in the body is ignored.
- Config `app.auth.login-failures-per-account | login-failures-per-address | login-window | registrations-per-address |
  registration-window`. In memory, per instance; the address is the TCP peer (forwarded headers are not trusted).

## Community changes
- `GET /api/community/area` signals add `"confidence": "LOW" | "MODERATE" | "HIGH"`, `"reasons": ["SINGLE_REPORT" |
  "CONFIRMED_BY_OTHERS" | "ESTABLISHED_CONTRIBUTORS" | "LINKED_SUPPORT_COUNTED_ONCE" | "DISPUTED", …]` and
  `"youFlagged": false`. `support` and `precision` now count INDEPENDENT people (linked accounts count once).
  Quarantined and removed signals are not listed.
- `GET /api/community/mine` status may also be `"UNDER_REVIEW"` (hidden while moderators look at it).
- `DELETE /api/community/mine/{id}` → `409 This signal is being reviewed. You can withdraw it after the review.` while a
  FLAGS or REREPORTED case about the signal is open, or while it has unresolved flags. **Phase 9C:** a COORDINATION
  case without flags does NOT lock withdrawal (`204` as usual): the 409 would tell a ring it had been detected.
- Report / answer / flag → `403 Your community contributions are paused for now. …` while a moderator's pause is in
  force; `429 You are sending a lot very quickly…` after 10 writes in 10 minutes (an outbox re-send of the same report
  is free). Viewing (`/area`, `/mine`) and every non-community endpoint keep working during a pause.

## Flag: `POST /api/community/signals/{id}/flags` `{ "reason": "FALSE_INFORMATION" | "TARGETS_SOMEONE" | "SPAM_OR_COORDINATED", "near": "tdr1v9q" }`
→ `202 { "status": "RECEIVED" }` — the same answer whatever the flag caused. Same visibility and nearness rules as an
answer. `400` unknown reason; `404` unknown / hidden signal; `409 You added to this signal. To take that back, withdraw
your own answer.`; `409 You have already flagged this signal`; `409` not near; `403` paused; `429` 10 flags / 24 h.
Effects (server only): a counted flag opens a moderation case; two independent flags whose weight reaches the signal's
support hide it (quarantine) until a moderator decides.

## Own standing and appeal
- `GET /api/community/standing` → `{ "contributionsPaused": true, "pausedUntil": "2026-10-27T13:10:00.123456",
  "reason": "FALSE_INFORMATION", "appeal": null | "OPEN" | "UPHELD" | "OVERTURNED", "canAppeal": true }`. UTC without
  zone. No tier, score or count, ever.
- `POST /api/community/appeals` (empty body) → `202 { "status": "OPEN" }`; `409 There is no restriction to appeal.`;
  `409 You have already asked for a review of this decision.`

## Moderation (MODERATOR or ADMIN)
- `GET /api/moderation/access` (any JWT) → `{ "canModerate": bool, "canAdminister": bool }`. Used by the app only to
  show or hide the Moderation entry; it grants nothing.
- Everything else under `/api/moderation/**`: `401` without a token, `403` for any other role or a disabled account,
  `429` after 600 requests per moderator per hour.
- `GET /api/moderation/cases?status=OPEN|CLOSED` (default OPEN, oldest first, ≤ 50) → `[ { "id", "origin": "FLAGS" |
  "REREPORTED" | "COORDINATION", "status", "outcome": null | "DISMISSED" | "REMOVED", "openedOn": "2026-10-20",
  "category", "group", "moderation": "NONE" | "QUARANTINED" | "REMOVED", "flags": { "TARGETS_SOMEONE": 2 },
  "independentSupporters": 1, "confidence": "LOW", "youTookPart": false } ]`. `400` unknown status.
- `GET /api/moderation/cases/{id}` → `{ "summary": {…}, "cell": "tdr1v9q", "areaCell": "tdr1v9", "precision",
  "publiclyVisible", "reasons": […], "independentDisputers": 0, "participants": [ { "ref": "3f9a0c12be47", "role":
  "REPORT" | "CONFIRM" | "NO_LONGER" | null, "flag": null | "<reason>", "tier": "RESTRICTED" | "LIMITED" | "STANDARD" |
  "TRUSTED", "accountAge": "NEW" | "RECENT" | "ESTABLISHED", "counting": true, "linkGroup": null | "A" } ],
  "youTookPart": false, "coordination": null | { "reasons": ["LINKED_GROUP" | "SUPPORT_CORE" | "EXCLUSIVE_PAIR"],
  "groupSize": "TWO" | "THREE_TO_FIVE" | "SIX_OR_MORE", "groupPlaces": null | "TWO_TO_FOUR" | "FIVE_TO_NINE" |
  "TEN_OR_MORE" }, "groupMembers": [ { "ref", "role": null, "flag": null, "tier", "accountAge", "counting",
  "linkGroup" } ] }`. `ref` is valid for this case only and changes when the server restarts. No name, e-mail,
  user id or any other SafeSphere data. `coordination` (Phase 9C) is recomputed on every view for any case: bands
  only, never another signal's cell or a number of areas; `null` when no pattern holds (or the check is OFF).
  `groupMembers` (Phase 9C, `PHASE9.md` §10): the OTHER members of the coord-1 group (SUPPORT_CORE / EXCLUSIVE_PAIR)
  this signal belongs to right now, i.e. people who back at least two of the group's places but nothing on this
  signal; case pseudonyms and bands only, sorted by `ref`; `counting` = their contributions count at all (false once
  paused); `[]` when there is no such group. `403 You took part in this signal; another moderator must review it.`
  (also for anyone in the group behind it); `404`.
- Phase 9C coordination check (server only, `app.moderation.coordination.mode = OFF | SHADOW | ACTIVE`, default
  SHADOW, at most hourly, ACTIVE budget 5 COORDINATION cases per 24 h): SHADOW logs a count; ACTIVE opens one
  COORDINATION case per group of accounts whose support looks coordinated (coord-1 R1/R2), never while one of the
  group's signals has an open case, and not while a signal backed only by its members has a dismissed COORDINATION
  case. No client-visible effect.
- `POST /api/moderation/cases/{id}/actions` `{ "action", "reason", "contributorRef"?, "days"?, "wholeGroup"? }` → the
  case detail afterwards. Allowed reasons: QUARANTINE: NEEDS_REVIEW | FALSE_INFORMATION | TARGETS_SOMEONE |
  SPAM_OR_COORDINATED; DISMISS: NO_VIOLATION; REMOVE: FALSE_INFORMATION | TARGETS_SOMEONE | SPAM_OR_COORDINATED |
  DUPLICATE; RESTRICT: FALSE_INFORMATION | TARGETS_SOMEONE | SPAM_OR_COORDINATED with `days` 1–90 (default 7) and
  EITHER `contributorRef` (a participant or a group member) OR `"wholeGroup": true` (Phase 9C: every member of the
  coord-1 group behind this signal who is not paused yet, in one decision; one audit entry, strike and appealable
  pause per person). `400` reason does not fit / missing participant / both `contributorRef` and `wholeGroup` /
  `wholeGroup` on another action; `403` took part; `404` unknown case or participant; `409 This case is already
  closed.`, `409 This signal is already hidden.`, `409 Someone else changed this at the same time.`, `409 No group
  stands behind this signal any more.`, `409 Everyone in this group is already paused.` Every successful action is
  written to the audit log.
- **Phase 9C.1 (S15 Option B):** DISMISS on a **COORDINATION** case also changes how that one signal counts: the people
  who were counting behind it at that moment count as separate people on it (confidence, precision, crediting), while
  Phase 7 still links them on every other signal and anyone who joins later is counted by the normal rule. Nothing new
  is stored (the closed case row is the mark; it goes after `closed-case-retention` or with the signal). A DISMISS of a
  FLAGS or REREPORTED case does not do this.
- `GET /api/moderation/appeals` → open appeals, never the caller's own: `[ { "id", "status", "createdOn",
  "restrictionReason", "category", "restrictedUntil": "2026-10-27", "currentTier", "strikes", "youDecidedTheRestriction" } ]`.
- `POST /api/moderation/appeals/{id}/decision` `{ "decision": "UPHELD" | "OVERTURNED" }` → the appeal. `400` other
  values; `403 The moderator who made a decision cannot decide its appeal.`; `404`; `409 This appeal is already decided.`

## Administration (ADMIN only; ADMIN itself is set only in the database)
- `PUT /api/admin/roles` `{ "email", "role": "USER" | "MODERATOR" }` → `204` (audited). `400` role ADMIN; `404`
  unknown e-mail; `409` own role or another admin's. Takes effect on the target's next request with the same token.
- `GET /api/admin/moderation-log?page=0` → newest first, ≤ 50: `[ { "id", "actorId", "action", "reason", "caseRef",
  "category", "subjectUserId", "restrictedUntil", "newRole", "occurredAt" } ]` (account ids, never names or e-mails).
- **Phase 9B (V15, `docs/PHASE9.md` §27):** `GET /api/admin/evaluation?weeks=12` (1–104; default 12; the current,
  incomplete week included) → weekly, system-wide outcome counters, derived rates and gate G1. No cell, coordinate,
  signal, case or account id, no e-mail and no time finer than an ISO week (Monday, UTC). `400` weeks outside 1–104;
  `401` no token; `403` USER or MODERATOR. There is no export, no per-area view and no app screen.
  ```json
  { "notice": "System-wide weekly counts … Publication rule: … values below 10 shown as \"fewer than 10\" …",
    "fromWeek": "2026-08-03", "toWeek": "2026-10-19", "currentWeekIncomplete": true, "countersEnabled": true,
    "counters": [ { "weekStart": "2026-10-19", "metric": "CASE_CLOSED", "dimension": "SUPPORT_CORE.REMOVED",
                    "modelVersion": "coord-1", "value": 1 } ],
    "rates": [ { "name": "TRIGGER_PRECISION", "dimension": "FLAGS", "numerator": 1, "denominator": 1, "minimum": 10,
                 "status": "NOT_ENOUGH_DATA", "value": null, "low": null, "high": null, "lowerBound": false } ],
    "g1": { "status": "NOT_MET", "decidedCasesPerFourWeeks": [0, 0, 1], "activeModerators": 1, "note": "…" } }
  ```
  - `metric` / `dimension`: `SIGNAL_STARTED`, `SIGNAL_JOINED_NEIGHBOUR`, `SIGNAL_CONFIRMED` (group); `SIGNAL_ENDED`
    (`{group}.{EXPIRED|RETIRED|WITHDRAWN}_{CONFIRMED|UNCONFIRMED}`); `SIGNAL_REMOVED`
    (`{group}.{CONFIRMED|UNCONFIRMED}_{reason}`); `CASE_OPENED`, `CASE_UNREVIEWED` (trigger: `FLAGS`, `REREPORTED`,
    `LINKED_GROUP`, `SUPPORT_CORE`, `EXCLUSIVE_PAIR`, or `COORDINATION` for cases opened before V15); `CASE_CLOSED`
    (`{trigger}.{REMOVED|DISMISSED}`); `COORDINATION_SHADOW` (coord-1 rule, once per group and week); weekly snapshot:
    `SNAPSHOT_TAKEN`, `VISIBLE_SIGNALS` (`{group}.{UNCONFIRMED|CONFIRMED|STRONG}`), `AREAS_BY_OUTLOOK`,
    `AREAS_BY_INFORMATION` (active `modelVersion`), `SHADOW_PICTURE` (`SAME|LESS_CAUTIOUS|MORE_CAUTIOUS`, `rules-2`).
  - `rates` (Wilson 95 % interval; `NOT_ENOUGH_DATA` below `minimum`, `INCONSISTENT` when the window cuts events
    apart): `CONFIRMATION_YIELD`, `FALSE_CONFIRMATION_RATE` (`lowerBound: true`), `RETIRED_AFTER_CONFIRMATION`,
    `JOIN_YIELD` per group; `TRIGGER_PRECISION`, `UNREVIEWED_SHARE` per trigger; `CONFIRMED_SHARE_OF_VISIBLE`,
    `SHADOW_LESS_CAUTIOUS`, `SHADOW_MORE_CAUTIOUS` from the latest weekly snapshot.
  - `g1.status`: `NOT_MET` or `COUNTS_MET_PRIVACY_REVIEW_REQUIRED` (never "met": the written privacy review G1 also
    needs is not something software can check). Config `app.evaluation.enabled` (true; false = nothing counted and the
    stored rows deleted at the next clean-up), `app.evaluation.retention-weeks` (104, at most 104).

# Phase 8 endpoints (Safety Intelligence, rules-1, V14, docs/PHASE8.md; rules-1.1 since Phase 9D)

Read-only, JWT required, no role. Codes and bands only: no ids of people or signals, no counts, weights, tiers,
timestamps or pattern values. The server sends codes; the app writes the words. Nothing here can start, change or end
an emergency, a walk, an escalation, an alert or a moderation action.

**Phase 9D (`docs/PHASE9.md` §19–§20), no new endpoint or field:** `modelVersion` is `"rules-1.1"` (was `"rules-1"`).
rules-1.1 = rules-1 plus one change: safe places ALONE (no confirmed concern) count at most once, so information stays
`LITTLE` and **`NO_CONFIRMED_CONCERNS` is no longer sent** (older apps keep its text). The `"rules-2"` candidate
("take extra care" from two confirmed concerns needs ≥ 3 independent people) is computed only in SHADOW and never
returned; it would appear as `"rules-2"` only with `app.intelligence.rules-2-mode=ACTIVE` (the user's decision). Config:
`app.intelligence.rules-2-mode` = OFF | SHADOW (default) | ACTIVE, `app.intelligence.rules-2-shadow-interval` (1h): the
shadow logs counts only.

## Area picture: new field in `GET /api/community/area?cell=tdr1v9`
Same request, same limits (120 look-ups / 15 distinct areas per hour). Computed from exactly the visible signals of the
neighbourhood (never pending, quarantined, removed or retired ones), BEFORE the list is cut to 100.
```json
"picture": {
  "modelVersion": "rules-1.1",
  "outlook": "TAKE_EXTRA_CARE",
  "concern": "ELEVATED",
  "information": "SOME",
  "reasons": ["CONFIRMED_CONDITION", "UNCONFIRMED_REPORTS", "SAFE_PLACES_NEARBY"],
  "concernCategories": ["POOR_LIGHTING", "ISOLATED_STRETCH"],
  "conflicting": false,
  "highestConcernCell": "tdr1v9",
  "areas": [ { "cell": "tdr1v9", "outlook": "TAKE_EXTRA_CARE", "information": "SOME" }, … all 9 cells ]
}
```
- `outlook`: `NO_INFORMATION` | `LITTLE_INFORMATION` | `NO_CONFIRMED_CONCERNS` (rules-1 only; never sent since 9D) |
  `TAKE_CARE` | `TAKE_EXTRA_CARE` (none means "safe"). `concern`: `NONE_CONFIRMED` | `SOME` | `ELEVATED` (the HIGHEST
  cell, never a sum). `information`: `NONE` | `LITTLE` | `SOME` | `MORE` (over the whole neighbourhood; havens count at
  most 4, and at most once when there is no confirmed concern).
- `reasons` (reading order): `CONFIRMED_INCIDENT_TODAY`, `CONFIRMED_INCIDENT_THIS_WEEK`, `CONFIRMED_CONDITION`,
  `CONDITIONS_MAY_HAVE_CHANGED`, `CONFLICTING_REPORTS`, `RECURRING_CONCERNS`, `UNCONFIRMED_REPORTS`,
  `SAFE_PLACES_NEARBY`, `LITTLE_INFORMATION`.
- `highestConcernCell`: one of the 6-character cells in `areas`, or `null` when nothing is confirmed.
- `picture` is `null` if it could not be computed (the signals are still returned). Older apps ignore the field.

## Region pack: `GET /api/intelligence/region?cell=tdr1v` (for "Check the way")
`cell` = one 5-character geohash (~4.9 km). The phone briefs the walk itself; the server never receives a route, a
start or a destination.
```json
{ "region": "tdr1v", "modelVersion": "rules-1.1", "complete": true,
  "areas": [ { "cell": "tdr1v9", "outlook": "TAKE_CARE", "concern": "SOME", "information": "LITTLE",
               "reasons": ["CONFIRMED_CONDITION", "UNCONFIRMED_REPORTS"], "concernCategories": ["POOR_LIGHTING"],
               "conflicting": false } ],
  "signals": [ { "category": "POOR_LIGHTING", "group": "CONDITION", "cell": "tdr1v9q", "confidence": "MODERATE",
                 "freshness": "THIS_WEEK", "disputed": false } ] }
```
- `areas` lists only cells the community knows something about: an ABSENT cell means `NO_INFORMATION`, never "safe".
- `signals`: CONFIRMED signals only (MODERATE / HIGH, already public at block precision), no ids; single reports never
  appear individually. `freshness` = independent freshness (linked accounts cannot keep a signal "today").
- `complete: false` (more than `app.intelligence.region-max-signals`, default 500): `signals` is empty and the phone
  shows no brief (a partial list could miss a concern).
- `400 Cell must be a 5-character geohash`; `401`; `429` after 6 DIFFERENT regions or 20 downloads in 24 h per account
  (2 different regions for an account still in its 24 h community probation). The budget is in memory only.

---

# Phase 10A endpoints (account security and deletion, V16, docs/PHASE10.md)

## Every authenticated endpoint: the account check

After the signature, expiry and issuer, every token is checked against its account (one primary-key lookup,
`ActiveAccountCheck`). It is refused with `401` when the account no longer exists or was erased, is disabled, or the
token's credentials stamp (claim `cca`, present once the password was ever changed) is not the account's current one.
Only for such an otherwise valid token the answer names the reason (RFC 6750), so the phone can explain the sign-out:

```
WWW-Authenticate: Bearer error="invalid_token", error_description="credentials_changed"   (password changed)
WWW-Authenticate: Bearer error="invalid_token", error_description="account_deleted"       (deleted or erased)
WWW-Authenticate: Bearer error="invalid_token", error_description="account_unavailable"   (disabled)
```
Missing, malformed, expired or forged tokens keep the plain `WWW-Authenticate: Bearer`. The JSON body is unchanged.
Before Phase 10A a disabled or deleted account's token kept working for up to 24 h everywhere except `/api/users/me`.

## Change password: `PUT /api/users/me/password` `{ "currentPassword": "...", "newPassword": "..." }`

`200` with `{ accessToken, tokenType, expiresInSeconds, user }` (as login): the NEW token for this phone. Every other
token of the account is refused from its next request (`credentials_changed`).
- `400` new password not 8–72 characters, over 72 bytes, or the same as the current one;
- `409 "Password is incorrect"` (409, not 401: a 401 would sign the phone out);
- `409 "You can change your password once your SOS or Safe Walk has ended."` while an emergency or a Safe Walk is
  ACTIVE (a second session must not be able to cut a running safety session off the server);
- `429` after 5 wrong passwords in 15 minutes (shared with the two deletion endpoints; in memory, per account); `401`.

## Request deletion: `POST /api/users/me/deletion` `{ "password": "..." }`

`202 { "deletionRequestedAt": "2026-10-22T10:00:00Z", "deletionDueAt": "2026-10-29T10:00:00Z" }`. Nothing is deleted
before `deletionDueAt` (`app.account.deletion-grace`, 7 days); everything keeps working until then; signing in does
NOT cancel; a repeated request returns the same dates.
- `409 "Your account cannot be deleted while an emergency is active."`;
- `409 "Your account can be deleted 24 hours after your last emergency ended."` (`app.account.deletion-protection`);
- `409 "Password is incorrect"`; `429`; `401`.

## Cancel deletion: `POST /api/users/me/deletion/cancel` `{ "password": "..." }`

`204`, idempotent (also when nothing is pending). `409 "Password is incorrect"`; `429`; `401`.

## The deletion itself (no endpoint)

`AccountDeletionJob` (every 15 min, `app.account.deletion-interval`) takes accounts whose `deletion_due_at` has
passed. If an emergency is ACTIVE or ended less than 24 h ago, the date moves to 24 h after it (the banner then says
"soon"). Otherwise the evidence ciphertext files are deleted first, then every owned row, then the account row; an
account that the moderation audit log names as the deciding moderator is instead kept as an anonymous, disabled
tombstone (`Deleted account`, `erased-<id>@erased.invalid`, `erased_at` set) so the log stays intact. Its e-mail
address can be registered again at once. Config: `app.account.password-failures` (5), `.password-failure-window`
(15m), `.deletion-grace` (7d, at least 1d), `.deletion-protection` (24h), `.deletion-batch` (20),
`.deletion-interval` (PT15M), `.deletion-job-enabled` (true; tests false).

---

# Phase 10A.4 (session continuity, V17, docs/PHASE10.md §11)

## Token claims
Login and register start a sign-in: every token now carries `sid` (a random id of that sign-in) and `auth_time` (epoch
seconds when the password was typed). Renewals keep both; a password change keeps the caller's `sid` and resets
`auth_time`. They are read by the server only; the phone reads nothing but `exp`.

## Renew: `POST /api/users/me/token` (no body)
`200 { accessToken, tokenType, expiresInSeconds, user }` (as login) for the SAME sign-in. Granted while `auth_time` is
younger than `app.account.session-max-age` (30d, at least 1d) and **always while an emergency or a Safe Walk is
ACTIVE**; otherwise `409 "Please sign in again on this phone."` (the current token keeps working until it expires).
`401` for a revoked, disabled or deleted account's token (the account check), as everywhere.

## Ending an ACTIVE emergency: `PUT /api/emergencies/{id}/resolve|cancel` `{ "password": "..." }` (body optional)
The emergency remembers the sign-in that started it (only while ACTIVE). That sign-in sends no body, as before. **Any
other sign-in** must send the account password:
- no password → `403 "This SOS was started on another phone. Enter your account password to end it here."`;
- wrong → `409 "Password is incorrect"` (shared 5/15 min limit with the account endpoints → `429`);
- right → `200`, and the emergency answer now has `"endedElsewhere": true` (also in history and `GET /{id}`).
Order of checks: not yours → `404`; already ended → `409`; then the password. Emergencies started by an older token
without `sid` keep the old rule. Every emergency answer has `endedElsewhere` (false unless ended this way); the session
id is never returned.

# Phase 11 (SOS without internet, V18, docs/PHASE11.md)

The phone starts every SOS by itself (Safety Circle SMS and location work without internet) and records it here when
the server can be reached. Changes to two existing endpoints; no new endpoint.

## `POST /api/emergencies` (body still optional)
```json
{ "triggerType": "MANUAL",
  "clientRef": "6f1c2c3e-1d2b-4c5a-9e8f-0a1b2c3d4e5f",
  "startedAt": "2026-10-22T08:00:00Z",
  "endedAt": "2026-10-22T08:30:00Z", "outcome": "RESOLVED" }
```
- `clientRef` (optional, lower-case UUID made by the phone): the same caller + reference **always answers with the same
  emergency** (`201`, the stored row as it is now, possibly already ended). Another person's identical reference makes
  that person's own emergency; it never reveals or touches the first one.
- `startedAt` (only with `clientRef`): the SOS began before the server could be reached. Clamped by the server to
  `[now − 24 h, now]`; the row is marked `startedOffline: true` (the phone's times). Without it the server's time is used.
- `endedAt` + `outcome` (`RESOLVED` | `CANCELLED`, only together and only with `startedAt`): the SOS also ended on the
  phone while offline; it is stored already ended (`endedAt` clamped to `[startedAt, now]`), with no session id. An
  ended row never conflicts with an ACTIVE one.
- An ACTIVE SOS started offline during a Safe Walk links that walk as before, but only a walk that was already running
  when the SOS began.
- `400`: `clientRef` not a lower-case UUID; `startedAt` without `clientRef`; `outcome` `ACTIVE`; `outcome`/`endedAt`
  alone or without `startedAt`; a reserved trigger. `409`: another emergency is ACTIVE (for an ACTIVE request).

## `PUT /api/emergencies/{id}/resolve|cancel` `{ "password": "...", "endedAt": "..." }` (both optional)
`endedAt` = when the person ended it on a phone that was offline at that moment; clamped to `[startedAt, now]`;
absent = now. Password rules unchanged (Phase 10A.4).

## Responses
Every emergency answer adds `"startedOffline": true|false`. The reference itself is never returned.

## Locations of an SOS started offline
For an emergency with `startedOffline: true`, the 15-minute upload grace counts from its end **or from when the server
first stored it, whichever is later** (an SOS that started and ended offline reaches the server after its end; its
points follow). Online emergencies keep the old rule. Point times must still lie between the start and the end.

# Phase 11 C1 (session continuity: device sessions and refresh secrets, V19, docs/PHASE11.md §5)

Why: the 24 h access token was renewed only while the app ran. After a day without the app the first request answered
401, the phone signed out and wiped its running SOS and Safety Circle copy. Now every sign-in is a **device session**
with a rotating **refresh token**; an expired access token is replaced without the password.

## Sign-in answers
`POST /api/auth/register`, `POST /api/auth/login`, `POST /api/auth/refresh` add `"refreshToken": "<sid>.<secret>"` (the
access token's `sid` + 43 base64url characters, 256 random bits). Token renewal (`POST /api/users/me/token`) and password
change (`PUT /api/users/me/password`) add `"refreshToken"` only when they create a session for a sign-in from before V19
(once); otherwise `null` (the phone keeps the one it has). Only SHA-256 of the secret is stored.

`POST /api/auth/login` accepts an optional `"previousRefreshToken"`: this phone's refresh token for the SAME account
(signing in again after "sign in to sync"). If it is the current or just-replaced secret of a session of that account,
that session ends (`SIGNED_OUT`) and an ACTIVE SOS it started moves to the new sign-in (ending it needs no password).
Another account's token changes nothing.

## Refresh: `POST /api/auth/refresh` `{ "refreshToken": "..." }` (public; the refresh token is the credential)
- `200` like login: a new 24 h access token for the same `sid` and `auth_time`, and a NEW refresh token (the old one is
  replaced). Works however long ago the access token expired.
- The just-replaced token shown again within `app.account.refresh-reuse-grace` (60 s) is a lost answer: rotated once
  more (`200`). Shown later it is **reuse**: the whole session ends (`REUSE_DETECTED`), and its access tokens too.
- `401` + `WWW-Authenticate: Bearer error="invalid_token", error_description="..."`:
  `session_revoked` (unknown, older or guessed secret, signed out, password changed elsewhere, reuse, idle longer than
  `app.account.refresh-idle-timeout` = 90 days); `account_deleted`; `account_unavailable` (disabled). A wrong secret for a
  real session id is refused without ending that session.
- `400`: missing token.

## Sign out: `POST /api/auth/sign-out` `{ "refreshToken": "..." }` (public)
`204` always (reveals nothing). Ends the session if the token is its current or just-replaced secret.

## Every authenticated endpoint
A token whose `sid` names an ended device session is refused: `401` with `error_description="session_revoked"`.
A `sid` without a session row (signed in before V19) is still accepted until it expires or is adopted.

## Password change, deletion, disabling
Password change ends every OTHER device session of the account (`PASSWORD_CHANGED`); the caller's continues. Account
erasure deletes the account's sessions. A disabled account's refresh is refused (`ACCOUNT_DISABLED`).
Ended sessions are deleted `app.account.session-retention` (30 days) after they ended.

# Phase 13A (identity foundation: age gate, profiles, connections, invites, blocks, mutes; V20, docs/PHASE13A.md)

All endpoints below need a JWT. Identity only from the token. A person the caller may not see (missing, blocked either
way, disabled or erased account) answers `404`, exactly like one that does not exist. Ids in URLs are random public ids.
No answer contains a count of anything. Social endpoints answer `409 "Confirm your age to use this"` for an account
that has not declared an adult date of birth. Limits are per person, in memory (`app.social.*`).
**Phase 17A:** that 409 carries `"code": "AGE_NOT_CONFIRMED"`; posting, perspectives and connection writes without a
profile answer `409 "Make your profile first"` with `"code": "PROFILE_NEEDED"`. Words unchanged.

## Age (decision D6: 18+)
- `POST /api/auth/register` now REQUIRES `"dateOfBirth": "yyyy-MM-dd"`. Under 18 (server's UTC date) → `400`
  `"You must be 18 or older to use this app"`; future or > 120 years ago → `400` `"Enter a valid date of birth"`;
  missing → `400` with `fieldErrors.dateOfBirth`. The date is checked and discarded; it is never stored or echoed.
- User answers (`/me`, login, register, refresh, password change) add `"ageConfirmed": true|false`.
- `POST /api/users/me/age-confirmation` `{ "dateOfBirth": "yyyy-MM-dd" }` → `204` (accounts created before V20 confirm
  once; the first confirmation time is kept). Same `400`s as registration.

## Profiles
- `GET /api/profiles/me` → own profile `{publicId, handle, displayName, bio, languages[], interests[], city,
  handleChangeAvailableAt}` (`handleChangeAvailableAt` set while the handle cannot change yet); `404` when none yet.
- `PUT /api/profiles/me` `{handle, displayName, bio?, languages?[], interests?[], city?}` → own profile (creates or
  updates). Handle: lower-cased, a leading `@` dropped, `^[a-z][a-z0-9_.]{1,18}[a-z0-9_]$`, no `..`; bad format `400`;
  reserved word, taken, or reserved for its previous owner (30 days) → `409 "That handle is not available"`; a second
  change within 30 days → `409 "You can change your handle once every 30 days"`. Text: NFC, invisible/control characters
  removed, spaces collapsed; name 1–40, bio ≤ 160, city ≤ 60 letters; links refused (`400 "Links are not allowed in a
  profile"`). `languages` ⊂ `ta en hi ml te kn`; `interests` ⊂ `FOOD_CAFES NATURE_OUTDOORS CULTURE_HERITAGE
  MUSIC_NIGHTLIFE STUDY_WORK DAY_TRIPS ACTIVITIES_SPORT HOBBIES_MAKING CITY_LIFE HIDDEN_GEMS`; anything else `400`.
- `GET /api/profiles/by-handle/{handle}` and `GET /api/profiles/{publicId}` → `{publicId, handle, displayName, bio,
  languages, interests, city, relationship}`; `relationship` = `SELF | NONE | REQUESTED | REQUEST_RECEIVED | CONNECTED`.
  Exact handle only (no prefix or name search); `404 "No one with that handle"`; `429` after 120 look-ups per hour.

## Connections (mutual)
- `POST /api/connections/requests` `{profileId}` → `202 {status: REQUESTED|CONNECTED, person}`. CONNECTED when the
  other person had asked first (waiting, or declined by the caller earlier). Asking again → same `REQUESTED`.
  `400` yourself; `404` not visible; `409` already connected / 50 requests waiting / `"Make your profile first"`;
  `429` after 30 per day.
- `GET /api/connections/requests?direction=incoming|outgoing` → `[{requestId, person{publicId, handle, displayName}}]`.
  A declined request is not shown to the decliner again and still shows to its sender (a decline is silent).
- `POST /api/connections/requests/{requestId}/accept` → `200 {status: CONNECTED, person}`;
  `POST …/decline` → `204`; `DELETE /api/connections/requests/{requestId}` (the sender cancels) → `204`; else `404`.
- `GET /api/connections` → own connections `[{publicId, handle, displayName}]`, most recent first. Nobody else can list them.
- `DELETE /api/connections/{profileId}` → `204` (silent); `404` when not connected. At most 1000 connections each (`409`).

## Invite codes
- `POST /api/connections/invites` → `201 {id, code, expiresAt}`: 26 characters (Crockford base 32), shown ONCE; only
  its SHA-256 is stored; works once for 7 days. `409` with 5 active codes; `429` after 10 per day; `409` without a profile.
- `GET /api/connections/invites` → `[{id, expiresAt}]` (active only, never the code); `DELETE …/invites/{id}` → `204`.
- `POST /api/connections/invites/redeem` `{code}` (case, spaces, hyphens and O/I/L look-alikes forgiven) → `200
  {status: CONNECTED, person}`. Unknown, used, expired, revoked, own, blocked-either-way → the same
  `404 "This invite code does not work"`; already connected → `409`; `429` after 10 attempts per hour.

## Blocks and mutes (private to their owner; the other person is never told)
- `POST /api/blocks` `{profileId}` → `204` (idempotent; works on any existing profile id, also someone who blocked you).
  Removes the connection and every request between the two in the same transaction; both become invisible to each
  other everywhere. `GET /api/blocks` → own list; `DELETE /api/blocks/{profileId}` → `204` (does not reconnect).
- `POST /api/mutes` `{profileId}` → `204`; `GET /api/mutes`; `DELETE /api/mutes/{profileId}` → `204`. A mute changes
  nothing for the muted person; it takes effect on content from 13B on. Yourself → `400`; unknown id → `404`.

# Phase 13B: experiences (V21, `docs/PHASE13B.md`)

All need a JWT and an age-confirmed account (`409 "Confirm your age to use this"`); posting and perspectives also need
a profile (`409 "Make your profile first"`). Not visible = **404**, exactly like missing. No answer carries a count, a
coordinate, or (for anyone but the author) a timestamp. Limits per person, in memory: places 10/day, place look-ups
60/h, posts 20/day, photos 30/day, perspectives 100/day → `429`. Config `app.social.*` (places-per-day,
place-lookups-per-hour, posts-per-day, media-per-day, comments-per-day, exact-delay-min/max 1h/3h, media-dir
`SOCIAL_MEDIA_DIR` default `social-media-store`, max-media-bytes 5 MiB, unattached-media-lifetime 24h,
media-cleanup-enabled, media-cleanup-interval PT1H).

Codes: type `EXPERIENCE|ASK` (TAKE → 400); precision `EXACT|AREA|CITY|NONE`; audience `EVERYONE|CONNECTIONS|ONLY_ME`;
commentPolicy `EVERYONE|CONNECTIONS|NOBODY` (ONLY_ME always NOBODY); daypart `MORNING|AFTERNOON|EVENING|NIGHT`; category
= the 10 interest codes; city `CHENNAI|COIMBATORE|MADURAI|TIRUCHIRAPPALLI|SALEM|TIRUNELVELI|VELLORE|ERODE|THOOTHUKUDI|
THANJAVUR|PUDUCHERRY|BENGALURU|OTHER`; band `THIS_WEEK|THIS_MONTH|EARLIER`; kind `BEEN_HERE|QUESTION|REPLY|ANSWER`.

## Places (no creator, no coordinates)
- `POST /api/places` `{name, category, cell(7)}` → `200 {publicId, name, category, cell, areaCell}` — the existing place
  when the same normalised name exists in that cell. `400`: cell not 7 lower-case geohash characters, unknown category,
  name 2–60 characters / no links / at least a letter or digit / not a private place ("my home", "hostel room",
  "en veedu", "door no 12"…).
- `GET /api/places/nearby?area=(6)` → up to 100 places in the area and its 8 neighbours, by name. `400` for anything
  that is not a 6-character area.
- `GET /api/places/{id}` → one place (a merged place answers as the place it was merged into); `404`.

## Photos
- `POST /api/media` — body = the image, `Content-Type: image/jpeg|image/png` (others `415`), ≤ 5 MB → `201 {mediaId,
  width, height}`. Recognised by magic bytes; size read before decoding (≤ 4096 px a side); decoded and re-encoded as
  a JPEG **without any metadata**; the original bytes are never stored. `400` for non-images, damaged/truncated files,
  too large. Only its owner can fetch it until a post uses it; unattached uploads are deleted after 24 h.
- `GET /api/media/{mediaId}` → `image/jpeg`, `Cache-Control: no-store, private`, `X-Content-Type-Options: nosniff`.
  Visible exactly where its post is visible; `404` otherwise.

## Posts
- `POST /api/posts` `{type, body, tip?, category?, placeId?, precision, city?, experiencedMonth? "YYYY-MM", daypart?,
  audience, commentPolicy, mediaIds[≤6]?}` → `201` post (author view). Rules: Experience body 1–5000, tip ≤ 300, month
  required (not later than the month of UTC+14 h, not before 1950); Ask body 10–1000, no tip, no month; EXACT/AREA need
  `placeId`; CITY needs `city` and no place; NONE neither; audience required (no default). `mediaIds` = the caller's
  own unattached uploads, attached in this order (`404` otherwise). **EXACT posts become visible to everyone but the
  author after a random 1–3 h** (`visibleFrom`).
- `GET /api/posts/{id}` → `{publicId, type, author{publicId, handle, displayName}, own, body, tip, category, precision,
  place{publicId, name} (EXACT only), areaCell (AREA only, 6 characters), city (CITY only), experiencedMonth, daypart,
  postedPeriod (band), edited, media[{mediaId, width, height}], audience (author only), commentPolicy, canComment,
  postedAt (author only), visibleFrom (author only)}`.
- `PUT /api/posts/{id}` (author) `{body, tip?, category?, precision, city?, experiencedMonth?, daypart?, audience,
  commentPolicy}` → `200`, marked `edited`. Audience and precision may only stay or narrow (`409` otherwise, also for
  moving a CITY post to another city); the place and photos never change; narrowing to CITY needs `city`.
- `DELETE /api/posts/{id}` (author) → `204`: perspectives, photo rows and the post, then the photo files.
- `GET /api/posts/mine?cursor=` and `GET /api/profiles/{profileId}/posts?cursor=` → `{items[post], nextCursor}`, newest
  first, 20 per page; `nextCursor` = the last item's id (more only on request). A profile the viewer may not see → `404`;
  a cursor naming a post the viewer cannot see → `400`.

## Perspectives
- `GET /api/posts/{id}/comments` → `[{publicId, kind, author, own, body, postedPeriod, hidden, helped, parentId,
  canHide, canMarkHelped}]`: on an Experience Been here too first, on an Ask the answers marked helpful first, then
  oldest first; replies follow their parent. A perspective hidden by the post's author is returned only to its writer
  and the author (`hidden: true`), and its replies to nobody else; perspectives by someone blocked either way are left
  out.
- `POST /api/posts/{id}/comments` `{kind, body (1–1000), parentId?}` → `201`. Top level: BEEN_HERE/QUESTION on an
  Experience, ANSWER/QUESTION on an Ask; REPLY only with `parentId` of a visible top-level perspective of the same post
  (one level). Who may write = the comment policy (the author always may, except on ONLY_ME); not allowed → `404`.
- `DELETE /api/comments/{id}` (its writer) → `204`, with the replies under it.
- `POST /api/comments/{id}/hide|unhide` (the post's author) → `204`.
- `POST|DELETE /api/comments/{id}/helped` (the asker, ANSWER on an Ask) → `204`; anything else `404`.

# Phase 13C: intent and discovery (V22, `docs/PHASE13C.md`)

All authenticated and age-confirmed; invisible = `404`; every list ends and gives more only with an explicit `cursor`;
no counts except the author's own tally; no coordinates; bands instead of times. Limits (in memory, `429`): butterfly
changes 200/day, Discover reads 240/h, place searches 120/h (`app.social.butterflies-per-day`,
`discover-reads-per-hour`, `place-searches-per-hour`).

## Butterfly and Cocoon

- `PUT /api/posts/{id}/butterfly` `{state?: "WANT" | "BEEN"}` (body optional, default WANT) → `200 {state}`. Only
  someone else's Experience the caller can see: own post or Ask → `400`; not visible → `404`. Marking Been again keeps
  the first time; WANT clears it.
- `DELETE /api/posts/{id}/butterfly` → `204` (works while the post is not visible to the caller); none → `404`.
- `GET /api/cocoon?state=WANT|BEEN&cursor=` → `{items[{post, state, savedPeriod}], nextCursor}`: the caller's own,
  newest saved first, 20 per page; posts the caller can no longer see are skipped (not deleted). `cursor` = a post id
  in that list.
- Every post answer (`GET /api/posts/{id}`, lists, Discover) adds `yourButterfly` (`null|WANT|BEEN`, the caller's own)
  and `butterflies` — only for the author of an Experience shared with others: `{count: n|null, fewerThanThree:
  bool}`, a number only from 3 people up; `null` for everyone else and on Only-me posts.

## Discover

- `GET /api/discover/edition?city=CHENNAI` (closed list without OTHER; else `400`) → `{city, weekOf (Monday, yyyy-MM-dd,
  Asia/Kolkata week), theme (category code), sections[{kind CITY|ASKS|THEME, items[{post, reasons[]}]}]}`. Everyone
  posts of other people in the city: CITY = Experiences visible since the start of last week (≤ 12); ASKS = Asks of
  the last 28 days without a "This helped" answer (≤ 5); THEME = Experiences of the week's category from the last 56
  days not already shown (≤ 6). Empty sections are left out. A post is in a city by its CITY precision or its place's
  city (EXACT, AREA). Muted and blocked people are left out.
- Reason codes: `IN_THIS_CITY`, `RECENT`, `HAS_PLACE`, `HAS_WHEN`, `HAS_TIP`, `MATCHES_YOUR_INTERESTS`,
  `BEEN_HERE_TOO`, `OPEN_ASK`, `THEME_OF_THE_WEEK`, `FROM_A_CONNECTION`. The order: these content facts, ties newest
  first; never counts or engagement.
- `GET /api/discover/connections?cursor=` → `{items[{post, reasons:[FROM_A_CONNECTION]}], nextCursor}`: accepted
  connections' Everyone + Connections posts of the last 30 days, newest visible first, 20 per page; muted out.
- `GET /api/discover/categories/{category}?city=&cursor=` → post page (`{items, nextCursor}`) of Everyone posts.
- `GET /api/places/{id}/posts?cursor=` → `{place, items, nextCursor}`: only EXACT-precision Everyone posts at that
  place (an AREA post never appears: it promised not to reveal the place). Unknown place `404`.
- `GET /api/places/search?q=&city=` → up to 20 places whose normalised name contains `q` (2–40 characters, letters or
  digits; else `400`), optionally in one city. Places only: no people, no post text.
- Place answers (`POST /api/places`, nearby, one, search, place page) add `city` (launch city from the server's boxes,
  or `null`).

# Phase 13D: moderation v2 (V23, `docs/PHASE13D.md`)

All authenticated; the member endpoints need an age-confirmed account; invisible = `404`. Nothing returned to the
person concerned names a reporter, says a report exists, or carries a count.

## Reports (any member; never paused)

- `POST /api/reports` `{targetKind: "POST"|"COMMENT"|"PERSON", targetId, reason, note?}` → `202 {status: "RECEIVED"}`,
  also when the caller had already reported it (nothing changes). `targetId` = post / perspective / profile public id.
  Reasons: `HARASSMENT`, `SEXUAL_HARASSMENT`, `HATE`, `THREAT`, `DOXXING`, `SPAM`, `NUDITY_OR_VIOLENCE`,
  `IMPERSONATION` (people only), `OTHER` (needs `note`, 1–200 characters; no note otherwise). `400` own content /
  unknown reason / note rules; `404` not visible to the caller; `429` after 20 a day (`app.moderation.reports-per-day`).
- Three independent counting reports collapse a post or perspective (`app.moderation.collapse-reports`, 3). A report
  counts from 72 h after its reporter's sign-up (`app.social.probation`) and while the reporter is not paused.
- `GET /api/reports/mine` → `[{reportId, targetKind, reason, status: RECEIVED|REVIEWED, outcome:
  null|ACTION_TAKEN|NO_VIOLATION, period}]` (newest 50; only the caller's own).

## Changes to posts and perspectives

- Post answers add `approveFirst` (bool); for the author `moderation: null | {state: COLLAPSED|HELD|REMOVED, reason,
  decisionId}` (`reason` and `decisionId` are `null` for a collapse while reviewed); for everyone else `collapsed:
  null | UNDER_REVIEW | BY_MODERATOR`. HELD and REMOVED posts are `404` to everyone but the author (also in lists,
  photos, perspectives and the Cocoon); collapsed posts are left out of the Edition only.
- Perspective answers add `awaitingApproval` (writer and post author), `canApprove` (post author), `moderation`
  (writer), `collapsed` (others).
- `POST /api/posts` and `PUT /api/posts/{id}` accept `approveFirst` (only with `commentPolicy: EVERYONE`, else `400`).
  With it, perspectives from people not connected with the author wait; `POST /api/comments/{id}/approve` (post
  author) → `204`.
- New accounts (72 h): at most 3 posts and 10 perspectives a day, no links in them (`400`).
- A pause answers `403` with a calm message: comments paused → new perspectives; posting paused → also posts, photos,
  places; suspended → also connection requests, accepting, invite codes and profile changes. Reading, blocking, muting,
  reporting, deleting, narrowing and every safety endpoint are never refused.

## The person's own standing

- `GET /api/social-standing` → `{restriction: null | {level: COMMENTS_PAUSED|POSTING_PAUSED|SUSPENDED, until, reason,
  decisionId, appeal: NONE|OPEN|UPHELD|OVERTURNED, canAppeal}, decisions[{decisionId, action, reason, targetKind,
  targetId, period, appeal, canAppeal}]}` — decisions of the last 90 days; "keep" decisions are never listed.
- `POST /api/social-standing/decisions/{id}/appeal` → `202 {status: "OPEN"}`. Appealable: `CONTENT_COLLAPSE`,
  `CONTENT_REMOVE` (while the content still carries it), the three pauses (while in force). `404` not theirs; `409`
  already asked / no longer applies.

## Moderation (MODERATOR or ADMIN, roles read from the database; shared per-hour limit)

- `GET /api/moderation/content-cases?status=OPEN|CLOSED` → `[{caseId, targetKind, status, outcome, openedOn, topReason,
  reasons{code: n}, counting, independent, contentState, priority, youTookPart}]` (open: priority = severity ×
  independent reports, then oldest).
- `GET /api/moderation/content-cases/{id}` → `{summary, content{postType, commentKind, body, tip, category, precision,
  placeName (EXACT), areaCell (AREA), city, audience, experiencedMonth, daypart, media[], contextBody, person{displayName,
  handle, bio, city, interests} (PERSON)}, revisions[{body, tip, replacedOn}], participants[{ref, role
  AUTHOR|SUBJECT|REPORTER, reason, note, counting, accountAge, linkGroup}], author{ref, strikePoints, suggestedStep,
  restriction, restrictedUntil}, possibleActions[], decisions[]}`. `403` for a moderator who wrote it, is the person or
  reported it.
- `POST /api/moderation/content-cases/{id}/actions` `{action, reason}`: `CONTENT_KEEP` (`NO_VIOLATION`), `CONTENT_HOLD`
  (`NEEDS_REVIEW` or a violation), `CONTENT_COLLAPSE`, `CONTENT_REMOVE`, `PERSON_KEEP` (`NO_VIOLATION`, person cases),
  `PERSON_WARN`, `PERSON_PAUSE_COMMENTS` (3 d), `PERSON_PAUSE_POSTING` (7 d), `PERSON_SUSPEND` (30 d); violations are the
  report reasons. Person actions also on a closed content case whose outcome is ACTION_TAKEN. `400` action/reason;
  `409` closed or another moderator first.
- `GET /api/moderation/content-cases/{id}/media/{mediaId}` → the photo (`no-store`), only the case post's photos.
- `GET /api/moderation/content-appeals`, `POST /api/moderation/content-appeals/{id}/decision {decision: UPHELD|
  OVERTURNED}` (`403` for the decider or a moderator who reported it; `409` decided). `GET /api/moderation/appeals`
  (Phase 7) now lists community RESTRICT appeals only.
- `GET /api/moderation/places?area=` (6 characters) → `[{placeId, name, category, cell, mergedInto}]`;
  `POST /api/moderation/places/{id}/merge {into}` → `204` (same area only, else `400`; `409` one is already merged). The
  duplicate's posts move; it disappears from search and nearby; reading it gives the kept place.

# Phase 13E: the automatic check (V24, `docs/PHASE13E.md`)

Every Experience, Ask and perspective that someone else can see is checked when it is written and when its words change
(an only-me post never is). The internal moderation service returns evidence; the backend's DecisionPolicy decides
PUBLISH / PUBLISH_COLLAPSED / HOLD_FOR_REVIEW. Nothing is ever refused: a write answers as before (`201` / `200`). The
check never removes anything and never restricts a person.

## Changes to posts and perspectives

- Everyone: `strongLanguage` (bool) — the check found strong language (an app may let its viewer collapse it).
- Others: `collapsed` may also be `BY_SYSTEM` ("our system thinks this may be hurtful").
- The author's `moderation` adds `source: "SYSTEM" | "MODERATOR" | null` (`null` = a collapse by reports while a
  moderator looks: the author is never told that reports exist) and `flagged: [{start, end}]` (SYSTEM only): code-point
  offsets of the phrases the check noticed, in the checked text = the body, a new line and the tip (or the perspective).
- New reason `AWAITING_CHECK`: a new account's (72 h) content waits for an unavailable check, at most 2 hours
  (`app.moderation.ai.probation-wait-max`), then it is published anyway. `NEEDS_REVIEW` from the system = a new
  account's post for everyone with photos waits for a moderator.
- The author's own create/edit answer may carry `supportSuggested: true` (the words suggest they may be going through
  something hard; an app shows a support line). It is in that answer only and is stored nowhere.

## Ask for a review of an automatic collapse

- `POST /api/content-checks/review` `{targetKind: "POST"|"COMMENT", targetId}` → `202 {status: "RECEIVED"}`, the same
  whether a case already existed or not. Own content only, collapsed or held by the system; else `404`; other kinds
  `400`. A hold is already in front of a moderator.

## Moderators

- Case summaries add `openedBy: REPORTS|SYSTEM|AUTHOR_REQUEST` and `contentSource: REPORTS|SYSTEM|MODERATOR|null`.
- Case detail adds `assessment: null | {status, action, rule, reason, evidence[{category, severity 0-3, targeted,
  quoted, start, end}], strongLanguage, normaliserVersion, lexiconVersion, modelVersion, policyVersion}`. Never a
  self-harm signal (not stored). Decisions are the 13D actions; KEEP on a system hold or collapse restores the content
  and is counted (`AI_REVIEWED`).

## The internal service (not exposed; `moderation-service/README.md`)

- `POST /v1/assess` with `X-Moderation-Key` `{kind: POST|COMMENT, text}` → `{versions{normaliser, lexicon, model},
  script, evidence[{category, severity, targeted, quoted, start, end, source}], strongLanguage, selfHarm, modelScores}`;
  `GET /v1/health`. The backend waits at most `app.moderation.ai.timeout` (1500 ms); unavailable → published and
  retried (`PENDING`, backoff 1 → 30 min, for 24 h).
- Config `app.moderation.ai.*`: `enabled` (true), `url` (`http://127.0.0.1:8091`), `key` (env `MODERATION_SERVICE_KEY`,
  16+ characters, else the check is off and the start-up log says so), `connect-timeout` 300ms, `timeout` 1500ms,
  `model-mode` OFF|SHADOW|ACTIVE (OFF; no model ships), `probation-wait-max` 2h, `retry-for` 24h, `retention` 90d,
  `job-interval` PT5M.
- ADMIN `GET /api/admin/evaluation` lists the new weekly counters `AI_CHECKED` (`{action}.{rule}`) and `AI_REVIEWED`
  (`{state}.{decision}`), version `lex-1`.


# Phase 17C: media — photos and one video per post (`docs/PHASE17C.md`, V27)

- `POST /api/media` with `Content-Type: video/mp4`, the body the MP4 itself (≤ 50 MB, ≤ 60 s + 0.5 s rounding,
  exactly one picture track, ≤ 1 sound track) → **201** `{mediaId, kind: "VIDEO", width, height, durationMs}`. The
  server stores only its stripped copy (metadata boxes zeroed, times zeroed). 400 with words for anything it refuses
  (not an MP4, damaged, too long, too large, extra tracks, fragmented, unknown codec; an oversized body too);
  429 after `app.social.videos-per-day` (10). Photos unchanged (`image/jpeg|png`); every upload answer now also has
  `kind` (and `durationMs` for a video).
- `POST /api/media/{id}/poster` (`image/jpeg|png`, ≤ 5 MB): the poster frame of the caller's own video that no post
  uses (sent again = replaced) → 200 the upload answer; anything else 404. Sanitised like a photo.
- `GET /api/media/{id}`: a photo, or a video's poster (always `image/jpeg`; a video without a poster yet = 404).
- `GET /api/media/{id}/video` → `video/mp4` from the file, `Accept-Ranges: bytes`, `Range` → 206; `no-store, private`,
  `nosniff`. Same visibility as the post (owner only before it is attached).
- `DELETE /api/media/{id}` → 204: the caller's own upload that no post uses (photo or video); else 404.
- `POST /api/posts`: `mediaIds` ≤ 6 photos or videos, **at most one video** (400); a video without its poster, or any
  item whose file is missing → **409** "not ready" (no post is created).
- Post answers: `media[] {mediaId, width, height, kind: PHOTO|VIDEO, durationMs (video only)}`. Moderators: case
  `content.media[]` gets the same `kind`/`durationMs`; `GET /api/moderation/content-cases/{id}/media/{mediaId}/video`.
- Config: `app.social.max-video-bytes` (52428800, at most that), `app.social.videos-per-day` (10).


# Phase 17B: phone verification (`docs/PHASE17B.md`) — replaces the V25 e-mail flow below


A new account gets **no session** until the 6-digit code texted to its mobile number is entered. E-mail + password stays
the sign-in. All endpoints are public (the password or the challenge token is the credential) and limited in memory.

- `POST /api/auth/register {name, email, password, dateOfBirth, phone}` → **201**
  `{status: "PHONE_VERIFICATION_REQUIRED", challenge, phoneHint: "••••••3210", needsPhone: false, codeSent,
  resendInSeconds, codeExpiresInSeconds}` (no token). `phone`: an Indian mobile number, any common way of typing it;
  400 `PHONE_INVALID` otherwise. `codeSent: false` = the SMS provider refused; resend at once.
- `POST /api/auth/login` of an account without a verified phone → **403** `code: PHONE_NOT_VERIFIED` (only after the
  right password). Accounts from before 17B keep their existing sessions.
- `POST /api/auth/phone/start {email, password, phone?}` → 200 the same challenge shape (an interrupted sign-up gets a
  new token for the same verification; a code already sent still works). Wrong password = the generic 401.
  `needsPhone: true` = the account has no number: send one with `/phone/change`. 409 `PHONE_ALREADY_VERIFIED`.
- `POST /api/auth/phone/verify {challenge, code}` → **200** the sign-in answer (`accessToken`, `refreshToken`, `user`).
  400 `OTP_INVALID` (message says the tries left), `OTP_EXPIRED`, `CHALLENGE_INVALID`, `PHONE_REQUIRED`; 429
  `OTP_LOCKED` (5 wrong codes: resend); 409 `PHONE_IN_USE`.
- `POST /api/auth/phone/resend {challenge}` → 200 challenge; 429 `OTP_WAIT` (60 s cooldown, 5 codes per challenge,
  8 per number per day; the message says how long).
- `POST /api/auth/phone/change {challenge, phone}` → 200 challenge (no cooldown; caps apply); 400 `PHONE_INVALID`.
- Config `app.otp.*` (`OTP_PROVIDER` DEV_FILE|FAST2SMS, `FAST2SMS_API_KEY`, `OTP_SECRET`, `OTP_DEV_FILE`).
- **Removed:** `POST /api/auth/verify-email`, `POST /api/auth/verification/resend`, `GET /verify-email(.js|.css)`.

# V25: e-mail ownership verification (`docs/EMAIL_VERIFICATION.md`) — SUPERSEDED by Phase 17B (history)

A new account has an unverified e-mail and gets **no session** until its owner opens the link e-mailed to that address.
Accounts that existed before V25 are verified. Every error body (ApiError) may now carry an optional `code`; it is
present only where an app must react (below) and absent everywhere else.

- `POST /api/auth/register` (body unchanged) → **201** `{status: "VERIFICATION_REQUIRED", email}` — no `accessToken`,
  no `refreshToken`, no `user`. The server e-mails `PUBLIC_BASE_URL/verify-email#t=<token>` (after commit, in the
  background). 409 for an address already in use, EXCEPT an unverified account whose last link expired: it is replaced.
  400 / 429 as before.
- `POST /api/auth/login`: the right password of an unverified account → **403** `{code: "EMAIL_NOT_VERIFIED", message:
  "Please verify your email before signing in"}`, no session. A wrong password or unknown e-mail is the generic 401 as
  before (no code).
- `POST /api/auth/verify-email` `{token}` (public) → 200 `{status: "VERIFIED"}`. Every link that cannot verify
  (unknown, malformed, already used, replaced by a newer e-mail, expired) → the same **400** `{code:
  "VERIFICATION_LINK_INVALID", message: "This link is not valid or has expired"}`. 429 after 30 failed attempts per
  network address per 15 min. A link works once, for 60 minutes.
- `POST /api/auth/verification/resend` `{email, password}` (public) → **202** `{status: "RECEIVED"}` for EVERY request
  (unknown address, wrong password, already verified, real). Only the right password of an unverified account gets a
  new e-mail; its new link replaces the previous one. 429 after 3 per e-mail address or 20 per network address per hour.
- `GET /verify-email`, `GET /verify-email.js`, `GET /verify-email.css` (public): the confirmation page. The token stays in
  the URL fragment; the page posts it to `/api/auth/verify-email` when the person taps "Confirm my email". Headers:
  `Content-Security-Policy: default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; …;
  frame-ancestors 'none'`, `Referrer-Policy: no-referrer`, `Cache-Control: no-store`.
- Defence in depth: an access token or refresh of an unverified account is refused (401 `account_unavailable`); none is
  ever issued.
- Config: `PUBLIC_BASE_URL`, `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM`, `MAIL_STARTTLS`;
  `app.email-verification.*` (see `docs/EMAIL_VERIFICATION.md` §4).

## Phase 14 (security audit, `docs/PHASE14.md`): no new endpoint or field

- `GET /api/profiles/{id}/posts` (someone else's posts) is now ordered and paged newest **visible** first
  (`visible_from`, id), like every other list of others' posts; the cursor is still a post id. Your own list
  (`/api/posts/mine`, or your own profile) keeps creation order.
- `POST /api/reports` keeps answering the same 202 when two reports arrive at once (also the same person twice);
  `POST /api/blocks` / `POST /api/mutes` sent twice at once both answer 204 (were 500).
- Moderators: on a perspective case, the author of the post it was written on now counts as taking part
  (`youTookPart: true`, 403 on detail / actions / photos, and kept out of that decision's appeal).

## Phase 16 (UX reset, `docs/PHASE16.md`; no migration, no new endpoint)

- **Every post answer** (`GET /api/posts/{id}`, lists, Discover, Saved/Cocoon, place and category pages, profile lists,
  create/edit answers) adds `commentCount` (integer ≥ 0): how many perspectives **this viewer** can read on the post,
  computed with the comment list's own rule (`Visibility.canSeeComment`, replies only under a readable parent), so it
  always equals the length of `GET /api/posts/{id}/comments` for the same viewer. A hidden, held, blocked or
  awaiting-approval perspective is not counted for those who cannot read it. Decision U1 (the only public number).
- **`GET /api/discover/edition`** may add a section `kind: "TALKING"` ("Talking now", decision U2): up to 8 Everyone
  posts of the city (Experiences or Asks, visible in the last 56 days, not the viewer's) with a perspective from
  someone other than the author in the last 7 days, **ordered by the time of the newest perspective the viewer can
  read** (never by totals). Its items carry the reasons `IN_THIS_CITY`, `NEW_PERSPECTIVES`. Older phones drop the
  unknown section (DTOs fail closed). The order's time is never returned.

## Phase 17E (social redesign, `docs/PHASE17E.md`; migration V28)

**Butterfly = a reaction, Save = a private bookmark (D-3).** Both idempotent, so a feed can toggle optimistically.
- `PUT /api/posts/{id}/reaction` → 200 `{marked: true}` (again = the same 200, one row). Someone else's **Experience**
  the caller can see; own post or an Ask → 400; invisible or unknown → 404. `DELETE /api/posts/{id}/reaction` → 204
  always (also when there was none or the post is gone).
- `PUT /api/posts/{id}/save` → 200 `{marked: true}`: someone else's Experience **or Ask** the caller can see; own post →
  400; invisible → 404. `DELETE /api/posts/{id}/save` → 204 always (works after a block: it is the caller's row).
- `GET /api/saved?cursor=` → `{items[{post, savedPeriod}], nextCursor}`: newest saved first, 20 per page, posts the
  caller can no longer see are skipped (the one visibility rule).
- Shared limit `app.social.butterflies-per-day` (200 changes of either mark) → 429.
- Post answers: `youReacted`, `saved` (the viewer's own marks only); `butterflies {count, fewerThanThree}` only for the
  author of an Experience shared with others (count from 3 people, never who). Saves are counted for nobody.
- **Removed:** `PUT|DELETE /api/posts/{id}/butterfly`, `GET /api/cocoon` (404/405).

**Profile photo.**
- `PUT /api/profiles/me/photo` (body `image/jpeg` or `image/png`, ≤ 5 MB) → 200 `{photo: "<uuid>"}`: centred square, at
  most 720 px, re-encoded without metadata, a **new key every upload** (the old file is deleted after the commit).
  Not an image → 400; no profile → 404; suspended → 403. `DELETE /api/profiles/me/photo` → 204 always.
- `GET /api/profiles/{profileId}/photo/{key}` → the JPEG (`no-store, private`, `nosniff`) only for someone who may see
  that profile and only under its current key; blocked, wrong key, replaced or removed → 404; no sign-in → 401.
- `photo` (key or null) in every person summary (`publicId, handle, displayName, photo`), in `GET /api/profiles/me` and
  in a person's profile.

**Today's state (D-7).**
- `PUT /api/profiles/me/daily-state {state}` → 200 `{dailyState}`; `state` one of `CURIOUS, PLANNING, INSPIRED,
  LEARNING, CELEBRATING, SLOW_DAY, BUSY_DAY, QUIET_DAY` (moods and intents only; anything else → 400).
  `DELETE /api/profiles/me/daily-state` → 204.
- `dailyState` in `GET /api/profiles/me` and in a person's profile **only when the viewer is a connection** (null for
  everyone else, exactly as if there were none). Never in posts, lists, people rows or notifications. It counts only on
  the India (IST) day it was chosen: null from midnight IST, rows of earlier days deleted by the hourly clean-up.

**Cities (D-5).** `places.city` and a CITY post's `city` are codes from the catalog `cities-v1.tsv` (85 Indian cities;
`OTHER` = "Another city", posts only). Unknown codes → 400. The phone searches the same bundled catalog; there is no
city search endpoint (search words never leave the phone).

**Moderation.** Person cases: detail adds `person.hasPhoto`; `GET /api/moderation/content-cases/{id}/person-photo` (the
JPEG; moderators who did not take part; other kinds → 404); action `PERSON_REMOVE_PHOTO` (with a violation reason): the
photo goes, the case stays OPEN, no strike, not appealable, shown to the person in `GET /api/social-standing` as a
decision with `canAppeal: false`, audited in `moderation_actions`.

---

## Phase 17F (emergency; no migration, no new endpoint)

`POST /api/emergencies` now accepts `triggerType` **`SHAKE`** besides `MANUAL` (the default). The answer, the history and the
emergency context packet carry it back unchanged as `triggerType`. It is only a label for the person's own history: a
shake still went through the phone's cancellable 5-second countdown before this call, and nothing on the server treats
a SHAKE emergency differently. `VOICE` (deferred, D-4) and `DURESS_PIN` still answer `400`. It works on the offline path
too (`clientRef`, `startedAt`): the phone keeps the trigger with the record and sends it with every attempt. The column
`emergency_events.trigger_type` has no CHECK (verified on MySQL 8.0), so no migration was needed.

# Phase D12: health check (`docs/D12_DEPLOYMENT.md`)

- `GET /api/health` (public, no sign-in, GET only) -> `200 {"status":"UP"}` while the database answers a 2-second ping, otherwise
  `503 {"status":"DOWN"}`. Nothing else is ever in the body: no version, no exception text, no time, no counts, nothing about any
  person. `Cache-Control: no-store`, no CORS headers. Any other method, or a sub-path such as `/api/health/details`, is `401` like every
  other protected path. For an uptime monitor and `deploy/scripts/smoke-test.sh`. The `prod` profile binds the backend to
  `127.0.0.1` (`SERVER_ADDRESS`), so the monitor must call it through the HTTPS proxy.

# Phase N2: phone-only sign-in, real name, no password (`docs/PHASE_N2.md`, V33)

All `/api/auth/*` endpoints are public (the challenge, ticket or refresh token is the credential) and limited in
memory. Numbers: an Indian mobile number, any common way of typing it (`9876543210`, `+91 98765 43210`, …). Codes: 6
random digits, 5 minutes, stored only as an HMAC, never logged or returned. Refusals carry a stable `code` (below).

**Sign in or sign up (one flow, N-21)**
- `POST /api/auth/phone/start {phone}` → **200** `{challenge, phoneHint: "••••••3210", codeSent, resendInSeconds,
  codeExpiresInSeconds}`. The SAME answer and a code whether or not the number has an account (nobody can probe which
  numbers are registered). 400 `PHONE_INVALID`; 429 `OTP_WAIT` (cooldown, per number per day, per address).
- `POST /api/auth/phone/verify {challenge, code, previousRefreshToken?}` → **200** either
  `{status: "SIGNED_IN", session: {accessToken, tokenType, expiresInSeconds, user, refreshToken}}` (the number's
  account; Phase 11 C1 hand-over of this phone's old sign-in and its running SOS kept) or
  `{status: "SIGN_UP_NEEDED", signUp: {ticket, phoneHint, expiresInSeconds}}` (no account yet; the ticket is 256 bits,
  SHA-256 stored, one use, 30 minutes, tied to the number). 400 `OTP_INVALID` (tries left in the message),
  `OTP_EXPIRED`, `CHALLENGE_INVALID`; 429 `OTP_LOCKED` (5 wrong codes); 403 `ACCOUNT_UNAVAILABLE` (the number's account
  is switched off, or has no verified number: N-22).
- `POST /api/auth/sign-up {ticket, name, dateOfBirth: "yyyy-MM-dd"}` → **201** the session answer (`accessToken`,
  `refreshToken`, `user`); the number is verified from the first moment. `name` = the real name (2–60 characters;
  letters of any script with their marks, spaces, `. ' -`; no digits or emoji; spaces collapsed); `dateOfBirth` is
  checked for 18+ and never stored (D6). 400 name or age refusals (`You must be 18 or older to use this app`), 400
  `TICKET_INVALID` (used, ended or unknown ticket: start again), 409 `PHONE_IN_USE` (the number got an account
  meanwhile), 429 too many new accounts from one address.
- `POST /api/auth/phone/resend {challenge}`, `POST /api/auth/phone/change {challenge, phone}` (a typo): as in 17B.
- `refresh`, `sign-out`: unchanged (Phase 11 C1). A session needs a verified number: a refresh of an account without
  one answers 401 `account_unavailable`.

**Sensitive actions confirmed with a fresh code (N-7)** — replaces the password of Phase 10A
- `POST /api/users/me/confirmation` (no body) → **200** the same challenge shape as `phone/start`; the code is texted
  to the account's OWN number (purpose CONFIRM, one use). 429 `OTP_WAIT`.
- The confirmed actions send `{challenge, code}`. A wrong or used code, or a challenge of another account or of a
  sign-in, is **409** "That code is not right, or it has ended. Ask for a new code." (never 401: a 401 means "signed
  out" on the phone); 5 wrong codes in 15 minutes per account, all actions together, = **429**. Every refusal of the
  action itself is checked BEFORE the code, so a refused action does not use the code up.
  - `POST /api/users/me/deletion {challenge, code}` → 202 `{deletionRequestedAt, deletionDueAt}` (rules of 10A
    unchanged: 409 during an SOS and 24 h after one; 7-day grace).
  - `POST /api/users/me/deletion/cancel {challenge, code}` → 204.
  - `POST /api/users/me/sessions/sign-out-others {challenge, code}` (N-26) → **200** the session answer with a NEW
    access token for this phone (same device session); the credentials stamp moves and every other sign-in of the
    account ends (their next call: 401 `credentials_changed`; their sessions end with reason `OTHERS_SIGNED_OUT`).
    409 while an SOS or a Safe Walk runs.
  - `PUT /api/emergencies/{id}/resolve|cancel {challenge?, code?, endedAt?}`: the phone that started the SOS sends no
    code; another sign-in gets **403** without one and 409 with a wrong one (Phase 10A.4 rules otherwise unchanged).
- Honest limit: the code goes to the same SIM as sign-in. It protects against a stolen session or a phone left
  signed in, not against someone who holds the SIM.

**Name and account**
- `PUT /api/users/me/name {name}` (N-24) → **200** the `user` object. Same rule as sign-up; 400 otherwise. Locked after
  the ID check of N3: 409 `NAME_LOCKED` (see "Phase N3").
- `GET /api/users/me` and every `user` object: `{id, name, phoneHint, createdAt, deletionRequestedAt, deletionDueAt,
  ageConfirmed}` (no `email`).
- `PUT /api/admin/roles {phone, role}` (ADMIN): the account is found by its verified number (any common way of typing
  it); 404 for a number without a verified account.
- Config: `app.otp.ticket-lifetime` (30m) replaces `app.otp.unverified-retention`; `app.account.code-failures` (5) /
  `code-failure-window` (15m) replace `password-failures` / `password-failure-window`; `app.auth.login-*` removed.

# Phase N3: the verified MIET community (`docs/PHASE_N3.md`, V34)

A person signed in (N2) is NOT yet in the community. The community = `MIET` (seeded by V34; no endpoint creates one).
Roll numbers arrive only in requests (typed by the applicant, or by a verifier / admin from the ID card); no answer
carries one; only an HMAC is stored (`COMMUNITY_SECRET`). Codes are 8 characters of `23456789ABCDEFGHJKMNPQRSTUVWXYZ`,
shown and accepted as `ABCD-2345` (spaces and `-` ignored, lower case accepted).

**The gate**
- Every community read and write (profiles of others, connections, invites, blocks and mutes, places, posts, media,
  perspectives, Discover, reports) answers **403** `{code: "MEMBERSHIP_NEEDED", message: "This is for verified MIET
  members. You can get verified from You."}` without an active membership. Account-level, NOT gated: the caller's own
  profile and photo (the draft; a suspension still stops changes), the own moderation standing, appeal and review
  request, and everything of auth, account, Safety Circle, SOS, Safe Walk and circle alerts.
- `PUT /api/users/me/name` → **409** `{code: "NAME_LOCKED"}` once the name was checked against the ID (N-24).

**The applicant** (signed in)
- `GET /api/community/me` → `{state: NONE|PENDING|MEMBER|ENDED|BANNED, community: {code, name}, membership:
  {programme, programmeLabel, graduationYear, method, verifiedAt, endsAt, endedAt, endReason}|null, request: {method,
  programme, programmeLabel, graduationYear, code|null, codeAlive, codeExpiresInSeconds, photoUploaded,
  expiresAt}|null, outcome: {kind: REFUSED|ENDED|BANNED, reason, at}|null, nameCorrection: {from, at}|null, nameLocked,
  staff: {verifier, admin}}`. `endsAt` = the end of 31 July of the graduation year in India (18:30 UTC); access stops
  at that minute. `outcome` = the latest refusal, end or ban with its reason (no shadow decisions).
- `GET /api/community/programmes` → `[{code, label, level: UG|PG, firstYear, lastYear}]` (the owner's 14).
- `POST /api/community/verification {method: IN_PERSON|ID_PHOTO, rollNumber, programme, graduationYear}` → 200 `/me`
  (PENDING); an in-person answer carries `request.code` ONCE (10 minutes; only its HMAC is stored). 400 bad roll
  number / programme / year; 409 already a member or a request already open; 429 more than 3 requests a day. A banned
  number or roll number: refused (the state answers BANNED). The server never tells whether a roll number is taken.
- `POST /api/community/verification/code` → 200 `/me` with a NEW code (the old one stops). 404 no in-person request.
- `PUT /api/community/verification/photo` (body `image/jpeg` or `image/png`, ≤ 5 MB) → 200 `/me`; re-encoded
  (no metadata), encrypted with AES-256-GCM, stored under a random name in `app.community.photo-dir`; replaces an
  earlier photo. 404 no ID-photo request; 400 not an image / too big.
- `DELETE /api/community/verification` → 204; the request and its photo are deleted.
- A request (and its photo) expires after 7 days; the hourly job removes it.

**Verifiers and admins** (`/api/verifier/**`: ADMIN or an active verifier; `/api/admin/community/**`: ADMIN; roles
read from the database per request; 403 otherwise)
- `POST /api/verifier/lookup {code}` → 200 `{ref, method, name, phoneHint, programme, programmeLabel,
  graduationYear, createdAt, photoUploadedAt}` and the request is claimed for the caller for 15 minutes. 404 wrong or
  expired code; 429 after 10 wrong codes in 15 minutes; 403 the caller's own request ("Nobody can verify themselves").
- `POST /api/verifier/requests/{ref}/decision {approve, rollNumber?, name?, reason?}` (only the claimer, in person)
  → 200 `{outcome: VERIFIED|REFUSED, reason}`. Approve needs the roll number typed from the card: a mismatch is **409**
  (counted; retype or refuse); `name` = the spelling on the ID (logged as NAME_CORRECTED, shown to the person). Refuse
  needs `reason` ∈ `NAME_MISMATCH, ROLL_MISMATCH, NOT_THE_PERSON, NOT_MIET_ID, UNREADABLE, OTHER`. Automatic refusals:
  `ALREADY_MEMBER` (the roll number belongs to another account), `BANNED`. 429 after 50 approvals in 24 h per
  verifier (admins exempt).
- `GET /api/admin/community/photo-requests` → the ID-photo queue, oldest first (same shape as lookup).
  `GET /api/admin/community/requests/{ref}/photo` → the decrypted image (`Cache-Control: no-store`, `nosniff`).
  `POST /api/admin/community/requests/{ref}/decision` → as above (409 when no photo yet). The file is deleted after the
  decision commits.
- `GET /api/admin/community/verifiers` → `[{name, phoneHint, programme, graduationYear, since}]`;
  `PUT /api/admin/community/verifiers {phone, verifier: true|false}` → 204 (409 not an active member; 404 no account).
- `PUT /api/admin/community/members/name {phone, name}` → 204 (a checked name corrected; logged and shown).
- `GET /api/admin/community/bans` → `[{id, reason, note, onRollNumber, onPhone, person, bannedBy, createdAt,
  liftedAt}]` (never a number); `POST /api/admin/community/bans {phone?, rollNumber?, reason, note?}` → 200 the ban
  (reason ∈ `HARASSMENT, HATE, THREATS, SEXUAL_CONTENT, IMPERSONATION, FALSE_ID, PRIVACY, REPEATED, OTHER`, note ≤ 200;
  ends every membership it hits as BANNED; never touches the account, sign-in, sessions or safety);
  `POST /api/admin/community/bans/{id}/lift` → 200 (the membership stays ended until a new verification).
- `POST /api/admin/community/members/end {phone}` → 204 (REMOVED).
- N-25 re-link: `POST /api/community/relink {ticket, rollNumber}` (PUBLIC: the new number's sign-up ticket from
  `phone/verify` is the credential and is used up) → 200 `{code, expiresInSeconds: 3600}`; 409 the number already has
  an account. `POST /api/admin/community/relinks {code, rollNumber}` (ADMIN, in person with the ID) → 200
  `{outcome: "RELINKED"}`: the member's account moves to the new number, every sign-in ends; 404 wrong / expired code,
  409 roll mismatch / no member with it / banned roll number, 403 the admin's own account, 409 during an SOS or Safe Walk.
- `GET /api/admin/community/log?page=0` → 50 lines a page, newest first: `[{action, method, reason, actor, subject,
  detail, at}]` (`actor` "IRL" for the upkeep job; `detail` = the earlier spelling of a corrected name).
- Config `app.community.*`: `secret` (env `COMMUNITY_SECRET`, ≥ 32 characters; required by the `prod` profile; in
  development an empty one falls back to a key derived from `JWT_SECRET` with a WARN), `photo-dir`
  (`COMMUNITY_PHOTO_DIR`, `id-photo-store`), `code-lifetime` 10m, `claim-lifetime` 15m, `request-lifetime` 7d,
  `relink-lifetime` 1h, `requests-per-day` 3, `approvals-per-day` 50, `wrong-codes` 10 / `wrong-code-window` 15m,
  `job-enabled`, `job-interval` PT1H (first run 3 minutes after start).

# Phase N4: moments and Today (`docs/PHASE_N4.md`, V35) — backend units B1 to B3

**Status: built and tested (backend, N4-B1 to B3). V35 is applied by N4-B4 on the development MySQL only.**

**Removed in N4-B3 (the sections "Phase 13B", "13C", "17C", "17E" above and the post / place parts of "13D" / "13E" are
history):** `/api/posts/**`, `/api/places/**`, `/api/discover/**`, `/api/media/**` (post photos and video),
perspectives (`/api/posts/{id}/comments`, `/api/comments/**`), `/api/moderation/places/**`, the moderator's case video
route, the profile fields `city`, `languages`, `interests`. Reports and cases know two targets: `MOMENT` and `PERSON`.

Every route needs an active MIET membership (403 `MEMBERSHIP_NEEDED`). A moment the caller may not see answers **404**
(as if it did not exist). No answer has a count, a location, a viewer list or a ranking. Times: only the author gets
`createdAt` / `visibleAt` / `expiresAt`; everyone else gets `when` (rough, India time).

**The moment object**
```
{ id, kind: SAY|WHOS_IN, audience: COMMUNITY|MY_PEOPLE,
  author: { profileId|null, handle|null, name, photo|null },      // name = the account's real name; no profile needed
  mine: bool, text|null,                                          // text is null only next to a photo
  photo: null | { width, height },                                // the picture: GET /api/moments/{id}/media
  plan: null | { at: instant|null, where: string|null },          // WHOS_IN only; `at` is exact, for everyone
  question: null | { id, text },                                  // the daily question it answers
  when: null | { day: TODAY|YESTERDAY|THIS_WEEK|THIS_MONTH|EARLIER, part: MORNING|AFTERNOON|EVENING|NIGHT|null },
  status: LIVE|ENDED|WAITING,                                     // WAITING: only its author ever gets one
  kept: bool,
  collapsed: bool,                                                // others: show it behind a tap
  notice: null | { state: COLLAPSED|HELD|REMOVED, reason|null, by: SYSTEM|MODERATOR|null,
                   reviewable: bool, decisionId|null },           // the AUTHOR only (no shadow states)
  supportSuggested: true|null,                                    // only in the author's own answer to POST
  createdAt, visibleAt, expiresAt }                               // instants for the author; null for everyone else
```
`when.part` is set for TODAY and YESTERDAY only (morning 05:00-11:59, afternoon 12:00-16:59, evening 17:00-20:59, night
21:00-04:59, India time), counted from when others could first see the moment. `when` is null while it is WAITING.

`notice` (author only): `reason` is a guideline code (`HARASSMENT`, `SEXUAL_HARASSMENT`, `HATE`, `THREAT`, `DOXXING`,
`SPAM`, `NUDITY_OR_VIOLENCE`, `IMPERSONATION`, `OTHER`) or `NEEDS_REVIEW` (a person is looking; also what "This is
about me" reads as: the author never learns that someone asked, or who), `AWAITING_CHECK` (a new member's words wait
for the automatic check, at most 2 hours) or `NEW_MEMBER_PHOTO` (a new member's photo for all of MIET waits for a
moderator). A collapse by reports alone is `{state: COLLAPSED, reason: null, by: null}`. `reviewable` = the author may
call `POST /api/content-checks/review`; `decisionId` = a moderator's decision, appealable through
`GET /api/social-standing`.

**Members**
- `POST /api/moments/media` (body = the image, `image/jpeg` or `image/png`, ≤ 5 MB) → **201** `{mediaId, width, height}`.
  Re-encoded without metadata before it is stored. 415 another type, 400 not a readable image / too large, 403 paused,
  429 over 20 a day. `DELETE /api/moments/media/{mediaId}` → **204** (own, not attached; else 404). An upload not
  attached within 24 h is deleted.
- `POST /api/moments {kind, audience, text?, mediaId?, planAt?, planWhere?, questionId?}` → **201** the moment.
  `kind` and `audience` are required (no default audience). `text`: 1 to 500 characters after cleaning; optional next
  to a photo. `mediaId` = the caller's own unattached upload (400 "That photo is no longer available. Please take it
  again."). `planAt` (ISO instant, kept to the minute, after now and at most 24 h ahead) and `planWhere` (one line,
  ≤ 60 characters) only on `WHOS_IN`. `questionId` must be the question pinned on Today right now. **400** with our
  message for each rule ("Choose who can see this", "A moment has at most 500 characters", "Choose a time within the
  next 24 hours", "Only a Who's in? has a time and a place", "That question is no longer today's question", and for a
  new member with a link: "New members can share links after their first week. Please remove the link."). **403** while
  a moderator's pause stops posting. **429** over the daily limit (3 in the first 7 days after approval, then 10).
  The answer already shows what happened to it: `status: WAITING` + `notice` when it is held (a new member's photo for
  `COMMUNITY`; words the automatic check holds; a new member's words while the check is unavailable), `notice
  {COLLAPSED}` when the check collapsed it, `supportSuggested: true` once when the words suggest the author may need
  support (never stored).
- `GET /api/moments/{id}/media` → the photo (`image/jpeg`, `Cache-Control: private, no-store`, `nosniff`) for exactly the
  people who may see the moment; 404 otherwise or when it has none.
- `GET /api/moments/today?people=ALL|MINE&cursor=` → `{question: {id, text}|null, moments: [moment], nextCursor|null}`.
  Live moments the caller may see, newest first (by first visibility), 20 per page; `nextCursor` null = the wall ends.
  `ALL` (default): the community's moments, the caller's connections' "my people" moments and the caller's own live
  ones. `MINE`: moments by the caller's connections only. Muted people's moments are left out here (only here).
  `question` = today's daily question (India day), on every page. 400 unknown `people` or cursor; 429 per-hour limit.
- `GET /api/moments/mine` → `{current: [moment], kept: [moment], keptMax: 6}`: the caller's moments that have not ended
  (WAITING or LIVE, whatever their moderation state), newest first, and the ones they keep.
- `GET /api/moments/{id}` → the moment; 404 not visible (ended and not kept, wrong audience, blocked, held, …).
- `GET /api/profiles/{publicId}/moments` → `{live: [moment], kept: [moment]}`: that person's moments the caller may
  see (the one visibility rule); 404 when the profile is not visible.
- `DELETE /api/moments/{id}` → **204** (own; always allowed, also under review). There is no edit route (N-54).
- `PUT /api/moments/{id}/kept` → **200** the moment with `kept: true` (own; idempotent). **409** "Only a moment that is
  live on Today can be kept" / "You can keep 6 moments. Un-keep one first."
- `DELETE /api/moments/{id}/kept` → **204** (own; any time).

**Profiles (N-51)**
- `PUT /api/profiles/me {handle, bio?}` (no city, languages or interests any more).
- `GET /api/profiles/me` → `{publicId, handle, displayName, bio, programme|null, graduationYear|null,
  newMemberUntil|null, handleChangeAvailableAt|null, photo|null}`. `programme` (its code) and `graduationYear` come
  from the verified membership; `newMemberUntil` = the end of the 7-day new-member period, null once established.
- `GET /api/profiles/{publicId}` and `/by-handle/{handle}` → `{publicId, handle, displayName, bio, programme,
  graduationYear, relationship, photo}`.

**Reports and "This is about me"**
- `POST /api/reports {targetKind: MOMENT|PERSON, targetId, reason, note?}` → **202** `{status: RECEIVED}` (the same
  answer when repeated). Only something the caller can see, never their own (400). `reason`: the nine guideline codes
  (`IMPERSONATION` only on a person; `OTHER` needs a note of 1 to 200 characters). One report changes nothing its
  subject can observe; three independent counting reports collapse a moment while a moderator looks. A new member's
  report reaches moderators at once and counts toward that collapse from the end of their 7 days.
- `POST /api/moments/{id}/about-me` (no body) → **202** `{status: RECEIVED}`. The moment is hidden from everyone but
  its author at once (unless a moderator already decided) and a priority case opens; a human decides. Uses the daily
  report limit; 404 when the caller cannot see the moment; 400 on one's own. N4-C (N-85): when the caller already has an
  OPEN report about that moment, that report becomes the about-me request (reason `ABOUT_ME`, its note dropped) and
  the moment is hidden the same way; a report a moderator already decided stays as it is (same 202, nothing changes).
- `GET /api/reports/mine`, `GET /api/social-standing`, `POST /api/social-standing/decisions/{decisionId}/appeal`:
  unchanged shapes; `targetKind` is `MOMENT` or `PERSON`.
- `POST /api/content-checks/review {targetKind: "MOMENT", targetId}` → **202**: the author asks a moderator to look at
  their own moment that a rule collapsed or held (404 otherwise).

**Moderators** (`/api/moderation/**`, MODERATOR or ADMIN)
- `GET /api/moderation/content-cases?status=OPEN|CLOSED` → `[CaseSummary]`, highest tier first, then oldest:
  `{caseId, targetKind, status, outcome, openedOn, topReason, reasons, counting, independent, contentState, priority,
  youTookPart, openedBy: REPORTS|SYSTEM|AUTHOR_REQUEST|ABOUT_ME, contentSource, waitingHours}`. Tiers (N-69): 1 =
  "This is about me" and content a rule held for a severe finding; 2 = reported content; 3 = new-member photo holds and
  authors' review requests; more than 12 hours waiting moves a case up one tier.
- `GET /api/moderation/content-cases/{id}` → `{summary, content: {kind, audience, body, planAt, planWhere, question,
  photo: {width, height}|null, person|null}, participants, author: {ref, strikePoints, suggestedStep, restriction,
  restrictedUntil, newMemberUntil}, possibleActions, decisions, assessment}`.
- `GET /api/moderation/content-cases/{id}/media` → the case's moment photo (`private, no-store`); 403 for a moderator
  who took part (the author, a reporter, the person who said "about me").
- `POST /api/moderation/content-cases/{id}/actions {action, reason}`: `CONTENT_KEEP` (releases; a never-visible
  moment's 24 hours start then), `CONTENT_HOLD`, `CONTENT_COLLAPSE`, `CONTENT_REMOVE`, the `PERSON_*` steps. Appeals:
  `GET /api/moderation/content-appeals`, `POST /api/moderation/content-appeals/{id}/decision` (never the decider).

**Admins** (`/api/admin/**` = ADMIN from the database; the admin must also be an active member: 403 `MEMBERSHIP_NEEDED`)
- `GET /api/admin/questions` → `[{id, day, text, createdAt}]` from 7 days ago onwards, oldest first.
- `POST /api/admin/questions {day: "YYYY-MM-DD", text}` → **201**. `day` = an India calendar day, today to 60 days
  ahead; `text` 3 to 140 characters, one line. **409** "That day already has a question. Delete it first to write
  another." 400 otherwise.
- `DELETE /api/admin/questions/{id}` → **204**; its answers stay as plain moments. 404 unknown.

**Config:** `app.moments.per-day` (10), `new-member-per-day` (3), `today-reads-per-hour` (600), `questions-ahead` (60),
`photos-per-day` (20). The product rules (24 h, purge after 7 days, 6 kept, the 7-day new-member period, 500 / 60
characters) are constants. `app.social.*` keeps profiles, connections and the photo folder only.

# Phase N5: responses, plans, private replies, Replies (`docs/PHASE_N5.md`, V36)

**Status: built and tested (backend B1 to B4); V36 applied on the development MySQL only (2026-11-17).** Every route
needs an active MIET membership (403 `MEMBERSHIP_NEEDED`). Taps, asks, follow-ups, new replies and messages are
stopped by a responses pause (`COMMENTS_PAUSED` or stronger): **403** with our message; leaving, ending, calling off,
reading and reporting never are (N-95). A moment the caller cannot see, or a conversation they are not part of, is
**404** (exactly like one that does not exist). No answer carries a count, a "seen", a read state or a location.

**Moment answers** gain `responses` and `happenedOf`:
- `responses.same {mine, band}`: `band` = `SOMEONE` (1) | `A_FEW` (2–5) | `MANY` (6+) of the OTHER people, null when
  none (D14); `null` for the author. `responses.sayMore {mine}`. `responses.more {text, when{day, part}, at}` (the
  follow-up; `at` only for the author). `responses.plan {you: AUTHOR|NONE|ASKED|IN|REMOVED, open, calledOff, planId}`
  (Who's in? only; `planId` for the author and the people in, once the plan opened). `responses.canReply`,
  `responses.replyId` (the viewer's own private reply). `responses.author {same, otherSame, sayMore, otherSayMore,
  asking, otherAsking, in, otherIn}`: up to 3 names each + "and others", the author only.
- `happenedOf {id, text}`: the plan a This happened Say came from (≤ 120 characters), null when gone or not visible.

**Responses** (only while the moment is on Today, never on one's own: 400; ended: 409)
- `PUT|DELETE /api/moments/{id}/same`, `PUT|DELETE /api/moments/{id}/say-more` → the moment (200). Same / Say more on a
  Say only (400). Taking back an answered Say more changes nothing.
- `POST /api/moments/{id}/more {text}` → **201** the moment. The author only (404 otherwise), once (409), after at least
  one Say more (409), 1–500 characters, no links for a new member (400).
- `PUT /api/moments/{id}/in` → the moment; **409** when asks are closed (plan time, Today over, called off) or the
  author removed the caller. `DELETE /api/moments/{id}/in` → **204** (leaving; "… can't make it" in the plan).
- `GET /api/moments/{id}/people` → `{same, sayMore, asking, setAside, in}` lists of `{ref, name, profileId, photo,
  programme, year, myPeople}` for the author; `{in}` (with the author) for someone in; 404 for anyone else.
- `POST /api/moments/{id}/people/{ref}/add`, `POST|DELETE /api/moments/{id}/people/{ref}/set-aside`,
  `DELETE /api/moments/{id}/people/{ref}` (remove someone added) → the people lists. The author only. The first Add
  opens the plan. No decline exists.
- `POST /api/moments/{id}/call-off` → the moment; closes the plan at once.

**Private replies and plans**
- `POST /api/moments/{id}/private-replies {text}` → **201** `ConversationView`. **403** when the author's setting does
  not allow the caller; **409** a second one on the same moment.
- `GET /api/conversations/{id}` → `{id, kind: PLAN|REPLY, moment {id, kind, text, plan, calledOff, mine, authorName},
  people, messages [{id, kind: TEXT|SYSTEM, text, event: IN|LEFT|CALLED_OFF|HAPPENED|ENDED, by, mine, at, collapsed,
  notice, supportSuggested}], you {turn: YOUR_TURN|WAITING|OPEN|CLOSED|ENDED, canSend, paused}, closesAt, open}`.
  Exact times for members only. Someone else's held or removed message is left out; a collapsed one is behind a tap;
  `notice` is the writer's own only.
- `POST /api/conversations/{id}/messages {text}` → **201** `MessageView`. **409** "Wait for an answer before you write
  again" (a private reply takes turns) or "This conversation has closed".
- `POST /api/conversations/{id}/end` → **204** (a private reply; either person; read-only for both).
- `GET|PUT /api/social-settings {privateReplies: MY_PEOPLE|COMMUNITY|NOBODY}` (no row = MY_PEOPLE).

**Replies** (D19)
- `GET /api/replies` → `{needsYou, plans, conversations, same, fresh}`; each line `{type, moment {id, kind, text, plan,
  calledOff, mine, authorName, live}, conversationId, people (≤ 3), morePeople, turn, open, at, fresh}`. Types:
  needsYou `ASKS | SAY_MORE | YOUR_TURN | MORE`; plans `PLAN | WAITING | NOT_IN | CLOSED | CALLED_OFF`; conversations
  `REPLY`; same `SAME` (never fresh). `at` only on PLAN / REPLY lines.
- `GET /api/replies/marker` → `{fresh}`; `PUT /api/replies/opened` → **204** (the person's own time, never per message).

**This happened** (N-92): `POST /api/moments` takes `happenedOf` = a Who's in? id. A Say with a photo (400 otherwise),
by its author or someone in it (404 otherwise), after the plan time while the plan is open (409), once per plan (409),
with the plan's audience (`audience` may be left out; another one is 400); a My people plan only by its author (409).

**Moderation of messages:** `POST /api/reports {targetKind: "MESSAGE", targetId, reason, note?}` (a message the caller
can see; 400 on one's own); `POST /api/content-checks/review {targetKind: "MESSAGE"}`; case `targetKind MESSAGE`,
`content.kind MESSAGE`, `content.conversation {kind, messages [{ref, text, reported}], connected}` (N-97);
`content.more` = a moment's follow-up; standing decisions about a message carry the conversation id as `targetId`.

**Config:** `app.responses.signals-per-day` (200), `asks-per-day` (20), `new-member-asks-per-day` (10),
`replies-per-day` (10 new private replies), `new-member-replies-per-day` (3), `messages-per-day` (300),
`messages-per-minute` (10), `replies-reads-per-hour` (600). In memory.

# Phase N6: safety trim and automatic SOS alerts (`docs/PHASE_N6.md`, V37)

**Status: B1 and B2 built and tested (2026-11-18); V37 written, held with `spring.flyway.target=36` until B3.** The walk
is "On my way" (D22): a reminder, no tracking. The server texts the Safety Circle (D20).

**Walk (`/api/journeys`) after N6-B1:**
- `POST /api/journeys {"destinationLabel"? (≤ 100, the person's own words), "checkInIntervalMinutes": 2..60}` → `201`.
  `destinationLatitude` / `destinationLongitude` are no longer read (an old client that sends them is not refused;
  nothing is stored).
- Every walk answer is exactly `id, status, destinationLabel, checkInIntervalMinutes, escalationLevel, startedAt,
  endedAt, nextCheckInDueAt, serverTime`: no `destination`, no `lastLocation`. `escalationLevel` is 0 while the walk
  runs and 3 when an SOS ended it.
- Kept unchanged: `GET /api/journeys`, `GET /api/journeys/{id}`, `POST …/check-ins` ("More time"; a walk an older phone
  left on level 1 / 2 goes back to 0 and that is recorded), `PUT …/complete` ("I'm there"), `PUT …/cancel`, the SOS
  link (`POST /api/emergencies` ends the caller's ACTIVE walk as `EMERGENCY`, level 3).
- **Gone (404):** `PUT /api/journeys/{id}/location`, `POST /api/journeys/{id}/escalations`,
  `POST /api/journeys/{id}/circle-alerts`. Walk alert kinds `WALK_NO_RESPONSE` / `ALL_CLEAR` and escalation reasons
  `ROUTE_DEVIATION` / `UNEXPECTED_STOP` exist only in old rows (still shown in the owner's Emergency Context Packet).
- `POST /api/emergencies/{id}/circle-alerts` (the phone's own report) is unchanged in B1.

**Automatic Safety Circle texts (N6-B2, D20, `PHASE_N6.md` §3).** JWT required; the caller's own data only; not gated by
membership (safety never is). No answer carries a contact's number, a text or a location.

| Method and URL | Purpose | Success | Errors |
|---|---|---|---|
| `GET /api/safety-alerts` | The choice and what it means | `200` Settings | `401` |
| `PUT /api/safety-alerts {"on": true\|false}` | The one explicit choice (and later changes) | `200` Settings | `400`, `401` |
| `POST /api/safety-alerts/test` | "Test my alerts": the server texts the caller's OWN number (works with the choice on or off) | `202` Settings | `401`, `409` no number, `429` once per 24 h (`app.alerts.tests-per-person`) |
| `GET /api/emergencies/{id}/alerts` | What the server did for each contact in this SOS | `200` | `401`, `404` (another person's SOS too) |
| `POST /api/admin/alert-stops {"phoneNumber"}` | ADMIN: stop all IRL alerts to a number (kept only as an HMAC) | `204` | `400` not an Indian mobile, `401`, `403` |
| `POST /api/admin/alert-stops/lift {"phoneNumber"}` | ADMIN: lift it | `204` | `400`, `401`, `403` |
| `POST /api/admin/alert-stops/check {"phoneNumber"}` | ADMIN: `{"stopped": bool}` | `200` | `400`, `401`, `403` |

```json
{ "choice": "UNSET|ON|OFF", "decidedAt": "…|null",
  "preview": "IRL SOS: Asha Devi pressed SOS at 9:41 PM. Location: maps.google.com/?q=(where you are) Call 9876543210 now. No answer? Call 112.",
  "contacts": [ { "contactId": 3, "name": "Ravi", "automatic": true, "reason": null },
                { "contactId": 4, "name": "Uncle", "automatic": false, "reason": "NOT_INDIAN_MOBILE" } ],
  "lastTest": { "status": "SENT", "at": "…", "reason": null }, "testAvailable": false }
```
```json
{ "choice": "ON",
  "alerts": [ { "contactId": 3, "contactName": "Ravi", "kind": "SOS_STARTED", "status": "SENT", "reason": null,
                "at": "…", "late": false } ] }
```
- `choice` UNSET = never chosen: nothing is sent automatically. `kind` SOS_STARTED / SOS_LOCATION / SOS_RESOLVED /
  SOS_CANCELLED. `status` QUEUED / SENT (accepted by the provider, **not** delivered: no delivery reports in N6) /
  DELIVERED (reserved) / FAILED / SKIPPED; the phone reads anything else as "not confirmed". `reason` (SKIPPED only):
  NOT_INDIAN_MOBILE, NOT_IN_FIRST_FIVE, DUPLICATE_NUMBER, NOT_AVAILABLE (the stop list, never named), NUMBER_LIMIT,
  PERSON_LIMIT, SERVER_LIMIT, TOO_LATE, ENDED, CONTACT_GONE. `late` = texted as "sent late".
- Rules: the first text goes at the first location or 20 s after the SOS reached the server; a False alarm before that
  sends nothing; one follow-up with the location within 10 min; one closing text after I'm safe / False alarm, never
  for an SOS ended from another sign-in; nothing for an SOS that reached the server more than 30 min after it started
  (2–30 min: "sent late"); at most 5 contacts by priority, Indian mobiles only, each number once; 3 attempts, then
  FAILED. Limits counted in the database (24 h windows): 5 rounds per person, 10 texts per number across all senders,
  100 per server.
- `POST /api/emergencies/{id}/circle-alerts` (the phone's own reports) now refuses `channel` SERVER_SMS, `status` QUEUED /
  SKIPPED and `kind` TEST (`400`), and a reference that belongs to a server row (`409`).
- **Config** `app.alerts.*`: provider (`ALERT_PROVIDER` DEV_FILE | FAST2SMS, the shared Fast2SMS client and key), secret
  (`ALERT_SECRET`), dev-file (`ALERT_DEV_FILE`), job-enabled, dispatch-interval 5s, send-delay 20s, stale-after 30m,
  late-after 2m, follow-up-window 10m, closing-window 24h, max-contacts 5, rounds-per-person 5, per-number 10,
  server-wide 100, tests-per-person 1, max-attempts 3, retry-delay 15s. The prod profile requires FAST2SMS and its own
  ALERT_SECRET.

# Phase N7: push notifications and the Safety Circle in the app (`docs/PHASE_N7.md`, V38)

**Push (B1, D26-D35).** Every route needs a sign-in; none needs a community membership.
- `PUT /api/push/device {token, appVersion?}` → **204**. Binds this phone's FCM token to the CURRENT sign-in (the token's
  `sid`); a token seen on another sign-in moves here (the old row goes). **400** not a token (`^[A-Za-z0-9_:-]{32,512}$`),
  **409** "Sign in again to get notifications on this phone" (no `sid`, or the sign-in ended), **429** more than 10 an
  hour from one sign-in. The token is never returned or logged.
- `DELETE /api/push/device` → **204** (this sign-in's token; nothing to remove is fine).
- `GET|PUT /api/push/settings {replies, plans, planMessages, people, staff}` → the caller's own switches (no row = all on
  except `staff`). Safety and account notices have no switch here (D31).
- What a phone receives (FCM data-only, nothing else): `{"t": <kind>, "s": <opaque id>, "m": <event id>, "v": "1"}`;
  kinds `PLAN_ASK | PLAN_ADDED | PLAN_MESSAGE | PLAN_CALLED_OFF | REPLY_TURN | MORE_ADDED | PEOPLE_REQUEST |
  ACCOUNT_SIGN_IN | MEMBERSHIP_DECIDED | CONTENT_DECIDED | CIRCLE_REQUEST | CIRCLE_SOS | OMW_REMINDER | STAFF_WAITING`.
  Android priority HIGH only for `CIRCLE_SOS` and `OMW_REMINDER`; collapse key = thread + subject. The phone reads the
  details through the normal routes.
- Config `app.push.*`: `PUSH_PROVIDER` = `DEV_FILE` (default) / `FCM` / `NONE`, `FCM_CREDENTIALS_FILE`, `PUSH_DEV_FILE`,
  caps 6 an hour / 20 a day for social kinds, batch window 2 min, 5 attempts, stale tokens 60 days, retention 7 days.

**What makes a push (B2, D29/D30/D38).** No new route: the existing writes queue pushes after they commit. A plan
(I'm in → the author, batched 2 min; Add → the person added; a plan message → the author and the people in, once until
that person opens Replies; Call it off → the people in), a private reply (the other person), the follow-up to Say more
(the people who asked), a connection request, a new sign-in (your other phones), a membership decision, a moderator's
decision on your own moment or message (a Keep only if you had already seen a notice), and the staff summary (opt-in,
at most hourly). Never pushed: Same, Say more requests, accepted connections.

**The Safety Circle in the app (B3, D36).** JWT required; never a community membership; a social restriction never
applies. A foreign id is **404**.
- Contacts: `GET /api/safety-contacts[/{id}]` and the create / update answers carry `inApp` (true only after the person
  with that verified number accepted). Saving a contact whose number is a verified IRL account sends THAT person a
  request; the owner's answer is the same either way (nothing reveals that a number has IRL). At most 10 requests a day
  per owner (silently), never to yourself, never after "Don't ask again". A changed number, a removed contact, the
  member leaving or moving to another number ends the link.
- `GET /api/circle/requests` → `[{id, ownerName}]` (requests to me); `POST /api/circle/requests/{id}/accept` → **204**;
  `POST /api/circle/requests/{id}/decline {dontAskAgain?}` → **204** (the owner is told nothing either way).
- `GET /api/circle/in` → `[{id, ownerName}]` (the circles I accepted); `DELETE /api/circle/in/{id}` → **204** (leave).
- `GET /api/circle/alerts/{emergencyId}` → `{name, phone, startedAt, status: ACTIVE | SAFE | FALSE_ALARM | ENDED,
  mapLink?, locationAt?, sentLate}`: only for an accepted member whose contact's first SOS text was due (the moment the
  `CIRCLE_SOS` push went), while the SOS runs and for 24 h after; **404** for anyone else, the owner included. The
  location is the one the texts carried (never newer, no history). `ENDED` = ended from another sign-in.
- Pushes: `CIRCLE_REQUEST` (subject = the request id), `CIRCLE_SOS` (subject = the emergency id) with the first text
  and one more when the SOS ends (not when it was ended elsewhere).

**On my way (B3, D37).** No new route. Starting a walk and every "More time" queue two `OMW_REMINDER` pushes to the
walker's own phones (subject = the walk id) at the due time and 5 minutes later; I'm there, Stop and an SOS remove them.
Nobody else is told.

# Appendix: endpoint index by phase (moved verbatim from CLAUDE.md §8 on 2026-11-03)

> **N1 (2026-11-15):** the lines of Phases 5 to 9, 17G and 18A in this index are history; what was removed is listed at the top of this file.

A compact list of every phase's endpoints and config keys; the sections above have the details. Keep it in step.

- **Phase N5** (V36, `API.md` "Phase N5"): `PUT|DELETE /api/moments/{id}/same|say-more`, `POST /api/moments/{id}/more`, `PUT|DELETE /api/moments/{id}/in`, `GET /api/moments/{id}/people`, `POST /api/moments/{id}/people/{ref}/add`, `POST|DELETE …/set-aside`, `DELETE /api/moments/{id}/people/{ref}`, `POST /api/moments/{id}/call-off`, `POST /api/moments/{id}/private-replies`, `GET /api/conversations/{id}`, `POST /api/conversations/{id}/messages|end`, `GET|PUT /api/social-settings`, `GET /api/replies`, `GET /api/replies/marker`, `PUT /api/replies/opened`; `POST /api/moments` + `happenedOf`; moment fields `responses`, `happenedOf`; report / review target `MESSAGE`; case `content.conversation`, `content.more`. Config `app.responses.*`.

- **Phase N4-B1 to B3** (V35, `API.md` "Phase N4"): `POST /api/moments/media` 201 `{mediaId, width, height}`, `DELETE /api/moments/media/{id}`, `POST /api/moments {kind, audience, text?, mediaId?, planAt?, planWhere?, questionId?}` 201, `GET /api/moments/{id}/media`, `GET /api/moments/today?people=ALL|MINE&cursor=` → `{question, moments, nextCursor}`, `GET /api/moments/mine` → `{current, kept, keptMax}`, `GET|DELETE /api/moments/{id}`, `PUT|DELETE /api/moments/{id}/kept` (409 at 6 or when not live), `GET /api/profiles/{id}/moments` → `{live, kept}`, `POST /api/moments/{id}/about-me` 202, `POST /api/reports` (targets MOMENT, PERSON), `POST /api/content-checks/review` (MOMENT), `GET /api/moderation/content-cases/{id}/media`, admins `GET|POST /api/admin/questions`, `DELETE /api/admin/questions/{id}`. Moment fields `photo`, `collapsed`, `notice`, `supportSuggested`; profile fields `programme`, `graduationYear`, `newMemberUntil`; case fields `waitingHours`, `openedBy ABOUT_ME`, `author.newMemberUntil`. REMOVED: `/api/posts`, `/api/places`, `/api/discover`, `/api/media`, perspectives, `/api/moderation/places`, the case video route, profile `city` / `languages` / `interests`. Config `app.moments.*`.
- **Phase N2** (V33, `API.md` "Phase N2"): `POST /api/auth/phone/start {phone}` (same answer for every number), `/phone/verify` → `SIGNED_IN` + session | `SIGN_UP_NEEDED` + ticket, `POST /api/auth/sign-up {ticket, name, dateOfBirth}` 201, `POST /api/users/me/confirmation`, `POST /api/users/me/sessions/sign-out-others {challenge, code}`, `PUT /api/users/me/name {name}`; `{challenge, code}` on deletion / cancel / SOS end from another sign-in; `PUT /api/admin/roles {phone, role}`; `user.phoneHint` replaces `email`. Removed: `/api/auth/register`, `/api/auth/login`, `PUT /api/users/me/password`. Config `app.otp.ticket-lifetime`, `app.account.code-failures|code-failure-window`.
- `POST /api/auth/register`, `POST /api/auth/login`, `GET /api/users/me`
- Phase 17F (`API.md` "Phase 17F"): `POST /api/emergencies` accepts `triggerType` `SHAKE` (MANUAL default; VOICE and
  DURESS_PIN still 400). No new endpoint, no migration.
- Phase 17E (V28, `API.md` "Phase 17E"): `PUT|DELETE /api/posts/{id}/reaction` and `/save` (idempotent: 200
  `{marked}` / 204), `GET /api/saved?cursor=`; post answers `youReacted`, `saved`, author-only `butterflies`; removed
  `/butterfly` and `/api/cocoon`; `PUT|DELETE /api/profiles/me/photo`, `GET /api/profiles/{id}/photo/{key}`, `photo` in
  person summaries and profiles; `PUT|DELETE /api/profiles/me/daily-state`, `dailyState` (own + connections only);
  city codes from the catalog; moderators `GET …/content-cases/{id}/person-photo`, action `PERSON_REMOVE_PHOTO`.
- Phase 17C (V27, `API.md` "Phase 17C"): `POST /api/media` `video/mp4` → 201 `{mediaId, kind, width, height,
  durationMs}`; `POST /api/media/{id}/poster`; `GET /api/media/{id}` = photo or poster; `GET /api/media/{id}/video`
  (ranges); `DELETE /api/media/{id}` (own, unattached); posts: ≤ 1 video (400), not ready 409; `media[]` + `kind`,
  `durationMs`; moderators `GET …/content-cases/{id}/media/{mediaId}/video`. Config `app.social.max-video-bytes`,
  `videos-per-day`.
- Phase 17B (`API.md` "Phase 17B"): register needs `phone` → 201 `{status: PHONE_VERIFICATION_REQUIRED, challenge,
  phoneHint, needsPhone, codeSent, resendInSeconds, codeExpiresInSeconds}`; login without a verified phone → 403
  `PHONE_NOT_VERIFIED`; `POST /api/auth/phone/start|verify|resend|change` (verify answers like a sign-in). The V25
  endpoints below are removed.
- V25 (history): register → 201 `{status: VERIFICATION_REQUIRED, email}` (no tokens); login of an unverified
  account → 403 `code EMAIL_NOT_VERIFIED`; `POST /api/auth/verify-email {token}` (200 / 400 `VERIFICATION_LINK_INVALID`
  / 429); `POST /api/auth/verification/resend {email, password}` (202 always / 429); `GET /verify-email(.js|.css)`;
  `ApiError.code` optional. Config `PUBLIC_BASE_URL`, `MAIL_*`, `app.email-verification.*`.
- `GET|POST /api/safety-contacts`, `GET|PUT|DELETE /api/safety-contacts/{id}`
- `POST|GET /api/emergencies`, `GET /api/emergencies/{id}`, `PUT /api/emergencies/{id}/cancel`, `PUT /api/emergencies/{id}/resolve`
- `POST|GET /api/emergencies/{id}/locations`, `GET /api/emergencies/{id}/locations/latest` (4A; config
  `app.emergency.locations.max-per-emergency|upload-grace|clock-tolerance`, defaults 1000 / 15m / 5m)
- `POST|GET /api/journeys`, `GET /api/journeys/{id}`, `POST /api/journeys/{id}/check-ins`, `PUT …/complete|cancel`,
  `POST …/escalations` (1–2 only, no skipping, idempotent; 3 = start SOS; 4/5 rejected), `PUT …/location` (last known) (4B).
  `POST /api/emergencies` links the caller's ACTIVE walk (L3) and returns `journeyId`.
- 4C: `POST /api/journeys` optional `destinationLatitude/Longitude` (both or neither), response `destination`;
  `POST …/escalations` optional `reason` (L1: `CHECK_IN_MISSED` default | `ROUTE_DEVIATION` (needs destination) |
  `UNEXPECTED_STOP`, may precede the deadline; L2: `NO_RESPONSE` only).
- 4D: `POST /api/emergencies/{id}/circle-alerts`, `POST /api/journeys/{id}/circle-alerts` (1–20 reports, idempotent by
  `clientRef`, status only forward, ≤ 200 per target), `POST /api/emergencies/{id}/dial-prompts` (L4 once, ACTIVE only),
  `GET /api/emergencies/{id}/context?audience=OWNER|SAFETY_CIRCLE` (others 400). Journey L4 → 400.
- Phase 5: `GET /api/evidence/vault-key`, `POST|GET /api/emergencies/{id}/evidence`, `GET …/evidence/custody`,
  `GET /api/evidence/{id}`, `PUT /api/evidence/{id}/content?offset=N` (octet-stream ≤ 1 MiB), `POST …/seal`,
  `GET …/content`, `POST …/delete {password}`. Config `app.evidence.*` (`EVIDENCE_STORAGE_DIR`, `EVIDENCE_VAULT_PUBLIC_KEY`).
  Context OWNER gains `evidence` counts; SAFETY_CIRCLE none.
- Phase 5 hardening (V10): `POST /api/evidence/{id}/verify` (server re-hashes its stored copy; damaged → UPLOADING
  again). Delete: refused while ACTIVE and for `app.evidence.delete-protection` (24h) after the end; 5 wrong passwords
  in 15 min → 429 (`delete-max-failures`, `delete-failure-window`); wrong passwords logged `DELETE_REFUSED`.
  `VaultKeyTool generate` (passphrase-encrypted PEM) | `check` | `decrypt`.
- Phase 5 restore (V11, `docs/PHASE5.md` §11): `GET|PUT /api/evidence/recovery`, `POST /api/evidence/recovery/envelope
  {password}`, `GET /api/evidence/recovery/items?page=N`, `PUT /api/evidence/{id}/recovery-key`; declaration accepts
  `recoveryRkVersion` + `recoveryWrappedKey`; `EvidenceResponse.recoverable`. Wrong passwords counted (5/15 min → 429).
- Phase 6 (V12, `docs/PHASE6.md`): `POST /api/community/reports {category, cell(7)}` → 202 (same answer new / merged /
  re-sent), `GET /api/community/area?cell=(6)` (cell + 8 neighbours, ≤ 100, nearest first; precision AREA/BLOCK,
  support/freshness BANDS, `yourContribution`), `POST /api/community/signals/{id}/contributions {kind, near(7)}`,
  `GET /api/community/mine`, `DELETE /api/community/mine/{id}`. Config `app.community.*`: delay 10–30 min, probation
  24 h, 10 reports / 3 per area / 30 answers per 24 h, 120 look-ups + 15 distinct areas per hour, purge 15 min.
- Phase 7 (V13, `docs/PHASE7.md`): area signals add `confidence`, `reasons`, `youFlagged`; mine adds `UNDER_REVIEW`;
  `POST /api/community/signals/{id}/flags {reason, near(7)}` → 202 `{status:RECEIVED}` (10/day);
  `GET /api/community/standing` (own pause only, no score); `POST /api/community/appeals` → 202;
  `GET /api/moderation/access` (any JWT); MODERATOR/ADMIN (role from DB per request): `GET /api/moderation/cases?status=`
  (summaries carry `youTookPart`),
  `GET /api/moderation/cases/{id}`, `POST /api/moderation/cases/{id}/actions {action QUARANTINE|DISMISS|REMOVE|RESTRICT,
  reason, contributorRef?, days?}`, `GET /api/moderation/appeals`, `POST /api/moderation/appeals/{id}/decision
  {decision UPHELD|OVERTURNED}`; ADMIN: `PUT /api/admin/roles {email, role USER|MODERATOR}`, `GET /api/admin/moderation-log?page=`.
  Paused contributor → 403 on report/answer/flag (viewing and safety features unaffected). Sign-in 429 after 10 failures
  per account / 30 per address per 15 min; sign-up 429 after 10 per address per hour; community writes 10 per 10 min.
  Config `app.auth.*`, `app.moderation.*`, `app.community.writes-per-10-minutes` (tests lift the per-address limits).
- Phase 8 (V14, `docs/PHASE8.md`): `GET /api/community/area` adds `picture {modelVersion "rules-1", outlook, concern,
  information, reasons, concernCategories, conflicting, highestConcernCell (6-char or null), areas[9] {cell, outlook,
  information}}` (null if the rules fail; computed before the 100-signal cut); `GET /api/intelligence/region?cell=(5)` →
  `{region, modelVersion, complete, areas[] (only known cells; absent = NO_INFORMATION), signals[] (MODERATE/HIGH only,
  no ids, independent freshness)}`; 429 after 20 downloads / 6 distinct regions per 24 h (2 in probation), in memory.
  Config `app.intelligence.*` (pattern half-life 60d, cap 4, recurring 2.5, forget 0.25, min gap 7d, region budgets,
  `region-max-signals` 500).
- Phase 9C (`docs/PHASE9.md` §8): `GET /api/moderation/cases/{id}` adds `coordination: null | {reasons
  [LINKED_GROUP|SUPPORT_CORE|EXCLUSIVE_PAIR], groupSize TWO|THREE_TO_FIVE|SIX_OR_MORE, groupPlaces null|TWO_TO_FOUR|
  FIVE_TO_NINE|TEN_OR_MORE}` (bands only, recomputed per view). `DELETE /api/community/mine/{id}` is no longer 409 for a
  COORDINATION case without flags. Config `app.moderation.coordination.mode` (SHADOW), `.interval` (1h; tests 0s),
  `.max-cases-per-day` (5).
- Phase 9C.1–9C.2 (`docs/PHASE9.md` §9–§10): case detail adds `groupMembers [{ref, role null, flag null, tier,
  accountAge, counting, linkGroup}]`; `POST …/actions` RESTRICT accepts `"wholeGroup": true` instead of `contributorRef`
  (400 with both or on another action; 409 no group / everyone paused); a moderator in the group's core gets 403. DISMISS
  of a COORDINATION case = Option B (that signal counts the people seen then separately).
- Phase 9D (`docs/PHASE9.md` §18–§20): **no new endpoint or field.** `picture.modelVersion` / region pack `modelVersion`
  = `"rules-1.1"` (was `"rules-1"`; `"rules-2"` only if `app.intelligence.rules-2-mode=ACTIVE`); the outlook
  `NO_CONFIRMED_CONCERNS` is no longer sent.
- Phase 9B (V15, `docs/PHASE9.md` §27–§34): ADMIN only `GET /api/admin/evaluation?weeks=1..104` (default 12) →
  `{notice, fromWeek, toWeek, currentWeekIncomplete, countersEnabled, counters[{weekStart, metric, dimension,
  modelVersion, value}], rates[{name, dimension, numerator, denominator, minimum, status OK|NOT_ENOUGH_DATA|INCONSISTENT,
  value, low, high, lowerBound}], g1{status NOT_MET|COUNTS_MET_PRIVACY_REVIEW_REQUIRED, decidedCasesPerFourWeeks,
  activeModerators, note}}`; 400 / 401 / 403. No client endpoint or field changed. Config `app.evaluation.enabled`
  (true; false deletes the rows at the next clean-up), `app.evaluation.retention-weeks` (104, max 104). `POST /api/community/reports` answers as before; a lone report may later
  appear in `GET /api/community/mine` as `kind: CONFIRM` on the neighbouring signal's id. Config
  `app.intelligence.rules-2-mode` (SHADOW) and `.rules-2-shadow-interval` (1h; tests 0s).
- Phase 10A (V16, `docs/API.md` "Phase 10A endpoints"): `PUT /api/users/me/password {currentPassword, newPassword}` → 200
  fresh token (others revoked; 409 during ACTIVE SOS/walk; 409 wrong password; 429), `POST /api/users/me/deletion
  {password}` → 202 `{deletionRequestedAt, deletionDueAt}` (409 ACTIVE / < 24 h), `POST /api/users/me/deletion/cancel
  {password}` → 204; `/me`, login, register and password-change `user` add `deletionRequestedAt`, `deletionDueAt`; every
  authenticated endpoint can answer 401 with `WWW-Authenticate: Bearer error="invalid_token",
  error_description="credentials_changed|account_deleted|account_unavailable"`. Config `app.account.*`.
- Phase 10A.4 (V17): tokens carry `sid` + `auth_time`; `POST /api/users/me/token` → 200 renewed token (409 after 30
  days unless an SOS/walk is ACTIVE); `PUT /api/emergencies/{id}/resolve|cancel` optional `{password}`: from another
  sign-in than the starter 403 without it, 409 wrong, 200 right; emergency answers add `endedElsewhere`.
- Phase 11 (V18): `POST /api/emergencies` optional `clientRef` (same caller + reference ⇒ same emergency),
  `startedAt` (offline start, clamped `[now − 24 h, now]`, marks `startedOffline`), `endedAt` + `outcome`
  RESOLVED|CANCELLED (ended offline, stored ended); `PUT …/resolve|cancel` optional `endedAt` (clamped `[start, now]`);
  answers add `startedOffline`; location grace for offline-started SOS also counts from the server's first record.
  Rise (10B) has NO endpoint.
- C1 (V19, `API.md` "Phase 11 C1"): register/login/refresh return `refreshToken` (`<sid>.<secret>`);
  `POST /api/auth/refresh {refreshToken}` (public) → new access token + rotated secret; replaced secret within 60 s =
  lost answer, later = reuse → session ends; 401 reasons `session_revoked` | `account_deleted` | `account_unavailable`;
  `POST /api/auth/sign-out {refreshToken}` → 204 always; login optional `previousRefreshToken` (same account: hand-over
  incl. ACTIVE SOS); every endpoint refuses a `sid` whose session ended (`session_revoked`); password change ends the
  other sessions; renewal/password change adopt pre-V19 sign-ins once. Config `app.account.refresh-idle-timeout` (90d),
  `refresh-reuse-grace` (60s), `session-retention` (30d).
- Phase 13A (V20, `API.md` "Phase 13A"): register REQUIRES `dateOfBirth` (18+, never stored); user answers add
  `ageConfirmed`; `POST /api/users/me/age-confirmation`; `GET|PUT /api/profiles/me`, `GET /api/profiles/by-handle/{h}`,
  `GET /api/profiles/{id}` (`relationship`); `POST|GET /api/connections/requests`, `…/{id}/accept|decline`,
  `DELETE …/requests/{id}`, `GET /api/connections`, `DELETE /api/connections/{profileId}`; `POST|GET
  /api/connections/invites`, `DELETE …/invites/{id}`, `POST …/invites/redeem`; `POST|GET /api/blocks`, `DELETE
  /api/blocks/{id}`; same for `/api/mutes`. Invisible = 404; no counts; config `app.social.*`.
- Phase 13B (V21, `API.md` "Phase 13B"): `POST /api/places` (200, existing place returned), `GET /api/places/nearby?area=(6)`,
  `GET /api/places/{id}`; `POST /api/media` (image/jpeg|png body, ≤ 5 MB → 201; others 415), `GET /api/media/{id}`
  (`no-store, private`, `nosniff`); `POST /api/posts`, `GET|PUT|DELETE /api/posts/{id}` (PUT narrow-only, 409 on
  widening), `GET /api/posts/mine?cursor=`, `GET /api/profiles/{id}/posts?cursor=`; `GET|POST /api/posts/{id}/comments`,
  `DELETE /api/comments/{id}`, `POST /api/comments/{id}/hide|unhide`, `POST|DELETE /api/comments/{id}/helped`. Bands
  for everyone but the author; exact-place posts visible to others after 1–3 h.
- Phase 13C (V22, `API.md` "Phase 13C"): `PUT|DELETE /api/posts/{id}/butterfly` (`{state?: WANT|BEEN}`; own post / Ask
  400), `GET /api/cocoon?state=&cursor=` (`{items[{post, state, savedPeriod}], nextCursor}`); post answers add
  `yourButterfly` and, author only, `butterflies {count ≥ 3 | null, fewerThanThree}`; `GET /api/discover/edition?city=`
  (`{city, weekOf, theme, sections[{kind CITY|ASKS|THEME, items[{post, reasons}]}]}`), `GET
  /api/discover/connections?cursor=`, `GET /api/discover/categories/{c}?city=&cursor=`, `GET /api/places/{id}/posts?cursor=`
  (EXACT + Everyone only), `GET /api/places/search?q=&city=` (names only, ≤ 20); place answers add `city`. Config
  `app.social.butterflies-per-day` (200), `discover-reads-per-hour` (240), `place-searches-per-hour` (120).
- Phase 13D (V23, `API.md` "Phase 13D"): `POST /api/reports {targetKind POST|COMMENT|PERSON, targetId, reason, note?}` →
  202 always the same (400 own/reason/note, 404 invisible, 429 after 20/day), `GET /api/reports/mine`; `GET
  /api/social-standing` (pause + decisions of 90 days, never a "keep"), `POST /api/social-standing/decisions/{id}/appeal`
  (202, 409 asked/no longer applies); post answers add `approveFirst`, author `moderation {state, reason, decisionId}`,
  others `collapsed` UNDER_REVIEW|BY_MODERATOR; perspectives add `awaitingApproval`, `canApprove`, `moderation`,
  `collapsed`; posts accept `approveFirst`; `POST /api/comments/{id}/approve`; pauses answer 403 on their writes only.
  Moderators: `GET /api/moderation/content-cases?status=`, `GET …/{id}`, `POST …/{id}/actions {action, reason}`,
  `GET …/{id}/media/{mediaId}`, `GET /api/moderation/content-appeals`, `POST …/{id}/decision`, `GET
  /api/moderation/places?area=`, `POST /api/moderation/places/{id}/merge {into}`; `/api/moderation/appeals` = Phase 7
  RESTRICT appeals only. Config `app.moderation.reports-per-day` (20), `collapse-reports` (3), `content-interval` (1h),
  `app.social.probation` (72h), `probation-posts-per-day` (3), `probation-comments-per-day` (10).
- Phase 13E (V24, `API.md` "Phase 13E"): writes answer as before (never refused); post/perspective answers add
  `strongLanguage`; others' `collapsed` + `BY_SYSTEM`; the author's `moderation` + `source` (SYSTEM|MODERATOR|null),
  `flagged [{start,end}]` (code points of body + "\n" + tip); reason `AWAITING_CHECK`; the author's create/edit answer
  may carry `supportSuggested`. `POST /api/content-checks/review {targetKind POST|COMMENT, targetId}` → 202 always the
  same (own SYSTEM state only, else 404). Moderator summaries add `openedBy`, `contentSource`; detail adds `assessment
  {status, action, rule, reason, evidence[], strongLanguage, versions}`. Internal service `POST /v1/assess` (key header)
  and `GET /v1/health`. Config `app.moderation.ai.*` (enabled, url, key = env `MODERATION_SERVICE_KEY`, connect-timeout
  300ms, timeout 1500ms, model-mode OFF, probation-wait-max 2h, retry-for 24h, retention 90d, job-interval PT5M).
- Phase 17G-1 (V29, `docs/PHASE17G.md`): `POST /api/explore/search {city, categories[≤2], facets[≤4], daypart?}` →
  `200 {city, requested, hits[≤20 {place{publicId, name, category, city}, matchedCategory, matchedFacets[], matchedDaypart,
  requested, matched}]}`, best match first, the list always ends. **A structured query only** (closed lists; the words a
  person typed are understood on the phone and never sent). At least one of categories / facets / daypart (`400 "Choose what
  you are looking for"`), city = a catalog city (never OTHER), unknown codes / repeats / more than 2 categories or 4 tags
  `400`, `401`, `429` after 120 per hour (`app.social.explore-searches-per-hour`). Searches Everyone-audience EXACT-place
  Experiences in the city, not the caller's own, through the Edition visibility rule; no coordinates, times, authors or counts
  in the answer. Posts accept and answer `facets` (Explore tags, 13 closed codes in `explore/explore-facets-v1.tsv`, an
  Experience only, ≤ 4, no repeats): create `facets`, edit `null` = keep / `[]` = remove / a list = replace, an Ask with tags
  `400 "Tags are for experiences, not questions"`; the answer lists them in the tag list's order (empty list when none).
- Phase 17G-3 (`docs/PHASE17G3.md`, optional, OFF by default): `POST /api/explore/understand {text ≤ 200 chars}` → `200 {categories[≤2],
  facets[≤4], daypart|null}` (closed-list codes only, validated), `400` for blank / over-long / control-character text, `401`, or
  `503` with `code` `UNDERSTANDING_OFF` (mode off or no provider key) / `UNDERSTANDING_UNAVAILABLE` (our caps, the breaker, a
  timeout or any provider problem; the message never says which). `GET /api/explore/understanding` → `200 {available}` (mode ON and the
  internal service holds a provider key; remembered 2 minutes). The words are never stored or logged. Config
  `app.explore.understanding.*` (mode OFF [env `EXPLORE_UNDERSTANDING_MODE`], url, key = env `MODERATION_SERVICE_KEY`, connect-timeout 300ms,
  timeout 9s, user-daily-cap 20, global-daily-cap 300, breaker-pause 10m, status-cache 2m). Internal service (not an app endpoint):
  `POST /v1/understand` (200 chips; 503 off; 502 provider failure; **422 this request got no usable answer**, for which the backend does not open its breaker), `GET /v1/health` gains `"understanding": "UP"|"OFF"`; provider key env `IRL_UNDERSTANDING_API_KEY` (service only), `IRL_UNDERSTANDING_PROVIDER` (`groq` default | `anthropic`), `IRL_UNDERSTANDING_MODEL`.
- Phase 17G-2 (V30, U5, `docs/PHASE17G2.md`): `PUT /api/places/{id}/went` → `200 {rating|null}` (the caller's OWN visit; idempotent; a place the
  caller cannot see, or an unknown id, `404`; `429` after 20 new marks a day, `app.social.visits-per-day`); `DELETE /api/places/{id}/went` → `204`
  whatever was there (also removes the rating); `PUT /api/places/{id}/rating {rating 1..5}` → `200 {rating}` (`409 "Mark that you went there first"`,
  `400 "Choose 1 to 5"`, `403` while paused from posting, `429` after 40 rating writes a day, `app.social.rating-writes-per-day`);
  `DELETE /api/places/{id}/rating` → `200 {rating: null}` (keeps the visit; `409` without one). A merged place stands for its survivor. The place page
  `GET /api/places/{id}/posts` answers two more fields: `rating` = ONE guarded average as text ("4.3") or `null` (needs ≥ 5 counted raters, accounts
  past probation; Bayesian, one decimal; recomputed at most once per IST day; never a count, name or time) and `yourVisit` = `{rating|null}` or `null`
  (the caller's own only). Nothing orders by either.
- Phase 18A-2 (V31, `docs/PHASE18_TRUST_PLAN.md`; backend built 2026-11-14, Android not yet): `PUT /api/places/{id}/went` accepts an OPTIONAL body
  `{cell}` = the 7-character cell the phone is in (no coordinate; no body = as before). A malformed cell is `400 "Invalid cell"`. The server compares it
  with the place's cell and the cells around it, keeps one private bit and drops the cell; the answer is the SAME `200 {rating|null}` whether presence
  was confirmed or not, and no endpoint returns the bit. `POST /api/posts` and `PUT /api/posts/{id}` accept `disclosed` (bool, Experience only;
  ignored on a question; an edit can turn it on, `false` or absent never turns it off); every post answer adds `disclosed` (bool). The rating of a
  person who has a disclosed post at a place is not counted in that place's `rating` (their own `yourVisit` is unchanged). The app sends `{cell}` only as a
  second, silent call after the plain one, and only when the phone itself found that it is in the place's cell or a cell around it.
- Phase D12: `GET /api/health` (public, `{status: UP|DOWN}`, 503 when the database does not answer; `docs/D12_DEPLOYMENT.md`).
- Phase N3 (V34, `docs/PHASE_N3.md`): the community gate (403 `MEMBERSHIP_NEEDED` on every community route; own profile
  draft, own standing, account and safety not gated); `PUT /api/users/me/name` 409 `NAME_LOCKED`; applicant
  `GET /api/community/me`, `GET /api/community/programmes`, `POST|DELETE /api/community/verification`,
  `POST /api/community/verification/code`, `PUT /api/community/verification/photo`, public `POST /api/community/relink`;
  verifiers `POST /api/verifier/lookup`, `POST /api/verifier/requests/{ref}/decision`; admins `GET
  /api/admin/community/photo-requests`, `GET /api/admin/community/requests/{ref}/photo` (no-store), `POST
  /api/admin/community/requests/{ref}/decision`, `GET|PUT /api/admin/community/verifiers`, `PUT
  /api/admin/community/members/name`, `GET|POST /api/admin/community/bans`, `POST /api/admin/community/bans/{id}/lift`,
  `POST /api/admin/community/members/end`, `POST /api/admin/community/relinks`, `GET /api/admin/community/log`. Config
  `app.community.*` (secret = env `COMMUNITY_SECRET`).
- Phase N6 (`docs/PHASE_N6.md`): B1 walk trim: `POST /api/journeys {destinationLabel?, checkInIntervalMinutes}` (no
  coordinates; answers without `destination` / `lastLocation`); `PUT /api/journeys/{id}/location`,
  `POST /api/journeys/{id}/escalations`, `POST /api/journeys/{id}/circle-alerts` removed (404).
  B2: `GET|PUT /api/safety-alerts`, `POST /api/safety-alerts/test`, `GET /api/emergencies/{id}/alerts`,
  `POST /api/admin/alert-stops`, `…/lift`, `…/check`; config `app.alerts.*` (`ALERT_PROVIDER`, `ALERT_SECRET`, `ALERT_DEV_FILE`).
- Phase N7 (`docs/PHASE_N7.md`): B1 `PUT|DELETE /api/push/device`, `GET|PUT /api/push/settings`; FCM data-only pointers `{t, s, m, v}`; config `app.push.*`. B3 `GET /api/circle/requests`, `POST /api/circle/requests/{id}/accept|decline`, `GET /api/circle/in`, `DELETE /api/circle/in/{id}`, `GET /api/circle/alerts/{emergencyId}`; contacts carry `inApp`.
