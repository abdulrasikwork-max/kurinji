# Phase 17 plan — product reset, bug fixes, social UX, AI Explore (DRAFT, awaiting the user's decisions)

Status: **17A implemented, compiled, tested, emulator-verified 2026-10-27** (`PHASE17A.md`); 17B–17J planned. Written 2026-10-27 after a read-only inspection
(Phase A). The shell was unavailable during the inspection (the tool's safety check returned no verdict), so **no
build, test, database query or device check was run**; every finding below comes from reading the code and the docs.

---

## 1. Phase A findings

Legend: **W** working (tested in an earlier phase) · **P** partial · **B** broken · **M** missing · **NV** not verified.

### 1.1 Root causes that explain several reported bugs at once

| # | Finding | Evidence | Explains |
|---|---|---|---|
| R1 | **The debug build talks to `http://127.0.0.1:8080/`.** On a physical phone that works only while the USB cable is in, `adb reverse tcp:8080 tcp:8080` is active and the backend runs on the PC. Unplugged, or after a reconnect, *every* server call fails with "no connection". | `android/local.properties` line 6; `app/build.gradle.kts:16`; debug cleartext only for 10.0.2.2 / localhost / 127.0.0.1 | "Cannot post", Nearby reports failing, Discover empty, uploads failing on the phone |
| R2 | **No app-wide age state.** The server refuses every social call with `409 "Confirm your age to use this"` for an account without `users.age_confirmed_at` (accounts created before 13A). Android shows that raw message on every social screen; the *only* place to fix it is You → Edit profile (`ProfileSetupFragment`). New sign-ups are confirmed at registration (`AuthService.register:93`), so the bug hits older accounts — very likely the owner's personal account. Also `RemoteSocialRepository.ageConfirmed()` treats a missing field as "confirmed" (fail-open). | `SocialAccess.requireMember`, `RemoteSocialRepository:30`, `ProfileSetupViewModel` | The repeated "Confirm your age to use this", and posting refused (`requireCanPost` → `requireMember`) |
| R3 | **SOS location says "temporarily unavailable" on a normal GPS warm-up.** `EmergencyLocationSharing.collectLocations` maps every `NO_FIX` (fused `onLocationAvailability(false)`, which Play services sends routinely at the start and indoors) to `UNAVAILABLE`, even while still searching and even after a good fix exists. | `EmergencyLocationSharing.kt:220, 313` | "Temporarily unavailable" shown when nothing is actually broken |
| R4 | **Nearby reports need a precise fix ≤ 100 m.** Indoors or with approximate permission the report is refused on the phone ("location too rough") before anything is sent. This is a deliberate Phase 6 rule (a report in the wrong ~150 m block misleads), but the UX gives no path forward. | `CommunityPolicy.reportReadiness` | "Sometimes fails to submit" |

The shared location layer itself (`FusedLocationProvider`, `OneFix`, Phase 16) is sound: distinct states for permission,
location off, no fix, no Play services, with a platform fallback. The fix is to route the remaining screens (SOS sharing,
Nearby, Safe Walk) through the same states (R3, R4), not to build a new layer.

### 1.2 Area status

| Area | Status | Notes |
|---|---|---|
| Email + password sign-in, sessions (C1), revocation | W | Phase 2–11, live verified |
| E-mail verification (V25) | W in code / **B in practice** | No mail provider configured; real delivery never verified (`EMAIL_SETUP.md`) |
| Phone OTP | **M** | Nothing exists; needs an SMS gateway (see §3 D-1) |
| Age state | **B** (R2) | |
| Posting (text, category, place, photos) | W on emulator (Phase 16) / **B on the phone** (R1, R2) | Failed photos are silently left out of the post (`ComposeViewModel.draftOf`) — **B**, small |
| Multi-photo | W (≤ 6, carousel) | Phone re-encodes to ≤ 2048 px, JPEG q85 — visibly soft on large screens |
| Video | **M** | Server accepts JPEG/PNG only; no player on Android |
| Location layer | P (R3, R4) | |
| Discover feed, comment count, Talking now, end card | W (Phase 16, emulator) | NV on a phone |
| Butterfly | W as "Save" (Cocoon Want/Been) | The request changes its meaning (§2 17E) |
| Cities | P | Closed list of 12 + OTHER, enforced by CHECKs (V21/V22) — expanding needs V26 |
| Profile | P | No profile photo upload exists |
| You → Safety tools | W, visually dominant | |
| SOS start/cancel/offline/SMS/location | W (Phases 3–11) | NV on a phone; R3 |
| SOS audio | W, **tap-only** by design (Phase 5) | Auto-record on consent is new |
| SOS shake trigger | **M** | Never built: `SHAKE` is a reserved trigger the server *rejects* (V3). Not "broken" — absent |
| SOS voice trigger | **M** | Never built: `VOICE` reserved and rejected. Not "broken" — absent |
| Launcher shortcut + Quick Settings tile | W (tile emulator-verified 13F) | Shortcut and locked-phone tile NV |
| Nearby reporting | P (R1, R4) | Radar is app-drawn, no map tiles (privacy decision, Phase 6) |
| Safe Walk | W (4B/4C) | No route drawing; destination via Geocoder |
| AI moderation service | W (13E) | stdlib Python, rules only, no model |
| AI Explore, ratings | **M** | |
| Physical phone | **NV everywhere** | No device has been connected since 13F |

---

## 2. Proposed sub-phases (dependency order)

Each sub-phase: plan → implement → backend/Android/lint/Python tests → live API → emulator → phone (when connected) →
docs → one local commit. Nothing is pushed.

| Sub-phase | Scope | Depends on | Migration |
|---|---|---|---|
| **17A Stabilise** | R1: `api.base.url` options documented + a debug "LAN IP" path (cleartext for one configured host, debug only); R2: one age state (`/me.ageConfirmed` read at session start, fail-closed; any 409 CONFIRM_AGE routes to a single age screen, asked once); R3: SEARCHING vs UNAVAILABLE after a grace period, last fix shown with its time; failed photo blocks Share with "Retry / Remove" | — | none |
| **17B Phone OTP** | per D-1; email verification removed from the active flow (code kept until a later clean-up, routes removed) | 17A | V26 `phone_verifications` + `users.phone_*` |
| **17C Media** | photo quality ≤ 3072 px q92; video (D-6); upload progress + retry; no partial posts (post only when every item is uploaded) | 17A | V27 media kind/duration |
| **17D Location UX** | Nearby: "Move outside or wait for a better signal" path, approximate-permission upgrade prompt; Safe Walk on the shared states; post location = "Use where I am / Search / Recent" | 17A | none |
| **17E Social UX** | butterfly = reaction (D-3), Saved separate; profile photo; profile redesign; Discover cards; city search + expanded list (D-5); daily state (D-7); You → Safety compact grid; "I went" follow-through (§4) | 17C | V28 butterfly reactions, profile photo, cities, daily state |
| **17F Emergency** | auto-audio consent (first visit to You → Safety, stored per account on the phone); shake trigger (D-4); voice (D-4); Nearby simplified flow; radar readability; Safe Walk start→destination line on the app-drawn view; SOS last-known marked as such | 17A, 17D | V29 if SHAKE/VOICE triggers become accepted |
| **17G AI Explore + ratings** | per D-2 and D-8 | 17E | V30 ratings, experience facets |
| **17H Brand** | mountain mark (vector, adaptive icon, splash), notification sound (short .ogg, own channel; SOS channel unchanged) | — | none |
| **17I/J** | full regression, release-readiness audit | all | — |

---

## 3. Decisions needed from the user (each conflicts with a recorded rule or needs something only the user has)

| # | Decision | Conflict / why it is needed | Recommendation |
|---|---|---|---|
| D-1 | **SMS gateway for OTP** | OTP needs a paid SMS provider account; Indian OTP SMS also needs **TRAI DLT** sender + template registration. Without it, real delivery cannot be built or verified. Firebase Phone Auth avoids DLT work but is a Google dependency and moves verification to Google. | Provider-neutral `OtpSender` (like `VerificationMailer`), MSG91 or Twilio adapter configured by env vars; dev profile writes to a local sink file (never logs). Keep e-mail + password as the **sign-in**; the phone is verified once at sign-up. Existing accounts verify their phone at next sign-in. |
| D-2 | **What powers AI Explore's language understanding** | `ModelPlacesTest` and D2 allow models only for moderation, only inside the stdlib Python service. A real LLM means sending the request text to an external API (privacy, key, cost); a local model means a large new dependency. | Claude API (`claude-haiku-5-5`) from the Python service, receiving **only** the request text and candidate place facts (no user id, no profile, no location finer than the city); deterministic engine works without it (keyword/facet match, distance, recency). Needs D2 amended for this one use. |
| D-3 | **Butterfly = reaction** | Replaces the 13C Want/Been model. Who sees the number? Permanent rule: no public counts. | Reaction only; nobody sees a count except the author privately from 3 (as today). Saved becomes a plain bookmark; "Been" removed; existing Cocoon rows migrate to Saved. |
| D-4 | **Shake and voice SOS scope** | Background detection needs an always-running foreground service with a permanent notification; Android 14+ refuses to start a **microphone** foreground service from the background, so always-on voice is not reliably possible; on-device speech needs a ~40–50 MB model (new dependency). Both conflict with the Phase 4 "no background" decision. | Shake: opt-in, active while the app is open **or** during a Safe Walk (the existing service), pattern = 3 strong shakes in 2 s, then the normal 5 s countdown. Voice: **defer**; say so in the app. |
| D-5 | **Cities** | Closed list in CHECKs; "Chennai first" (D5). | V26+ `cities` table (name, state, box) seeded with ~60 Indian cities; places get a city by box; free text search over that table. |
| D-6 | **Video** | No video player in the app; Media3/ExoPlayer would be a new dependency (D7 allowed only Coil). Metadata (GPS atom) must be stripped. | Platform `MediaMuxer` remux on the phone (no GPS/metadata), server validates MP4 boxes and drops `udta`/`meta`; ≤ 60 s, ≤ 50 MB; play on tap with platform `VideoView` (no new dependency), never autoplay. |
| D-7 | **Daily state** | Rule "no presence/online status". | One emoji-and-word from a fixed list, visible on the profile to connections only, no time shown, clears at midnight IST, no notification, no history. |
| D-8 | **Ratings + "% match"** | Rule "no public counts of anything, no popularity rankings" (only comment counts allowed, U1). A star rating is a public aggregate; ranking by "community activity" is a popularity ranking. | Rating 1–5 only from people who marked "I went"; shown as "⭐ 4.6 Kurinji" only from 5 ratings, Bayesian average (prior 3.5, weight 5); never sorted by rating or activity alone; "% match" = how many of the request's facets the place matches, explained in words. Needs a new decision (U5). |
| D-9 | **Nearby: description / photo** | Phase 6 rule: place signals carry no text, photos or authors (anti-accusation, privacy). | Keep no free text and no photo; simplify to Report → type → confirm. |
| D-10 | **Physical phone** | Every hardware item in the request (GPS, shake, mic, camera, tile on lock screen, sound) needs the phone connected over USB with debugging on. | Connect the A23 before 17D/17F. |

---

### 3.1 Decided by the user, 2026-10-27

- **D-1:** provider-neutral `OtpSender` (MSG91/Twilio adapter via env vars, dev sink file); e-mail + password stays the
  sign-in; the phone is verified once at sign-up, older accounts at their next sign-in.
- **D-2:** Claude API (`claude-haiku-5-5`) from the Python service, request text + candidate place facts only; the
  deterministic engine works without it. Amends D2 for this one use (`ModelPlacesTest` to be updated accordingly).
- **D-4:** shake only (opt-in, app open or Safe Walk running, 3 strong shakes in 2 s → the normal countdown); voice
  deferred and said so in the app.
- **D-8 (= U5):** guarded ratings as recommended above.
- **D-3, D-5, D-6, D-7, D-9:** the recommendations above, accepted by the user on 2026-10-27 (with the go-ahead for
  17A).

## 4. The one small interaction (request §39)

**"I went"** on a saved or reacted post: one tap after a visit opens the composer with the place filled in, and (if
D-8 is accepted) the 1–5 rating. It closes SEE → WANT → GO → SHARE without streaks, points or counts, and it is the
only source of ratings, so ratings come from people who actually went.
