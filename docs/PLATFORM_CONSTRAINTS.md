# Android platform constraints

This file records the restrictions SafeSphere designs around, instead of working around them with insecure
tricks. Policies change often: re-check the current Android and Google Play documentation before each phase.

## What Phase 4A actually does (emergency location)

| Constraint | How 4A handles it | Remaining limitation |
|---|---|---|
| Location is "while in use" only; background location (`ACCESS_BACKGROUND_LOCATION`) is a separate permission reviewed by Google Play | Not requested. Instead a **foreground service of type `location`** (`SafetyTrackingService`) keeps updates running with the screen off, which Android allows for while-in-use access | Tracking only continues while that service runs |
| Android 12+ forbids starting a foreground service from the background | Started only from the visible SOS screen (re-requested every time it becomes visible) | After the process dies, tracking resumes only when the person reopens the SOS screen |
| Android 14+ requires location permission at the moment `startForeground(type=location)` is called (SecurityException otherwise) | Service started only once location permission is granted; start failures are caught and the screen says "Keep SafeSphere open" | — |
| Android 12+ lets the person grant only **approximate** location | Accepted; balanced-power request; the screen explains positions may be off by a few hundred metres | Approximate positions are much less useful for helpers |
| Permission denied / permanently denied ("Don't ask again") | Asked once automatically when the emergency becomes active, then via "Allow location"; permanent denial shows "Open settings" | — |
| Revoking a runtime permission in Settings kills the app process | On restart, `access()` reports NONE and the card asks again | Points queued in memory at that moment are lost |
| Phone location switched off | Detected (`LocationManagerCompat.isLocationEnabled` + fused availability); "Turn on location" opens the settings; updates resume by themselves | — |
| `START_STICKY` restarts would run without while-in-use access | `START_NOT_STICKY`; recovery happens on next app open (server history, or the phone's own record when offline) | — |
| OEM battery killers (Xiaomi, Oppo, Vivo, Samsung...) may stop foreground services | Recovery on next open | Not preventable by the app; users may need to exempt SafeSphere from battery optimisation |
| Android 13+ `POST_NOTIFICATIONS` | Asked right before the first tracking notification; if denied the service still runs (Android hides the notification) | — |
| Lock-screen privacy | Notification `VISIBILITY_PRIVATE` with a public version "SafeSphere is running"; it never contains coordinates | — |
| Network loss | In-memory queue (newest 500 points), uploaded in batches of up to 50 when back online; server de-duplicates re-sent points | Queue is lost if the process dies (deliberate: precise locations are never written to the phone's storage) |
| Phone clock wrong by more than 5 minutes | Server rejects such points (400); the app drops them and says "check the date and time" | — |
| Google Play services missing (some devices, non-Play emulators) | Reported as "Location is not available on this phone" | No fallback provider yet |
| Device-to-device transfer ignores `allowBackup="false"` on Android 12+ | `dataExtractionRules` exclude all app data from cloud backup **and** device transfer | — |

## What Phase 4B adds (Safe Walk)

| Constraint | How 4B handles it | Remaining limitation |
|---|---|---|
| No exact alarms, no server push (not in Phase 4) | Check-in timer is a coroutine in the app process, kept alive by the location foreground service; location updates keep the CPU waking during Doze | If the process is killed, nothing escalates until the app is opened (Home banner then leads back) |
| A location foreground service needs location permission | Walk still runs without it; screen says reminders only work while SafeSphere is open | A timer-only service type would need a re-plan |
| Notification permission (13+) | Asked when the walk starts (alerts depend on it); hint shown if denied | Without it, reminders appear only in the app |
| Lock screen | Alerts `VISIBILITY_PRIVATE`, public text "SafeSphere needs your attention"; no destination/location | "I'm OK" can be tapped by whoever holds the phone (duress PIN is reserved for later) |
| Notification action after process death | `goAsync()` receiver recovers the walk, then checks in | — |
| Phone clock wrong | Countdown uses server time-left; server verifies "missed" against its own deadline | A manual clock change mid-walk shifts the local countdown until the next server answer |

## What Phase 4C adds (stop / route checks, battery)

| Constraint | How 4C handles it | Remaining limitation |
|---|---|---|
| Platform `Geocoder` may have no backend, need network, be slow or pick a wrong place | Behind `DestinationResolver`; 6 s timeout; 50 km search box around the person; any failure → walk runs with stop checks only and the screen says why; the found address is shown so the person can switch the checks off | The typed text is sent to the phone's map service (disclosed in the privacy policy). No offline geocoding |
| Android 13+ async `Geocoder` API vs older blocking one | API 33+: `GeocodeListener`; older: blocking call on `Dispatchers.IO` | — |
| GPS accuracy varies (indoors, approximate permission ≈ hundreds of metres) | Fixes worse than 100 m are ignored and the screen says "GPS signal is weak: checks paused"; approximate-only access therefore effectively pauses the checks | No movement checks with approximate location |
| Location updates in Doze / background | Only while the 4A/4B location foreground service runs; low battery halves the request rate | If the process is killed, no checks until the app is opened again |
| Battery level | `BatteryManager` (no permission), polled every 60 s; no broadcast receiver | Emulators may report odd levels (`adb shell dumpsys battery set level N` to test) |
| Motion sensors for inactivity | **Not used**: throttled in the background, extra permission (`ACTIVITY_RECOGNITION`) on 10+ | Stops are detected from location only (10 min) |

## What Phase 4D adds (Safety Circle SMS, Call 112)

| Constraint | How 4D handles it | Remaining limitation |
|---|---|---|
| Google Play restricts `SEND_SMS` | Checked 2026-10-20: allowed under the **"Physical safety/emergency alerts to send SMS"** exception with a Permissions Declaration and review. Asked only when the person switches on automatic SMS (rationale first); without it the SMS app opens and the person sends | A Play release needs the declaration; review is not guaranteed |
| The system dialog says "send **and view** SMS" (permission group wording) | Rationale states the app only sends; only `SEND_SMS` is declared (no READ/RECEIVE_SMS) | The wording is Android's |
| Tablets / devices without SMS | `uses-feature telephony required=false`; `FEATURE_TELEPHONY_MESSAGING` (33+) / `FEATURE_TELEPHONY` checked; card says "cannot send SMS" | Only the SMS app path (if any) |
| No SIM / no default SMS subscription / no signal | `send()` failures and "sent" result codes → FAILED, shown per contact with the Text fallback | — |
| "Sent" ≠ delivered; delivery reports are optional per network | Sent PendingIntent → SENT; delivery report PDU (TP-Status) → DELIVERED only when all parts confirm | Many networks never report; results after process death are lost |
| Delivery PendingIntent must be mutable (system fills in the PDU) | Explicit intent to a non-exported receiver; sent intents immutable | — |
| Calling needs `CALL_PHONE` (not wanted) | `ACTION_DIAL tel:112`: the dialer opens, the person presses call | The app cannot know whether a call was made (recorded as "dialer opened") |
| `ACTION_SENDTO smsto:` with several numbers is not portable across SMS apps | One contact per composer (list on SOS screen, picker on Safe Walk) | — |
| Phone may be offline for the server but have SMS | SMS needs no internet; contacts cached on the phone; server reports queued and persisted. **Phase 11:** the SOS itself starts on the phone (before Phase 11 an SOS without a server answer only showed "could not record"); `SosRecorder` records it later with a client reference | Needs a signed-in session; evidence waits for the server record; queued points are memory-only (`PHASE11.md` §3.7) |

## What Phase 5 adds (Evidence Vault)

| Constraint | How Phase 5 handles it | Remaining limitation |
|---|---|---|
| Android 11+ blocks microphone access from the background unless a foreground service of type `microphone` was started while the app was visible | Recording starts only from a tap on the visible SOS screen; the ONE tracking service then adds the microphone type (verified types `0x88`) | If Android refuses, the card says "Keep SafeSphere open" |
| Android 14+ needs `FOREGROUND_SERVICE_MICROPHONE` and the RECORD_AUDIO grant at `startForeground` | Both declared; types are computed from the granted permissions; failures are caught | — |
| Privacy indicators (12+) | Accepted: the green indicator is part of the "never hidden" design | — |
| Camera from the background is impossible; `CAMERA` permission + CameraX would add risk and a dependency | Camera-app intents with a FileProvider URI into app-private staging; no `CAMERA` permission declared (declaring it un-granted would break these intents) | The camera app sees the media and may keep its own copy; it may ignore the 10 min / 190 MB video limit |
| Process death mid-recording | AAC/ADTS parts + a note per capture; sealed on the next start (verified) | A recording can only continue once the person reopens the app |
| Keystore keys bound to user authentication would stop sealing while the phone is locked | Key not auth-bound; StrongBox when present | Viewing now needs the screen lock (see "Hardening" rows), but as an app gate |
| Screenshots / recents thumbnails | `FLAG_SECURE` on the vault screen and its viewers (verified: black screenshot) | Not applied to the SOS screen (it shows no evidence content) |
| Flash storage cannot guarantee secure overwrite | Plaintext only in app-private storage, for seconds, then deleted; evidence at rest is ciphertext | Forensic recovery of deleted plaintext blocks is not ruled out |
| Google Play foreground-service and microphone review | — | A release needs the FGS declaration (user-initiated recording during an emergency) |
| (Hardening) Uploads stop when the process dies | Platform JobScheduler job with a network constraint restarts the process and resumes uploads (verified on API 37 after a process kill) | Not persisted across reboot (no `RECEIVE_BOOT_COMPLETED`); "Force stop" cancels jobs by design; OEM battery managers may delay or block jobs; regular jobs run ≤ ~10 min, then again with back-off |
| (Hardening) Auth-bound Keystore keys are invalidated when the screen lock is removed | Vault VIEWING gated by BiometricPrompt / device credential (Android 10+) or the Keyguard confirmation screen (8–9); the key stays non-auth-bound | It is an app gate: a rooted / compromised phone could bypass it. Without a screen lock there is nothing to ask (the vault warns) |
| (Hardening) Overwriting files on flash | Plaintext staging / viewing files are zero-overwritten, flushed, then deleted | Best effort only (wear levelling); file-based encryption remains the real protection |

## What Phase 10A adds (account security and deletion)

- **No new permission.** Settings links to the phone's own app-details and notification pages (system intents).
- **Sessions:** tokens last 24 h. A password change ends every other phone's session on its next request.
  **10A.4:** while the app process runs, the phone renews its token when less than 12 h are left (every 10 minutes
  during an SOS or Safe Walk, when the foreground service keeps the process alive; hourly otherwise), and the server
  always allows that during an SOS or walk. If the process was killed and the app is not opened for 24 h, the token
  still expires (no background job wakes the app for it; deliberate: no new job or alarm).
- **Ending an SOS from another phone** needs the account password; the starting phone notices when its SOS screen is
  next visible.
- **Deletion and the phone:** the phone can remove a deleted account's evidence only when it hears the server's
  `account_deleted` answer (needs a still-valid token) or when the person taps "Remove its data from this phone" on
  Login after the date. Uninstalling the app removes everything anyway.
- **FLAG_SECURE** on the Account screen: screenshots and the recents preview are blank there (by design).

## Constraints for later phases

| Future feature | Constraint to plan for | Design direction (not implemented) |
|---|---|---|
| Microphone / voice keyword detection | Recent Android versions limit microphone use when the app is in the background and show a privacy indicator. Continuous listening needs a visible foreground service with the correct service type and permission. Always-on listening drains battery. | Use a foreground service with a persistent notification, only while the user has switched a protection mode on. Never record silently. |
| Location (GPS, live sharing, Safe Walk) | Background location is a separate permission, requested after foreground location, and Google Play reviews apps that ask for it. Android 14+ requires declaring foreground service types. | Ask for foreground location first; explain why; use a location foreground service during an active journey or emergency. |
| Shake / physical SOS | Sensors are throttled when the app is in the background or the device is in Doze, so a plain background listener is unreliable. | Run detection inside a foreground service while protection mode is on; add a visible on-screen SOS as the dependable path. |
| Emergency SMS fallback | Done in 4D (see above): opt-in SEND_SMS under Play's physical-safety exception, SMS app otherwise. | A server-side gateway (no phone needed) remains a later option. |
| Starting work from the background | Android 12+ blocks starting most foreground services from the background; Doze and App Standby delay background jobs; some phone makers add their own battery killers. | Start emergency work from a user action. Use high-priority notifications and tell users how to exempt the app from battery optimisation. |
| Notifications | Android 13+ needs the POST_NOTIFICATIONS runtime permission. | Done in 4A (see above). Safe Walk alerts (4B) will add their own channel. |
| Camera / video evidence | Done in Phase 5 (camera-app intents, see above). | In-app capture (CameraX) only if approved later. |
| Evidence encryption | Done in Phase 5 (Keystore-wrapped per-item keys, SSEV1, SHA-256 on phone and server). | A vault PIN/biometric gate for viewing; WorkManager uploads. |
| Backend communication | Cleartext HTTP is blocked by default for modern targets. | HTTPS only for release builds. INTERNET was added in Phase 2 (debug builds allow plain HTTP to 10.0.2.2 only). No secrets in the APK; use short-lived tokens. |
| Backup | `allowBackup` is already `false` in Phase 1 so private data is not copied to cloud backups. | Keep it off, or use explicit backup rules for non-sensitive data only. |
