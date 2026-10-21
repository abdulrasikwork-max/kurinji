# SafeSphere API (Phase 2)

Base URL (local development): `http://localhost:8080`
(From the Android **emulator** the same server is `http://10.0.2.2:8080`.)

All requests and responses are JSON (`Content-Type: application/json`).
The examples use made-up values only. Never put real credentials in documentation.

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
| `POST /api/emergencies` | Record a new ACTIVE emergency. Optional body `{"triggerType":"MANUAL"}` (default MANUAL; `SHAKE`, `VOICE`, `DURESS_PIN` are reserved and rejected) | `201` | `400` unsupported trigger, `401`, `409` one is already active |
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
