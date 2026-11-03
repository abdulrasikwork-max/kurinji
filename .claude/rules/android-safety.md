---
paths:
  - "android/app/src/**/feature/emergency/**"
  - "android/app/src/**/feature/safewalk/**"
  - "android/app/src/**/feature/circlealert/**"
  - "android/app/src/**/feature/evidence/**"
  - "android/app/src/**/feature/community/**"
  - "android/app/src/**/feature/intelligence/**"
  - "android/app/src/**/feature/moderation/**"
  - "android/app/src/**/feature/safety/**"
  - "android/app/src/**/feature/safetycircle/**"
  - "android/app/src/**/feature/account/**"
  - "android/app/src/**/core/location/**"
  - "android/app/src/**/core/tracking/**"
  - "android/app/src/**/core/sms/**"
  - "android/app/src/**/core/crypto/**"
  - "android/app/src/**/core/device/**"
  - "android/app/src/**/core/notifications/**"
  - "android/app/src/**/core/escalation/**"
---
# Android safety features (moved verbatim from CLAUDE.md §5 on 2026-11-03)

SOS (Phase 3 + 11), emergency location (4A), Safe Walk (4B), movement checks (4C), Safety Circle SMS (4D), Evidence
Vault (5), Nearby place signals (6), trust UI + community moderation (7), safety intelligence (8). Invariants are in
`CLAUDE.md` §5 and §11.

> **Read with care (checked against the code on 2026-11-03):** this text was written phase by phase. Since 13F
> there is no `feature/home` (no Home screen, no `HomeStatus`): "Home banner" now means the session banners in
> `MainActivity` (`SessionStatusViewModel`), "Profile" means the You tab. Since Phase 16/17E the third tab is **Saved**
> (`SavedFragment`, nav id still `cocoonFragment`); Cocoon / Want / Been are gone. `feature/rise` was removed in 13F.
> When this text and the code disagree, the code wins: flag it to the user.

- **Emergency (Phase 3):** `EmergencyUiState` = Loading → Idle → Countdown(5..1) → Starting → Active → Ended(RESOLVED/CANCELLED),
  plus StartFailed. Countdown is **client-side only** (cancel records nothing). Recovery: fetch history, show any ACTIVE event.
  The active screen lists enabled Safety Circle contacts **for display only — nobody is notified** (Phase 3; since 4D see
  "Safety Circle communication" below). Home SOS tile badge: "5 s to cancel", under the subtitle (not beside the title: it
  squeezed "SOS" off 320 dp screens); its description says SafeSphere texts the
  Safety Circle only with automatic SMS switched on (was "No alerts yet" until 2026-10-21; `HomeTextsTest` guards it).
- **SOS without internet (Phase 11, `docs/PHASE11.md`):** at the countdown's end `SosRecorder` (application scope, the
  one holder) starts the SOS ON THE PHONE with a LOCAL id (negative, `EmergencyIds`, from a random `clientRef`); location
  sharing and the Safety Circle start at once. It records it on the server with the same reference (idempotent; first
  attempt without phone time, later ones with it; backoff 5→60 s, at once when online, after a restart too); the
  server's id is then pushed to the `EmergencyRebinder`s (Circle first, then location: memory, deliveries, pending
  reports, queued and parked points move). "I'm safe"/"False alarm" before the server has it, or when the end call has
  no connection, go to `SosRecorderState.unsent` (≤ 5, `UnsentEndingsCodec`: numbers/UUID/flags) and are sent later
  (created ended, or `endedAt`). "Call 112" before the server has it is remembered and recorded later. 409 on start =
  join the server's ACTIVE SOS. Evidence only once on the server. Sign-out clears everything (`clearAll`). Tests with the
  real recorder use `sosRecorderForTest` + `settle()` (background scope).
- **Emergency location (4A):** `EmergencyLocationSharing` (application scope, single-threaded main) is the one holder read by
  the SOS screen (`state`) and `SafetyTrackingService` (`notice`; null ⇒ service stops). Queue in memory only (newest 500),
  batches ≤ 50, backoff 5→60 s. `ActiveEmergencyStore` keeps `{userId, emergencyId, startedAt}` for offline recovery
  (cleared on end/logout). Location asked once automatically when the emergency is ACTIVE, then via the card.
- **Safe Walk (4B):** `SafeWalkSession` (application scope) is the one holder for the screen, Home, the notification
  "I'm OK" action (`CheckInActionReceiver`, not exported) and the service. `CheckInPolicy` is pure: ON_TRACK → DUE_SOON
  (1 min before) → MISSED = L1 → NO_RESPONSE = L2 (+2 min); never L3 by itself. Local deadline = phoneNow + (due − serverTime).
  Ordered offline command queue; `ActiveJourneyStore` keeps ids/interval/start/local deadline/pending end (no destination,
  no location). The ONE `SafetyTrackingService` reads `CompositeTrackingNoticeSource` (emergency > walk); screens start it
  via `TrackingServiceStarter`. `EmergencyViewModel.onEmergencyStarted` ends the local walk.
- **Movement checks (4C):** `MovementPolicy` is pure (`onFix(state, fix, destination)` → next state): Safety Bubble
  (corridor around start→destination, half-width 40 % of length, 250 m–2 km, outside = distance − accuracy > width,
  sustained ≥ 3 fixes + 90 s), unexpected stop (10 min within 60–200 m), arrival hint (never auto-ends), fixes > 100 m
  accuracy ignored (GPS "weak" = paused). A NEW concern opens the same prompt early: `CheckInPolicy.evaluate(due, now,
  concern)` → L1 with reason, L2 after 2 min, never higher; "I'm OK" answers and suppresses the same condition until it
  clears. `BatteryPolicy` (pure): LOW ≤ 20 %, CRITICAL ≤ 10 %, +5 hysteresis, charging = NORMAL; low → location every
  60 s; critical → one notification + latest fix sent once; SOS location is never throttled. Destination looked up via
  `DestinationResolver` (platform Geocoder) at start; the phone stores only the switch + open concern (kind, time).
- **Safety Circle communication (4D):** `CircleAlertCoordinator` (application scope, the one holder) watches
  `SafeWalkSession.state` and gets SOS active/ended hooks from `EmergencyViewModel`; `CircleAlertPolicy` (pure) decides:
  walk L2 → "did not answer" (≤ 3 per walk) and an all-clear after an answer; SOS → one message (waits ≤ 30 s for a fix,
  one location follow-up ≤ 10 min), resolved/false-alarm at the end; nothing for stale (> 30 min) emergencies.
  Automatic ONLY if opted in + `SEND_SMS` + SMS supported + enabled contacts; otherwise the SMS app (`ACTION_SENDTO`).
  `CircleMessages` (pure) = Safety Circle allow-list text (name, time, what happened, map link). `AndroidSmsSender`
  (multipart, sent/delivery PendingIntents → non-exported `SmsResultReceiver`). Statuses SENDING/SENT/DELIVERED/FAILED/
  COMPOSER_OPENED; SENDING never reported. `DataStoreCircleAlertStore` keeps opt-in, enabled contacts (name+number, for
  offline SMS), alert memory, recent deliveries, pending server reports (cleared on logout). "Call 112" = `ACTION_DIAL` +
  `POST /dial-prompts` (L4). Emergency details screen reads the context packet (OWNER).
- **Evidence Vault (Phase 5, `docs/PHASE5.md`):** `EvidenceVault` (application scope, the one holder) seals each
  capture at once: per-item 256-bit key, `core/crypto/EvidenceCipher` = **SSEV1** (AES-256-GCM, 64 KiB segments, STREAM
  nonce; byte-identical to backend `SsevFormat`, shared test vector), both SHA-256 hashes, key wrapped by
  `AndroidKeystoreKeyWrapper` (not auth-bound) and, when the server has a vault key, by `EscrowKey` (RSA-OAEP-SHA256;
  optional pin `evidence.vault.key.id` in local.properties). Files: `files/evidence` (ciphertext + `index.json`),
  `files/evidence-capture` (plaintext staging + note; `recover()` seals after a crash), `cache/evidence-view` (playback
  only). Upload: declare → resumable pieces → server seal; refusals keep the item. `EvidenceRecorder`: audio only on tap
  during ACTIVE SOS, 30 s AAC/ADTS parts, stops at 60 min / low storage / emergency end / logout / mic loss.
  Photo/video via camera-app intents + FileProvider (no CAMERA permission — never declare it). Vault screen
  FLAG_SECURE; delete everywhere = server password check, refused while ACTIVE. Items belong to the capturing account.
  Tests: background holders need `runCurrent()`/`advanceTimeBy()`, NOT `advanceUntilIdle()` (it ignores backgroundScope).
  Hardening (§10): every vault action checks the item belongs to the signed-in person; `VaultLock` (app scope) +
  `ScreenLockGate` (platform BiometricPrompt / Keyguard, `USE_BIOMETRIC`) gate the vault SCREEN, re-lock on leaving or
  account change (app gate, not auth-bound keys — deliberate); index written fsync'd with `index.json.bak` fallback;
  `PlaintextWipe` for staging/view files; `CustodyAnchoring` (pure) = phone-held `(sequenceNo, entryHash)` per
  emergency in `custody-anchors.json`; `EvidenceUploadJobService` (JobScheduler, job id 5001, network constraint, not
  persisted) resumes uploads after process death; "Remove from this phone" first asks the server to re-check
  (`/verify`); refused uploads can be retried. `ApiError.TOO_MANY_ATTEMPTS` = 429 (our message shown).
  Restore on a new phone (§11): `RecoveryKeyCrypto` (own PBKDF2-HMAC-SHA256 600k over NFC UTF-8; GCM wraps with AADs
  `SSRK1|rkVersion`, `SSRW1|clientRef|sha`), `RecoveryKeyStore` (RK per account, Keystore-sealed,
  `recovery-keys.json`; a NEW RK is stored only after the server accepted it), `EvidenceRecovery` (set up / restore /
  change / forgot), `PassphrasePolicy` (≥ 15 chars, not name/email/account password, not in `common_passwords_10k.txt`)
  + `PassphraseGenerator` (5 EFF words, `eff_large_wordlist.txt`); vault section + `EvidenceRecoveryViewModel`
  (behind `VaultLock`); `EvidenceVault` adds the copy at declaration (re-declares without it on a "recovery" 409),
  back-fills when idle, `importRestored` (content on Bring back), `syncDeletions` on vault refresh.
- **Community (Phase 6, `docs/PHASE6.md`):** place signals, not posts. The phone turns one fresh fix into a geohash
  (`core/location/GeoHash`, shared vectors with backend `GeoCell`): 6 chars (~1 km) to look at the area, 7 chars
  (~150 m) to report/answer; **no coordinate is ever sent**. `CommunityPolicy` (pure): reporting needs PRECISE +
  fix ≤ 100 m (viewing works with approximate), outbox ≤ 10 / ≤ 24 h, placement (distance, bearing), brief, 50/100 m
  rounding. `CommunityViewModel` (location only while the screen is open, 20 s timeout), `DataStoreCommunityOutbox`
  (cell + category only, cleared on logout), `SafetyRadarView` (no map tiles; shape + colour; halo = ~1 km precision),
  Home "Nearby Safety" opens Community. No new permission; location asked from the Community screen only.
  Phase 9D: the report confirmation (`report_confirm_message`) says a report may be added to the same report in the
  block right next to yours and then shows as "you saw this too" (`CommunityTextsTest`); no other phone change.
- **Trust UI (Phase 7, `docs/PHASE7.md`):** `AreaSignal.confidence/reasons/youFlagged` (unknown → LOW, never more
  certainty than told); card shows "Not yet confirmed / Confirmed by people nearby / Strongly confirmed", "How sure is
  this?" (reasons) and "Report a problem" (flag reason + confirmation, current 7-char cell only); `canFlag`/`canAnswer`
  exclude own and flagged signals; `CommunityStanding` from `/standing` drives a warning banner + "Ask for a review"
  and blocks writes; a 403 reloads it. `ApiError.FORBIDDEN` (403, our message shown); `AuthError.TOO_MANY_ATTEMPTS` (429).
  Trust tiers are NEVER shown in the app. "Withdraw" hidden for UNDER_REVIEW items.
- **Moderation (Phase 7, `feature/moderation`):** Profile shows "Moderation" only when `GET /api/moderation/access`
  says `canModerate` (hidden on any failure; re-asked on every Profile start). `ModerationPolicy` (pure) mirrors the
  server: reason codes per decision, no decision on a case you took part in / a closed case, no appeal decision on your
  own restriction. `ModerationViewModel` (queue; 403 ⇒ NotAllowed, data cleared) and `ModerationCaseViewModel` (only
  possible decisions sent; 409/403 reload). DTO unknowns fail closed (`youTookPart`/`youDecidedTheRestriction` default
  true); pseudonyms/cells redacted in `toString()`. Both screens FLAG_SECURE. Nothing stored on the phone. Closed cases
  and admin functions are API-only. The server is the authority for every check. Phase 9C: the case screen adds the
  coordination pattern as text lines (`ModerationTexts.coordinationLines`: reason, size/places bands, and ALWAYS the
  guidance "Backing each other is not proof…"); `CoordinationDto` drops unknown reasons; `ModerationTest` scans the
  `coordination_*` strings for accusatory words. 9C.1/9C.2: "Others in this group" (`CaseDetail.groupMembers`, read
  only when role and flag are both null), "Pause everyone in this group" (`ModerationPolicy.canRestrictGroup`: open
  coord-1 case only; `onRestrictGroup` sends RESTRICT + `wholeGroup`), "Keep it" on a COORDINATION case confirms with
  `coordination_confirm_dismiss` (Option B); each "Pause" button is labelled "Pause Person xxxxxx" for TalkBack.
- **Safety Intelligence (Phase 8, `docs/PHASE8.md`, `feature/intelligence`):** decision support only. The server sends
  CODES (rules-1; since 9D rules-1.1, which never sends NO_CONFIRMED_CONCERNS; the phone keeps its text and ignores
  `modelVersion`), the phone writes the words (`IntelligenceTexts`; a test forbids "safe/safest/dangerous/verified"
  claims). Two axes: concern (confirmed only) and information (how much is known); no outlook means "safe".
  `AreaPictureDto` fails CLOSED (unknown/inconsistent ⇒ no card); `RegionPackDto` fails SAFE (anything unknown ⇒ pack
  incomplete ⇒ no brief). Community: `AreaView(signals, picture)`, card above the radar (amber for care, teal info
  otherwise, never green/red), `SafetyRadarView.setAreaCells` (hatched = nothing known, dotted = little, amber outline =
  confirmed concerns). Night order from the phone's own clock (`IntelligencePolicy.isNight`). Safe Walk set-up:
  "Check the way" in its own `WayCheckViewModel` (one fix, destination via the shared `destinationResolver`,
  `CorridorBriefPolicy` pure: straight-line corridor = bubble half-width capped at 500 m, ≤ 4 regions, ≤ 20 km; only
  5-character region cells are sent). Nothing stored; `IntelligenceIsolationTest` keeps it out of SOS, tracking,
  Safe Walk session/policies, circle alerts and evidence.
