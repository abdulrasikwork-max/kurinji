# Phase 17J — Release readiness (2026-11-08)

Status vocabulary as in CLAUDE.md §3. **Nothing in this phase was committed, pushed or deployed**; no live SMS was sent; no
keystore was generated; no domain was invented.

17J had no written spec, so its scope is the release-blocker lists of `PHASE14.md` §5 / `PHASE15.md` §8-9 plus the user's brief
of 2026-11-08. The approach was reviewed by the advisor (Opus 5.5) before any change (order: offline-testable work first,
no new dependency, no toolchain bump, docs last). This file is the plan and the record.

## 1. What was done (implemented, compiled, tested)

| # | Blocker | Result | Key files |
|---|---|---|---|
| 1 | `AuthInterceptor` added the token to any host | The token is attached only when scheme + host + port equal the configured backend (`BackendOrigin`); `videoHeaders(url)` has the same check. | `core/network/BackendOrigin.kt`, `AuthInterceptor.kt`, `AppContainer.kt` |
| 2 | Access token plain in DataStore (A14) | Sealed with the Keystore key `safesphere_session_v1` (key `access_token_sealed`); never plain text, memory-only when the Keystore refuses (J-8). See §2. | `DataStoreSessionStorage.kt` |
| 3 | Release signing | `signingConfigs.release` only when `android/keystore.properties` (git-ignored) or `IRL_KEYSTORE_*` env vars exist; otherwise the release is **unsigned**, never debug-signed. | `app/build.gradle.kts` |
| 4 | R8 / minification | `isMinifyEnabled` + `isShrinkResources` on; keep rules for every field of our classes (Gson maps by field name, no `@SerializedName`; the same names are in the phone's own JSON files) and the Retrofit `data.remote` packages. | `proguard-rules.pro` |
| 5 | Versioning | `versionCode 1`, `versionName 1.0.0-rc1` (overridable: `-Papp.version.code=N`). **Your decision, see §5.** | `build.gradle.kts` |
| 6 | Production HTTPS | Separate `release.api.base.url`; default `https://api.irl-app.invalid/` (reserved TLD, can never resolve: fails closed). The Gradle release build **fails** if the URL is not `https://`, and a **signed** release fails if it is still the placeholder. Strict main `network_security_config.xml` (no cleartext, system CAs only), referenced from the manifest; debug builds overlay their generated allow-list. No pinning (needs the real host + rotation plan). | `build.gradle.kts`, `res/xml/network_security_config.xml` |
| 7 | Secrets / configuration | `git ls-files` has no `.env`, key, keystore or `gsk_`; `evidence-store/`, `social-media-store/`, `target/` are ignored; `keystore.properties` added to both `.gitignore`s; `backend/.env.example` now lists **every** variable the backend reads. | `.env.example`, `.gitignore` |
| 8 | Production configuration | Profile `prod` + `ProductionGuard`: the backend **refuses to start** with `OTP_PROVIDER` ≠ FAST2SMS (or no key), empty `OTP_SECRET`, a DB URL without `sslMode=REQUIRED|VERIFY_CA|VERIFY_IDENTITY` (or with `useSSL=false`), or a relative evidence / photo folder. Warnings for an empty vault escrow key or moderation key. | `config/ProductionGuard.java`, `application-prod.properties` |
| 9 | Rate limits behind a proxy | Documented + wired: `FORWARD_HEADERS_STRATEGY` (default `none`) and `TRUSTED_PROXY_REGEX` (§4). | `application-prod.properties` |
| 10 | Spring Boot support | Checked on Maven Central 2026-11-08: **3.5.16 is the newest 3.5.x** (no patch exists to apply); newest stable is **4.1.2** (4.2.0-RC2 also exists). Upgrade stays a separate planned phase (CLAUDE.md §6). | — |

### Also verified from the release APK (`app-release-unsigned.apk`, 4.7 MB)
- The only native library is androidx `libdatastore_shared_counter.so` (4 ABIs). Zip alignment `zipalign -c -P 16` passes and
  every ELF LOAD segment has `p_align = 16384`: **16 KB page-size ready** (read with a small ELF parser; Play requirement for
  apps targeting 15+).
- `aapt2 dump badging`: `com.safesphere.app`, versionCode 1, versionName 1.0.0-rc1, targetSdk 35, not debuggable.
- R8 `mapping.txt` shows our field names kept (`VaultItem.cloud -> cloud`), classes renamed.

## 2. The access-token change in detail (decision J-1, J-2)

Three traps found in review, each covered by `SealedAccessTokenTest`:
1. AES-GCM uses a fresh IV for every seal, so comparing the stored value with the old token (`replaceToken`, `clearIfToken`,
   `markNeedsSignIn`) would never match again. Every comparison now uses the **opened** value.
2. If the Keystore cannot open the token (key gone), the session must not vanish: it would sign the person out, break
   "expired is not revoked" and offline SOS. It now reads as the same person with an **empty token and "sign in to sync"**;
   nothing is deleted, an update of that state leaves the stored secrets alone (the Keystore may only be down for a moment),
   `AuthInterceptor` and `videoHeaders` send no header for an empty token.
3. `AuthInterceptor` reads the session on every request including every image: the last opened pair is cached in memory
   (50 reads = 0 Keystore calls).
Also: a plain token written by an older version is handled once at the first read (see J-8 below; the plain key is removed in
the same edit).

**J-2 (SUPERSEDED by J-8, 2026-11-08): the original 17J fell back to storing the token in the old plain key when the
Keystore refused to seal. That was wrong for a safety app: a token in plain text is exactly what 17J was meant to remove.**

### J-8: no token or refresh secret is EVER written to disk in plain text (security review of J-2)
Reviewed with the advisor (Opus 5.5) before changing anything. Rejected alternative: refusing the sign-in when the Keystore
fails (no session record then, and offline SOS needs one). Implemented instead, in `DataStoreSessionStorage`:
- Sealing is tried **twice** (a busy secure element often answers the second time).
- If it still fails, the session runs **memory-only**: token and refresh secret live in this process, so SOS sync, renewals
  and refreshes keep working while the app runs. The disk gets only the person's record plus a random, non-secret
  **generation id** (`token_memory_gen`) that ties the memory to exactly that disk state; every sealed or plain secret of an
  earlier state is removed in the same edit (a stale refresh secret replayed would end the session). A new generation id on
  every write also makes the flow emit when only the token changed, so the video headers and the SOS screen see renewals.
- After a **restart** (or any mismatch) the session is the existing safe state: same person, **empty token, "sign in to
  sync"**, nothing deleted. SOS keeps working locally (the safety holders read the person's record, not the token). The next
  sign-in writes a sealed session. While the generation marker exists, a sealed value is never used (it could belong to an
  earlier account).
- The plain `access_token` key is **never read as a token and never assigned**. A plain token from a version before 17J is
  sealed at the first read or, if the Keystore refuses, moved to memory; the plain key is removed in the same edit (if that
  edit fails, the key stays but is unreadable and the next read retries). Every write removes it.
- No Keystore key is deleted or regenerated (irreversible; regeneration would be the user's separate decision). The cost of
  a permanently broken Keystore: the person signs in again after every restart, and sees the banner.
- App-wide check: no other persisted store (Circle alert copy, SOS outbox, active emergency/journey, pending deletion, viewer
  and SOS options, onboarding, evidence notes, places) references a token, a refresh secret or an Authorization value.
- Restart-state behaviour pinned by `EmptyTokenRestartStateTest`: `SessionRefresher` returns NotPossible without a call,
  `SessionRenewer` makes no call however long the phone runs, a refused token-less request ends in "sign in to sync" and
  never in a sign-out.
- Tests (JVM, fake Keystore): raw DataStore **file bytes** are scanned for the token and the refresh secret after `save`,
  `replaceToken`, a refused seal and a legacy migration; a source guard forbids assigning the plain key; a mutation check
  (a plaintext write added on purpose) made 3 tests fail. **The memory-only path is NOT verified on a device**: a real Keystore
  failure cannot be produced on demand.

## 3. Tests and builds (this phase)

See §6 for the final numbers. New tests: `SealedAccessTokenTest` (7), `AuthInterceptorOriginTest` (6),
`Phase17JReleaseGuardsTest` (6, scans the build file, R8 rules, manifest, network config), backend `ProductionGuardTest` (8).

**R8 at runtime — manually verified on the API 33 emulator, and it found a real bug.** `assembleRelease` and the unit
tests prove nothing about Gson under R8, so a non-distributable build type `releaseCheck` exists (release code path, debug
key, application id suffix `.releasecheck`, development server, generated cleartext list). Walk against the local backend:
1. **First launch CRASHED at start-up** (`IllegalStateException: TypeToken must be created with a type argument`): R8
   stripped the generic signature of our `object : TypeToken<List<...>>() {}` classes (evidence / recovery-key stores,
   built in `AppContainer`). The assembled release APK looked fine and every unit test passed. Fixed with two keep rules
   (`-keep,allowobfuscation,allowshrinking class ...TypeToken` and `... * extends ...TypeToken`), pinned by
   `Phase17JReleaseGuardsTest`. **Without this rule a release build would have crashed for every user.**
2. **Second finding, same walk: server error text and codes were silently lost.** The wrong-password step did NOT prove
   error parsing (that 401 uses an app string, and sign-in errors are mapped by `RemoteAuthRepository`, not `ErrorBody`).
   A duplicate Safety Circle number (server answer 409 "This phone number is already in your Safety Circle") showed the
   app's generic "That conflicts with existing data. Please try again." in the minified build. Cause: R8 merged the
   private class `ApiCallHandler.ErrorBody` (filled only by Gson, never constructed in our code) into an unrelated class and
   its `message` / `code` fields were gone (confirmed in the dex), although `-keepclassmembers ... { <fields>; }` was
   present: that rule does not pin the CLASS. Every server-worded error (409/403/429 texts) and the `code` read by
   `ApiCallHandler` (AGE_NOT_CONFIRMED) were affected, in release builds only. Fix: `-keep,allowobfuscation class
   com.safesphere.app.**` (classes stay, names may still be obfuscated), pinned by `Phase17JReleaseGuardsTest`.
   **Verified after the fix on the emulator: the same step now shows the server's own sentence**, no crash logged.
3. After the fixes: opening screen, onboarding, sign-in screen; a wrong password gives "Invalid email or password" (app
   string, no crash); the **correct password signs in** (response DTOs parsed; the access token is
   sealed by the real Keystore); **force-stop + relaunch opens straight into the app** (sealed token and refresh secret read
   back); Discover → Chennai loads **real posts from the dev database**; You tab; Safety Center (incl. the first-visit audio
   question); Safe Walk screen; **Evidence Vault opens** (its screen is `FLAG_SECURE`, read through uiautomator); the bundled
   city list (a `getResourceAsStream` under R8) loads. No `AndroidRuntime` error in the log after the fix.
4. **NOT walked (still NOT VERIFIED under R8):** sign-up and phone-code screens, a post with a photo or video (needs a
   profile), the SOS countdown and an SOS, a Safe Walk start, a vault recording, Nearby, Explore (typed words + smart
   understanding), Safety Circle edit, comments, moderation screens, account deletion. A release candidate should repeat
   this walk once with the real release configuration before upload.
Scratch account `releasecheck.17j@example.com` (fake number 9000017710, password not recorded here) was created in the
DEV database for this walk; the emulator app was uninstalled afterwards.

## 4. Operator notes (production)

Reverse proxy (nginx example): TLS terminates at the proxy; `client_max_body_size 60m;` (videos ≤ 50 MB, uploads are raw
bodies, not multipart, so no Tomcat multipart limit applies); `proxy_read_timeout 120s; proxy_request_buffering off;`;
`proxy_set_header X-Forwarded-For $remote_addr;` (set it, never pass the client's). Then run the backend with
`SPRING_PROFILES_ACTIVE=prod FORWARD_HEADERS_STRATEGY=native` and make port 8080 reachable only from the proxy. Without this
every sign-up / OTP / sign-in limit counts the proxy as one person.
Back up together: the MySQL database, `EVIDENCE_STORAGE_DIR`, `SOCIAL_MEDIA_DIR`. Keep the R8 `mapping.txt` of every
released build (needed to read crash reports; it is in `app/build/outputs/mapping/release/`).

## 5. What needs the user (not done on purpose)

| Item | Why it is open |
|---|---|
| **Play target API: the app targets 35, Play now requires 36** | Checked 2026-11-08 (developer.android.com/google/play/requirements/target-sdk): new apps and updates must target Android 16 / API 36 since 31 Aug 2026. Moving to 36 means compileSdk 36 and a newer AGP (current 8.7.3), i.e. a toolchain change plus a behaviour review (edge-to-edge, predictive back, foreground-service rules). **Not done in 17J**; done in Phase 17K (`docs/TARGETSDK36_PLAN.md`): AGP 8.13.2, Gradle 8.14.6, compileSdk and targetSdk 36. The Play target-API blocker is closed in code; a real upload and an Android 16 phone are still to do. |
| Production domain, hosting, TLS certificate | Not available; `release.api.base.url` stays a placeholder. Then decide certificate pinning. |
| Release keystore | Generate and **back up** a release key (losing it means no updates). Put its paths/passwords in `android/keystore.properties` or `IRL_KEYSTORE_*`; use Play App Signing. |
| Version numbers | `1 / 1.0.0-rc1` is my proposal; confirm or change. |
| Real OTP | Per `PHASE17B.md` the Fast2SMS OTP route needs KYC on the account and a first Rs 100 top-up; it sends a FIXED text ("Your OTP: 123456") from Fast2SMS's own sender, so **DLT registration is only needed for a branded sender/custom text** (not verified in practice: confirm with Fast2SMS and current TRAI rules before launch). Then a real-phone test (no real SMS was ever sent). Provider choice is yours (`OtpSender` is provider-neutral). |
| Production secrets | `JWT_SECRET`, `OTP_SECRET`, `DB_PASSWORD`, `FAST2SMS_API_KEY`, `MODERATION_SERVICE_KEY`, vault escrow public key: generate on the server, never in git. |
| Managed MySQL with TLS | The guard requires `sslMode`. |
| Privacy policy + Play Data safety | Draft in `docs/RELEASE_PRIVACY_PLAY.md`; needs hosting at a public URL and your legal review. |
| Account-deletion web link | Play requires a URL where a person can request deletion without the app; needs hosting (the in-app flow exists: 7-day grace). |
| Play declarations | `SEND_SMS` ("physical safety / emergency alerts" exception form), foreground-service types location + microphone, content rating, target audience 18+. |
| Trademark / domain / store-name checks for "IRL" | Unchanged from D1. |
| Spring Boot 4.x upgrade | Its own phase (3.5.16 has no patch left). |

## 6. Results (2026-11-08)

| Suite | Baseline | Now |
|---|---|---|
| Android `testDebugUnitTest` (forced re-run) | 998 / 0 | **1029 / 0** (+15 sealed/memory-only token, +4 empty-token restart state, +6 origin, +6 release guards) |
| Android lint (`lintDebug`) | 0 errors / 105 warnings | **0 errors / 105 warnings** |
| Backend `mvn test` | 883 / 0 (2 skipped) | **891 / 0** (2 skipped; +8 `ProductionGuardTest`) |
| Python `unittest` | 83 / 0 | **83 OK** (run with `PYTHONPATH=. python -s -m unittest discover -s tests` from `moderation-service/`; `-I` as in its README cannot import `tests` here) |
| `assembleRelease` / `assembleReleaseCheck` | n/a | built again after J-8 (forced re-run, `lintVitalRelease` passed); release is **unsigned** (no keystore supplied) |
| Minified app on the emulator | n/a | walk in §3: **manually verified (partial)**; two release-only bugs found and fixed (launch crash; lost server error texts) |
| Gradle guards, failure mode | n/a | `preReleaseBuild -Prelease.api.base.url=http://example.com` FAILS with the https message; with fake `IRL_KEYSTORE_*` values and the placeholder URL it FAILS ("A SIGNED release must not use the placeholder server"); the default passes |
| Backend `prod` profile, failure mode | n/a | `SPRING_PROFILES_ACTIVE=prod mvn spring-boot:run` with the dev `.env` REFUSES to start and lists all five problems (OTP provider, OTP secret, DB TLS, both relative folders) |

Status vocabulary: items 1-4, 6-9 of §1: **implemented, compiled, tested**. R8: **manually verified, partial**. Signed release,
real HTTPS host, real SMS, Play upload, the A23 with the 17J build: **not verified / not done**. The Galaxy A23 was connected
but was **not touched** by this phase.

### J-8 follow-up run (2026-11-08)
`testDebugUnitTest lintDebug assembleRelease assembleReleaseCheck --rerun-tasks`: BUILD SUCCESSFUL, **1029 tests, 0 failures**,
lint **0 errors / 105 warnings** (unchanged). One earlier forced run of the same command reported BUILD FAILED; its output was
lost (piped through `tail`) and the cause was NOT identified; the same command was then repeated twice (once with the full log
kept) and passed both times. Backend and Python were not touched by J-8 and were not re-run. The J-8 memory-only path is
**tested (JVM, fake Keystore), not verified on a device**.

## 7. Git

Nothing committed or pushed (as instructed). New files: `BackendOrigin.kt`, `network_security_config.xml`,
`SealedAccessTokenTest.kt`, `AuthInterceptorOriginTest.kt`, `Phase17JReleaseGuardsTest.kt`, `ProductionGuard.java`,
`application-prod.properties`, `ProductionGuardTest.java`, `PHASE17J.md`, `RELEASE_PRIVACY_PLAY.md`.
