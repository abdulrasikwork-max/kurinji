# Phase 5: Emergency Evidence Vault

Written 2026-10-20. **Phase 5 is implementation-complete** (status words as in `CLAUDE.md` §3: implemented ·
compiled · tested · emulator-verified). Real phone (Samsung Galaxy A23): **partially verified**, see §11.5.1;
the remaining real-phone checks are **DEFERRED / NOT YET VERIFIED** by the user's decision (list in §11.5.1). A hardening pass on the same day (V10, vault
lock, server re-check, deletion guard, background uploads…) is described in **§10**; it changes some rules below
(deletion, "remove from phone", limitations), and §10 wins where they differ.

## 1. What it does (one paragraph)

During an ACTIVE SOS the person can record **audio** (in the app), or take a **photo / video** (with the phone's own
camera app). Every item is **encrypted on the phone immediately** with its own random key, and that key is protected by
the Android Keystore. The plaintext is deleted, both SHA-256 fingerprints are recorded, and the item is uploaded in
resumable pieces to the person's private vault on the server. The server **only ever receives ciphertext**. It
**verifies the hash** before it marks the item SEALED, and it writes every step to a **hash-chained chain-of-custody
ledger**. If the server publishes a vault recovery key, the phone also seals a copy of the item key for that RSA key.
The private half is kept **offline**, so evidence can be recovered if the phone is destroyed, while neither the running
server nor a database thief can open it. Deleting needs the account password and is refused while the emergency is
active. Recording is never automatic and never hidden.

## 2. Design decisions (and why)

| Decision | Why |
|---|---|
| Evidence only during an ACTIVE emergency, started by a tap; audio visible (notification + Android mic indicator); auto-stop after 60 min | "No continuous hidden recording". Consent and battery: restarting is a conscious tap |
| Audio in **30-second parts**, each sealed and queued the moment it closes | A snatched or smashed phone loses at most the part being recorded; uploads start during the incident |
| **AAC in ADTS** (not MP4) | ADTS has no index at the end: a part cut off by a crash is still playable, so crash recovery can seal it (verified: force-stop, then a 12-second part recovered) |
| Photo / video through the **camera app** (`TakePicture` / `CaptureVideo`, FileProvider URI into private staging) | No `CAMERA` permission and no new library (CameraX was not approved). The camera app sees what it records (it is the camera); some camera apps may keep a gallery copy, which is disclosed |
| **Per-item 256-bit key**, SSEV1 format = AES-256-GCM in 64 KiB segments ("STREAM": index + last flag in the nonce, header as AAD) | Streams large videos with a small buffer. Detects bit flips, re-ordering and truncation. Deleting one key destroys one item (crypto-shredding) |
| Item key wrapped by an **Android Keystore** AES-GCM key (StrongBox when available), **not** bound to the screen lock | Hardware-protected and non-exportable. It must work with the phone locked in a pocket. Trade-off: whoever can unlock the phone and open the app can view evidence (the screen says so; FLAG_SECURE blocks screenshots) |
| **Escrow**: item key also sealed with RSA-3072 OAEP-SHA256 for the vault recovery key; **the server holds only the public half** | Without it, a destroyed phone means unreadable evidence. With a server-held private key, a breach would expose everything. The offline key gives recoverability without server-side readability. `VaultKeyTool decrypt` proves it (tested + run live) |
| Optional **key pin** (`evidence.vault.key.id` in local.properties) | A compromised server cannot substitute its own escrow key |
| Server **verifies SHA-256 over every byte** before SEALED; mismatch → bytes discarded, `INTEGRITY_FAILED` in custody, phone re-uploads; if the phone's own file no longer matches its hash, it is kept and not retried | "Verified" is a server fact, not a phone claim |
| **Hash-chained custody ledger** per person (DECLARED, SEALED, INTEGRITY_FAILED, DOWNLOADED, DELETED) | Editing, deleting or re-ordering any past entry is detected (`chainIntact=false`, `brokenAtSequence`). Downloads are logged, so access is auditable. It cannot stop someone rewriting the WHOLE chain; noting the latest entry hash outside the system pins history |
| **Ciphertext files on disk**, random UUID names, outside the DB; start-up reconciliation (orphans older than 1 h removed; SEALED rows without files reported) | No BLOBs in MySQL; no user data in file names; no orphaned ciphertext after failed transactions |
| **Delete everywhere** = password re-check by the server + refused while ACTIVE + tombstone kept + escrow copy erased + local key destroyed. Never-uploaded items are declared first so the server still checks | Protects against forced or panic deletion by someone holding an unlocked phone. The custody record shows that something existed and was deleted |
| "Remove from this phone" (only after server SEALED) and "Bring back" (hash-checked) | Free space / reduce what is on the phone without losing evidence |
| No `user_id` on evidence items (ownership through the emergency); all lookups owner-filtered → foreign = 404 | Same pattern as V4 locations |
| `sequence_no` NOT unique on the server | Two phones of one person (or a reinstall) may record during the same emergency |
| Upload in the application scope (like 4A locations), not WorkManager | WorkManager would be a new dependency; uploads resume on next start / connectivity. Documented limitation |
| ONE foreground service, types = location ∪ microphone as needed | Keeps the Phase 4 "one service" decision; recording survives screen-off (verified: types 0x88) |

## 3. What changed

**Backend (`evidence` package, V9):**
- `evidence_items` + `evidence_custody_events` (CHECKs tie status to columns, lower-case hex hashes, sizes, capture
  order, tombstone rules; cascades; verified on MySQL §6).
- `EvidenceService`, `EvidenceStorage`, `CustodyLedger`, `VaultKey`, `EvidenceStorageReconciler`,
  `EvidenceController`, `EvidenceProperties`.
- Offline `tools/VaultKeyTool` + `SsevFormat`.
- The context packet OWNER projection gains an `evidence` count block. The SAFETY_CIRCLE projection has none.
- `UserRepository.findByIdForUpdate`.

**Android:**
- `core/crypto`: `EvidenceCipher`, `AndroidKeystoreKeyWrapper`, `EscrowKey`.
- `feature/evidence`:
  - domain: models, `RecordingPolicy`;
  - data: `EvidenceFiles`, index, `RemoteEvidenceRepository`, API;
  - capture: `EvidenceRecorder`, `MediaRecorderSegmentWriter`;
  - session: `EvidenceVault`;
  - ui: SOS Evidence card, Evidence Vault screen with in-app viewers.
- `SafetyTrackingService` foreground types.
- Manifest: `RECORD_AUDIO`, `FOREGROUND_SERVICE_MICROPHONE`, FileProvider.
- Emergency details: an evidence line plus "Open Evidence Vault".
- Privacy policy and About texts updated.

## 4. API

See `docs/API.md` "Phase 5 endpoints". Summary:
- `GET /api/evidence/vault-key`
- `POST|GET /api/emergencies/{id}/evidence`
- `GET /api/emergencies/{id}/evidence/custody`
- `GET /api/evidence/{id}`
- `PUT /api/evidence/{id}/content?offset=N` (octet-stream, ≤ 1 MiB)
- `POST /api/evidence/{id}/seal`
- `GET /api/evidence/{id}/content`
- `POST /api/evidence/{id}/delete` `{password}`

Settings: `app.evidence.storage-dir` (`EVIDENCE_STORAGE_DIR`, default `evidence-store`, git-ignored), `max-item-bytes`
200 MiB, `chunk-bytes` 1 MiB, `user-quota-bytes` 2 GiB, `max-items-per-emergency` 500, `clock-tolerance` 5m,
`vault-public-key` (`EVIDENCE_VAULT_PUBLIC_KEY`, empty = no escrow).

**Setting up escrow (operator, once):**
```
cd backend && mvn -q compile
java -cp target/classes com.safesphere.backend.evidence.tools.VaultKeyTool generate D:\offline\vault-private.pem
```
Put the printed `EVIDENCE_VAULT_PUBLIC_KEY=...` in `backend/.env`. Store the private key OFF the server. Optionally put
the printed key id in `android/local.properties` as `evidence.vault.key.id=`. Recovery of one item:
`VaultKeyTool decrypt <private.pem> <escrow_wrapped_key base64 from evidence_items> <downloaded .ssev> <out>`.
**The current dev `.env` has no vault key**, so uploads there are not escrowed until you add one.

## 5. Lifecycle

`capture (staging, plaintext, private)` → `sealed on phone` → `UPLOADING (declared, resumable)` →
`SEALED (server-verified)` → optionally `removed from phone` ↔ `brought back` → `DELETED` (tombstone).
- **Crash:** staged captures are sealed on the next start.
- **Viewing:** decrypted files live in `cache/evidence-view` only while a viewer is open, are deleted on close or
  when the screen stops, and are swept at start.
- **Logout:** recording stops, uploads pause, and items stay encrypted and invisible to other accounts. They resume
  when the same person signs in.

## 6. Verification record (2026-10-20)

| Check | Result |
|---|---|
| `mvn test` | **147 tests, 0 failures**, 2 skipped (opt-in MySQL timestamp tests); 26 new: `EvidenceApiIntegrationTest` 18, `SsevFormatTest` 6, `CustodyLedgerHashTest` 2 |
| `gradlew testDebugUnitTest` | **303 tests, 0 failures** (261 before; 42 new: `EvidenceCipherTest` 9, `EvidenceVaultTest` 21, `EvidenceRecorderTest` 9, `EvidenceScreensTest` 3) |
| `assembleDebug` + `lintDebug` | OK; 0 lint errors, 87 warnings (baseline was 88) |
| Cross-implementation | Fixed SSEV1 vector produced/decrypted identically by Kotlin and Java |
| V9 on MySQL 8.0 | Flyway "now at version v9"; 14 constraint checks in a rolled-back transaction (kind/hash/sizes/sealed/storage/capture-order/escrow CHECKs, unique client_ref, custody action + sequence, cascade) all behaved as designed; nothing left behind |
| Live API smoke test (throwaway accounts) | 21/21 pass: idempotent declare, 3-piece upload, resume 409, server seal, byte-exact download with hash header, other user 404, context counts / no evidence for Safety Circle, delete refused while ACTIVE and with a wrong password, custody intact |
| Escrow recovery (offline key, no phone) | Plaintext hash of the recovered file equals the original |
| Password delete live | DELETED tombstone (hash kept), file gone, escrow key NULL, download 409, custody DECLARED→SEALED→DOWNLOADED→DELETED intact |
| Emulator (separate API 37 AVD, throwaway account; your Pixel 7 emulator untouched) | Mic permission asked on tap; service types `0x88` (location+microphone) while recording, `0x08` after stop; "Recording audio evidence" notification; recording continued with the screen off; 30 s parts ≈185 KB each, SEALED + escrowed on the server; photo via camera app sealed (staging empty, no DCIM copy); vault list + custody line; audio playback (0:30) with the decrypted temp file deleted on close; vault screenshot is black (FLAG_SECURE); force-stop mid-recording → 12 s part recovered and uploaded; recovered device files decrypt offline to valid AAC (FF F1) / JPEG (FF D8) with matching plaintext hashes |
| Clean-up | Throwaway accounts, rows and server files removed (users 2, evidence 0); app uninstalled from the test AVD; backend stopped (it was stopped before) |

Not run: a real phone, a real SIM-less tablet, Android 8 to 13, OEM camera apps, video capture on the emulator, the
delete flow through the app UI (tested via API and unit tests), very large (> 50 MB) videos.

## 7. Known limitations

- **Phone-only, no server-side processing:** ~~uploads run while the app process lives~~ — since §10 a JobScheduler
  job resumes them when the network returns, even after the process died (not after a reboot). No push.
- **Unlocked-phone access:** the Keystore key is not bound to user authentication (it must work while locked). Since
  §10 the vault screen asks for the phone's screen lock; it is an app gate, not encryption.
- **Without a vault key or a recovery passphrase, cloud copies are recoverable only on this phone.** Since §11 the
  person can set up a recovery passphrase and restore on a new phone themselves; the operator's escrow key stays the
  fallback when the passphrase is forgotten AND every phone holding the recovery key is lost.
- **Not strong against an attacker at capture time:** the camera app handles plaintext photos/videos (it is the camera),
  and may keep its own copy. Plaintext is deleted with a normal file delete (flash storage cannot guarantee overwrite);
  it lives only in app-private storage and for seconds.
- **Audio gaps:** between 30-second parts there is a gap of a fraction of a second (stop/start of the recorder); parts
  can be up to ~31 s (1 s check interval).
- **Deletion password brute force:** since §10, 5 wrong passwords in 15 minutes lock deletion (429), and each is
  recorded. Login itself still has no rate limit.
- **Custody chain:** the server detects partial edits; since §10 the phone's custody anchor also detects a complete
  rewrite of what that phone has seen.
- Deleting an emergency row (only possible by deleting the account) also deletes its custody rows.
- **Capture time is the phone's clock** (checked within ±5 min of the emergency); `received_at` / `sealed_at` are
  server time.
- Videos above 200 MB are refused (camera apps are asked to stop at 10 min / 190 MB; some ignore it); such an item stays
  on the phone, marked "the server did not accept it".
- Google Play: `RECORD_AUDIO` + `FOREGROUND_SERVICE_MICROPHONE` need a foreground-service declaration with a video of the
  user-initiated recording.

## 8. Manual verification checklist (real phone)

1. Start SOS → Evidence card visible → **Record audio** asks for the microphone with the card's reason; "Don't allow"
   records nothing and says so; a later tap after "Don't ask again" opens Settings.
2. Allow → notification "Recording audio evidence" + Android's green microphone indicator; the timer runs; "parts saved"
   counts up every ~30 s.
3. Screen off for 2 minutes → recording continues; parts keep uploading (vault shows "Verified").
4. Airplane mode while recording → "No connection… safe on this phone"; back online → items become Verified.
5. Stop recording; tap **Photo** and **Video** → the camera app opens; after saving, a toast says it was encrypted; the
   items appear in the vault. Cancel in the camera app → nothing added.
6. Kill the app from recents while recording, reopen → the last part appears in the vault.
7. Vault: Open audio / photo / video works; a screenshot is black; leaving the app closes the viewer.
8. **Check integrity** → "On this phone: intact … In your vault: intact"; **Remove from this phone** (only for
   Verified items; the server re-checks its copy first) → "removed from this phone"; **Bring back** → playable again.
9. **Delete everywhere** while the SOS is active → refused; right after "I'm safe" → "only … 24 hours after the
   emergency ended"; 24 h later: wrong password → refused (5 in a row → locked for 15 minutes); right password →
   "Deleted everywhere"; the custody line still says intact.
10. Log out → log in as another account on the same phone → no evidence of the first account is visible.
11. Emergency details screen shows "N evidence items in the vault (M verified)" and opens the vault.
12. Lock screen while recording: no evidence content or location is shown.
13. (§10) Vault with a screen lock set: the vault asks for fingerprint / PIN; "Cancel" leaves only "Your Evidence Vault
    is locked"; press Home and come back → asked again. Without a screen lock: the vault opens with the warning.
14. (§10) Airplane mode, record a part, swipe SafeSphere away from recents (do NOT use "Force stop"), switch airplane
    mode off without opening the app → within a minute or two the item is Verified in the vault (on a real phone the
    OEM battery manager may delay or block this: note the brand).
15. (§10) Open the vault twice: the custody line says "Unchanged since this phone first checked it (…)".

## 9. Viva notes

- *Why can't the server read the evidence?* It never gets a key: item keys are sealed by the phone's Keystore, and
  optionally by an RSA key whose private half is offline.
- *How do you prove the file was not changed?* SHA-256 recorded on the phone at sealing, re-computed by the server over
  every received byte before SEALED, re-checkable on the phone at any time. The custody ledger records each step in a
  hash chain.
- *What if the phone is smashed mid-recording?* Every closed 30-second part is already encrypted and usually uploaded;
  with escrow it can be recovered without the phone.
- *Why ADTS?* An MP4 needs its index written at the end; a killed recording would be unplayable.
- *Why not CameraX?* New dependency not approved, and the camera-app intent needs no camera permission at all.
- *Why a password for deletion?* An abuser holding the unlocked phone could otherwise destroy the evidence.

## 10. Hardening pass (2026-10-20)

Labels: **IMPLEMENTED** (in the code) · **VERIFIED** (actually executed: tests / MySQL / API / emulator, as stated) ·
**NOT VERIFIED** · **LIMITATION** · **REAL DEVICE REQUIRED**.

### 10.1 What changed and why

| # | Change | Why | Status |
|---|---|---|---|
| 1 | **Owner check on every vault action** (open, check, remove, bring back, retry, delete). Before, the actions only looked the item up by reference; the Keystore key is shared by every account on the phone, so another signed-in person who obtained a reference could have decrypted it | Cross-account isolation must not depend on the screen only showing your own rows | IMPLEMENTED · VERIFIED (unit tests) |
| 2 | **Vault viewing lock**: the vault screen needs the phone's screen lock (BiometricPrompt `BIOMETRIC_STRONG \| DEVICE_CREDENTIAL` on Android 11+, device credential on 10, Keyguard confirmation on 8–9; platform APIs, no new library). Locks again when the screen is left, the screen goes off, or the account changes. While locked the ViewModel produces no rows and refuses every action. No screen lock on the phone → vault opens with a warning and a "Set a screen lock" button | "Anyone who unlocks the phone can view evidence" was the main open gap | IMPLEMENTED · VERIFIED (unit tests; emulator: prompt shown, cancel stays locked, PIN unlocks, leaving re-locks, no-lock warning) |
| 3 | **Deletion guard (server)**: refused for 24 h after the emergency ended; 5 wrong passwords in 15 min → `429` lock (even the right password); every wrong password recorded in custody as `DELETE_REFUSED` (committed although the request fails). V10 adds the action values and an index | Forced deletion right after a coerced "I'm safe"; online password guessing; an attempt to destroy evidence now leaves a trace | IMPLEMENTED · VERIFIED (integration tests incl. lock expiry; live API on MySQL) |
| 4 | **Server re-check of the stored copy** `POST /api/evidence/{id}/verify`: VERIFIED, or INTEGRITY_FAILED + back to UPLOADING so the phone repairs it. "Remove from this phone" calls it first and refuses if the vault copy is bad. "Check integrity" now reports both copies | A copy fine at seal time can rot or be tampered with on the server's disk later; the phone must never delete the last good copy | IMPLEMENTED · VERIFIED (tests; live API; emulator: damaged server file → "uploading again" → re-sealed) |
| 5 | **Background uploads via JobScheduler** (`EvidenceUploadJobService`, platform API, network constraint, back-off; not exported, `BIND_JOB_SERVICE`). Scheduled whenever the signed-in person has evidence waiting; the job keeps the process alive until nothing is pending (≤ 8 min, then again) | Uploads used to stop when the process died; resumed only on the next app start | IMPLEMENTED · VERIFIED (tests; emulator: offline, process killed, network on → process restarted by the job, both parts SEALED without opening the app) |
| 6 | **Crash-safe index**: fsync'd temp file, previous version kept as `index.json.bak`, read falls back tmp → bak; damaged file kept aside. Sealed ciphertext fsync'd before the plaintext is deleted; downloads fsync'd | The index is the only place the item keys live: one torn write could have made every item unreadable | IMPLEMENTED · VERIFIED (unit tests simulate a damaged index and a crash between renames) |
| 7 | **Plaintext wiping** (`PlaintextWipe`): staged captures and decrypted viewing copies are overwritten with zeros, flushed, then deleted | Reduce what a later forensic read of free space could find | IMPLEMENTED · VERIFIED (unit tests) · LIMITATION: best effort on flash (wear levelling); the real protection remains app-private storage + Android file-based encryption + seconds-long lifetime |
| 8 | **Custody anchor on the phone** (`CustodyAnchoring`, pure): per person + emergency the phone remembers the newest `(sequenceNo, entryHash)` it saw and checks it is still there next time: "Unchanged since …" or a warning | The server can only detect partial edits; a full rewrite of the chain by someone with database access is now detected by the phone | IMPLEMENTED · VERIFIED (unit tests; emulator shows "first check" then "unchanged since") |
| 9 | **Retry a refused upload** (menu "Try uploading again") | A refusal (e.g. a temporary server rule) was permanent | IMPLEMENTED · VERIFIED (unit tests) |
| 10 | **Deleted keys leave no copy**: after "delete everywhere" the index is written twice so `index.json.bak` holds no sealed key either | Found in the security review of #6: the backup would otherwise have kept a shredded key | IMPLEMENTED · VERIFIED (unit test) |
| 11 | **VaultKeyTool**: private key now written ENCRYPTED (PKCS#8 PBES2: PBKDF2-HMAC-SHA256 600 000 iterations + AES-256-CBC; passphrase ≥ 12 chars from the console or `VAULT_KEY_PASSPHRASE`; `--no-passphrase` possible); new `check <private.pem> [public-key]` proves the private key belongs to the deployed public key | A plaintext PEM on a USB stick was the weakest link of escrow; a mismatched pair would make every escrow copy silently useless | IMPLEMENTED · VERIFIED (unit tests: round trip, wrong passphrase, short passphrase, match / mismatch) |
| 12 | **Honest texts**: the SOS notice no longer says "never records audio" (untrue since Phase 5); the camera toast mentions that some camera apps keep their own Gallery copy; delete dialog explains the 24 h rule and the lock | Honesty rules (`CLAUDE.md` §9) | IMPLEMENTED |
| 13 | Android `ApiError.TOO_MANY_ATTEMPTS` (429, our own message shown); retried with back-off by the location, circle-alert and evidence upload loops | New status code from #3 | IMPLEMENTED · VERIFIED (mapping test) |

Recovery-key setup (operator): `VaultKeyTool generate D:\offline\vault-private.pem` (asks a passphrase), put the printed
`EVIDENCE_VAULT_PUBLIC_KEY=` in `backend/.env`, then run `VaultKeyTool check D:\offline\vault-private.pem` with that
variable set → "OK: the private key matches the vault key …". Repeat `check` in every recovery drill.

### 10.2 Decisions (viva)

- *Why not bind the Keystore key to the screen lock?* Capture must work while the phone is locked in a pocket, and
  Android permanently invalidates auth-bound keys when the screen lock is removed — an abuser forcing that would
  destroy every item key on the phone. So the lock is an access gate (honestly described), not encryption.
- *Why count wrong passwords in the custody ledger instead of a counter table?* The attempts are evidence themselves
  (append-only, hash-chained); counting them needs no new table and survives restarts.
- *Why 24 h after the emergency?* A person can be forced to press "I'm safe"; the delay separates deletion from the
  incident and gives uploads time to finish. Configurable.
- *Why JobScheduler and not WorkManager?* Same OS mechanism, no new dependency. Not persisted across reboot (that
  would need `RECEIVE_BOOT_COMPLETED`).
- *Why re-check before "remove from phone"?* "Verified once" is not "intact now"; the last good copy is never deleted
  on an old fact.

### 10.3 Verification record (actually executed, 2026-10-20)

| Check | Result |
|---|---|
| `mvn test` | **155 tests, 0 failures**, 2 skipped (opt-in MySQL tests). New: `EvidenceApiIntegrationTest` +3 (21), `EvidenceDeletionLockExpiryTest` 1, `VaultKeyToolTest` 4 |
| `gradlew testDebugUnitTest` | **330 tests, 0 failures** (303 before). New: `EvidenceVaultHardeningTest` 16, `CustodyAnchoringTest` 5, `VaultLockTest` 4, `EvidenceVaultTest` +1, `EvidenceScreensTest` +1 |
| `assembleDebug` + `lintDebug` | OK; 0 errors, 87 warnings (unchanged baseline, none in new code) |
| V10 on MySQL 8.0 | Flyway "now at version v10"; timestamp check passed; in a transaction that was rolled back: CHECK lists the 7 actions, VERIFIED + DELETE_REFUSED accepted, an unknown action rejected (ERROR 3819), new index present, lockout count query works; nothing left behind |
| Live API smoke test (throwaway accounts, real MySQL) | **17/17**: verify before seal 409; verify intact; other user 404; no token 401; one flipped byte on disk → intact=false, UPLOADING from 0, download 409; re-upload repairs; delete while ACTIVE 409; right after resolve 409 (24 h); 5 wrong → 409 each; 6th (right password) 429; item still SEALED; custody intact with VERIFIED / INTEGRITY_FAILED / 5 × DELETE_REFUSED; context packet OK; clean-up |
| Emulator (separate API 37 AVD, throwaway account, test PIN; Pixel 7 untouched) | Background job: offline audio parts, process killed (`kill`, not force-stop), network back → job restarted the process, 2 parts SEALED. Vault lock: no screen lock → warning shown; PIN set → system prompt on return, Cancel → locked panel only, PIN → content; custody "Unchanged since this phone first checked it". Integrity: both intact; Remove from phone after server check; damaged server copy → "uploading again" → re-sealed. FLAG_SECURE screenshot black |
| Clean-up | Throwaway accounts, rows, server files removed (users 2, evidence 0, files 0); PIN cleared; app uninstalled from the test AVD; emulator and backend stopped |

**NOT VERIFIED / REAL DEVICE REQUIRED:** fingerprint / face in the prompt (emulator used a PIN); Android 8–10 prompt
paths (only API 37 run); the job on OEM-skinned phones (Xiaomi, Oppo, Vivo, Samsung may delay or block jobs of
swiped-away apps); `VaultKeyTool generate` with an interactive console passphrase (tested through the API, not the
console); the full-rewrite case of the custody anchor (unit-tested; not staged on a real database).

### 10.4 Remaining limitations

- **LIMITATION** Viewing lock is an app gate, not cryptography (see 10.2). Without a screen lock it cannot protect.
- **LIMITATION** Jobs are not persisted across a reboot; uploads then resume when the app is opened.
- **LIMITATION** Plaintext wiping is best effort on flash storage. Damaged-index copies kept for inspection
  (`index.damaged-*.json`) may still hold sealed item keys of items deleted later.
- **LIMITATION** A stolen JWT can be used to lock the owner out of deleting for 15 minutes (fails safe: nothing is
  deleted). Login itself still has no rate limit (Phase 2 behaviour).
- ~~**LIMITATION** No in-app restore on a new phone~~ — added by §11 (recovery passphrase, V11).
- **LIMITATION** The custody anchor lives on this phone only; a new phone starts with "first check".
- **REAL DEVICE REQUIRED** Everything in §8 including items 13–15.

## 11. Restore on a new phone: recovery passphrase (2026-10-20, V11)

Design approved by the user on 2026-10-20 ("option D": two-level key, PBKDF2, suggested 5-word passphrase,
password-gated envelope, recovery-key reset deferred, download on demand). Status: **IMPLEMENTED · COMPILED · TESTED
(unit + integration) · V11 VERIFIED ON MYSQL · LIVE API VERIFIED · EMULATOR-VERIFIED (§11.5, items 16–22); real
phone DEFERRED (not yet verified).**

### 11.1 How it works

```
passphrase --PBKDF2-HMAC-SHA256(16-byte salt, 600 000 iterations)--> KEK        (on the phone only)
KEK --AES-256-GCM, AAD "SSRK1|rkVersion"--> envelope = wrapped recovery key RK   (server: evidence_recovery_envelopes)
RK  --AES-256-GCM, AAD "SSRW1|clientRef|ciphertextSha256"--> item recovery copy  (server: evidence_items.recovery_wrapped_key)
RK on the phone: sealed by the existing Android Keystore key (files/evidence/recovery-keys.json, per account)
```
- **Set up** (vault screen, behind the screen lock): account password (server re-check) + passphrase (suggested five
  EFF words ≈ 64.6 bits, or the person's own). The phone makes RK, sends only the envelope, and keeps RK sealed by the
  Keystore — only AFTER the server accepted it. New items are declared with their recovery copy (works offline: RK is
  local); earlier items get it from the upload loop's back-fill (`PUT /api/evidence/{id}/recovery-key`).
- **Restore** (new phone): account password → envelope; passphrase → RK (wrong passphrase = GCM failure, retried
  locally without asking the server again); every item key opened and re-sealed by THIS phone's Keystore; items appear
  as "Verified in your vault · removed from this phone"; "Bring back" downloads + hash-checks as before (custody
  DOWNLOADED). A copy that fails its tag (damaged or swapped between items) is counted, never imported.
- **Change**: current passphrase (proved against the server envelope) + new one + account password; same RK, new salt.
- **Forgot**: a phone still holding RK sets a new passphrase without the old one (screen lock + account password).
  Forgot AND every such phone lost → only the operator escrow key (if configured). Said on the set-up screen.
- **Delete everywhere**: the server erases `recovery_wrapped_key` with the escrow copy (V11 CHECK); the deleting phone
  shreds as before; other phones shred on their next vault refresh (`EvidenceVault.syncDeletions`, new — before this,
  a SEALED item deleted on another phone kept its key here).

### 11.2 Decisions (viva)

| Decision | Why |
|---|---|
| Two-level key (RK) instead of the passphrase wrapping every item directly | Changing the passphrase re-wraps 60 bytes, not every item; capture wraps with RK offline, never prompting during an emergency; a later KDF upgrade touches only the envelope |
| Passphrase separate from the account password, and refused if equal / contained | The server sees the account password at every login; deriving from it would let the server read evidence |
| PBKDF2-HMAC-SHA256, 600 000 iterations, own implementation over NFC-normalised UTF-8 | No new library (Argon2 would need one); explicit encoding identical on every provider; checked against RFC 7914 vectors and the JDK |
| GCM AADs bind the envelope to its RK version and each item copy to its clientRef + ciphertext hash | A server cannot swap copies between items or versions without detection |
| Envelope only with the account password; 5 wrong in 15 min → 429; "last fetched on …" shown in the vault | A stolen token alone gives no guessing material; a takeover leaves a visible trace |
| Item recovery copy write-once per RK version | A stolen token cannot overwrite good copies with garbage |
| A new RK is stored on the phone only after the server accepted its envelope | A refused set-up (e.g. already set up elsewhere) never leaves a phone wrapping for a key nobody else has |
| Restore imports keys + metadata; content on "Bring back" | No 2 GiB bulk download; each download is logged in custody |
| RK reset (rotation) deferred; `rk_version` columns exist, server refuses changing it | Keeps every item copy openable by the one RK; no migration needed later |

### 11.3 Could the server ever read evidence?

Not without the passphrase. It stores ciphertext, RSA escrow copies (offline private key), RK-wrapped item keys and
the passphrase envelope. Its only path is **guessing the passphrase offline** from the envelope (anyone with the
database can try). Estimate (not measured): one GPU ≈ 15 000 PBKDF2-600k guesses/s → a 5-word suggestion ≈ 2^64.6 /
15 000 s ≈ 4·10⁷ years; a typical human password: seconds to days. Hence the rules: ≥ 15 characters, not the name /
email name part, not the account password, not on the 10k common list, not repetitive. Residual risks: a malicious app
update; a person later changing the account password to equal the passphrase (cannot be detected); database backups
made before a deletion still hold that item's copy (same as escrow).

### 11.4 What changed

- Backend: V11 (`evidence_recovery_envelopes`, `evidence_recovery_failures`, `evidence_items.recovery_rk_version`,
  `recovery_wrapped_key`, shredded CHECK extended); `RecoveryService`, `RecoveryController`, entities/repositories;
  declaration accepts an optional recovery copy; `EvidenceResponse.recoverable`; deletion erases the copy.
- Android: `core/crypto/RecoveryKeyCrypto`; `data/RecoveryKeyStore`; `domain/PassphrasePolicy` + `PassphraseGenerator`;
  `session/EvidenceRecovery`; `EvidenceVault` (recovery copy at declaration, back-fill, `importRestored`,
  `syncDeletions`); `ui/EvidenceRecoveryViewModel` + vault-screen section + `dialog_recovery_passphrase.xml`
  (FLAG_SECURE, no autofill, fields cleared); assets `eff_large_wordlist.txt` (CC BY 3.0),
  `common_passwords_10k.txt` (SecLists, MIT), `THIRD_PARTY_NOTICES.txt`; About / privacy texts.

### 11.5 Verification record (actually executed, 2026-10-20)

| Check | Result |
|---|---|
| `mvn test` | **164 tests, 0 failures**, 2 skipped (opt-in MySQL). New: `EvidenceRecoveryApiIntegrationTest` 9 (full round trip passphrase → envelope → RK → item key → SSEV1 plaintext in plain JCE; password + lockout; pinned formats; optimistic version; write-once attach; delete erases the copy; restore never lists deleted / foreign items; 401s) |
| `gradlew testDebugUnitTest` | **354 tests, 0 failures** (330 before). New: `RecoveryKeyCryptoTest` 6 (RFC 7914 vectors, JDK equality, NFC, bindings), `PassphrasePolicyTest` 8, `EvidenceRecoveryTest` 10 (set-up + back-fill, restore on a second phone with its own Keystore and Bring back, wrong passphrase, refused set-up keeps no key, change, forgot, recovery copy refused ≠ upload refused, account isolation, cross-phone deletion shreds incl. `.bak`, damaged copy counted) |
| `assembleDebug` + `lintDebug` | OK; 0 errors, 87 warnings (baseline unchanged) |
| V11 on MySQL 8.0 | Flyway "now at version v11"; 15 checks in a rolled-back transaction: one envelope per user (1062), iterations / kdf / salt / wrap length / version CHECKs (3819), item both-or-none and 60-byte CHECKs, DELETED with a recovery copy refused, shredded tombstone accepted, cascades from users; nothing left behind |
| Live API smoke test (throwaway account, real MySQL) | **13/13**: status, 401, wrong password 409, weak iterations 400, set v1, second first-set-up 409, fetch returns the same bytes, status shows lastDownloadedAt without key material, change → v2, declare with copy → recoverable (copy never returned), attach wrong version 409, 1 + 4 wrong passwords → 429 with the right one. Clean-up: accounts, rows and server file removed; backend stopped |

**Emulator run (same day, separate API 37 AVD `Medium_Phone_API_37.0`, throwaway account, no screen lock; Pixel 7
untouched), §11.6 items 16–22 driven through the UI with adb:**

| # | Result |
|---|---|
| 16 | Suggestion of 5 words, "Another one" changes it; refused with the right reason: 10 characters (too short), passphrase containing the name (personal), mismatch, wrong account password ("Password is incorrect"). The "same as account password" case hit the name rule first (the test password contained the name); that rule is unit-tested. Set-up with "Use this" → saved |
| 17 | Two parts recorded BEFORE set-up were back-filled ("1 of 2" → "2 of 2"); two parts recorded after → "4 of 4"; MySQL rows: rk_version 1, 60-byte copies |
| 18 | App data cleared (new Keystore key) → items "recorded on another phone", "Restore my evidence here"; wrong passphrase → "does not open"; right → "4 items restored"; Bring back → "Verified · also on this phone"; audio opened (0:30). The decrypted file's SHA-256 on the new phone equals the plaintext hash recorded at capture on the old phone; the file was gone after closing |
| 19 | "Your recovery data was last fetched … 2:09 PM" shown |
| 20 | Change: wrong current → refused; right → "changed" (envelope v2, rk v1). Second new phone (uninstall + install): old passphrase → refused, new → 4 restored |
| 21 | Item deleted everywhere from "another phone" (same account via the API, after moving the emergency 25 h back in the DB): server copy erased; reopening the vault on the phone → "Deleted everywhere", key gone from `index.json` (no `.bak` held it) |
| 22 | Dialog screenshot is black (FLAG_SECURE); Home → back: dialog gone |

**Found and fixed during the run:** (1) after any recovery action the buttons stayed disabled (`refresh()` wrote back a
state captured while busy) → now updates the latest state; regression test `EvidenceRecoveryViewModelTest` (3 tests,
Main unconfined like Android's Main.immediate; written for the bug, not run against the old code). (2) The SOS
hint "no vault recovery key: … only on this phone" was untrue once a passphrase exists → reworded. After the fixes:
**357 Android tests, 0 failures**; lint 0 errors / 87 warnings.

**Emulator notes:** uiautomator (shell instrumentation) can read password-field text — Android behaviour, same as the
login screen. Once `pm clear` left the emulator's /sdcard unusable until a reboot (emulator issue, not the app).
Runtime permissions were pre-granted with `adb install -g`.
Clean-up: app uninstalled from the AVD, throwaway account + rows + 3 server files removed, emulator and backend stopped.

**NOT VERIFIED / REAL DEVICE REQUIRED:** PBKDF2 600k time on a real (low-end) phone (on the emulator set-up and
restore each finished within the ~6–8 s the script waited, not timed precisely); restore across two physical phones;
fingerprint / PIN gate in front of the section (AVD had no screen lock); Android 8–10.

### 11.5.1 Real-phone record: Samsung Galaxy A23 (SM-A236E, Android 14, One UI), started 2026-10-20

Debug build with `api.base.url=http://127.0.0.1:8080/` (git-ignored `local.properties`) over `adb reverse tcp:8080`;
installed for user 0 with no pre-granted permissions; the tester's own throwaway account. PASS only for what the
tester actually observed on the phone; PC-side checks are listed as supporting evidence, not as PASS.

| Test | Result |
|---|---|
| 1 Microphone permission flow (§8.1) | **PASS.** Evidence card shown on the SOS screen (observed). 1st "Don't allow": a microphone-related message was shown; **exact wording not remembered** (not verified). 2nd tap: Android asked again; "Don't allow" (observed). No recording notification and no green microphone dot (observed). 3rd tap: no system prompt, SafeSphere's Settings page opened (observed). Supporting (PC): SOS #24 ACTIVE with 0 evidence items; RECORD_AUDIO `granted=false, USER_FIXED`; evidence and capture folders empty |
| 2 Recording visible, parts saved (§8.2) **[SAMSUNG: One UI indicator]** | **PASS** (for what was observed). Microphone granted in Settings; green microphone dot shown while recording and gone after Stop (observed). Notification title: **not noted** (not verified). Stopped at ≈ 1:10 with "2 parts saved"; afterwards "On this phone (encrypted): 3 · Verified in your vault: 3 · Waiting to upload: 0" (observed; the 3rd part is the one closed by Stop). Supporting (PC): items 22–24 of SOS #24, one recording, sequence 0–2, 31.2 s + 30.3 s + 15.3 s, 184 443 / 184 621 / 92 068 bytes, all SEALED with received = declared size; the server re-hashed each stored file = declared SHA-256; custody DECLARED/SEALED × 3 (seq 1–6); not escrowed (dev server has no vault key) and no recovery copy (no passphrase yet); phone: 3 `.ssev` files with matching sizes, staging folder empty; tracking service now type 0x08 (location only: the microphone type ended with the recording) |
| 3 Screen off (§8.3) **[SAMSUNG]**, attempt 1 | **NOT VERIFIED: no recording took place.** Observed by the tester: location permission was asked and granted; a green dot when the screen came back on; no battery / "app sleeping" message; timer and parts count **not noted**; the line quoted after stopping was the hint text (app wording: "This server has no operator recovery key: unless you set up a recovery passphrase in the Evidence Vault, uploaded evidence can only be opened on this phone."), the summary line itself **not noted**. PC/phone evidence: Android app-ops show SafeSphere's **last microphone access at 14:54:09** (the end of Test 2) and none afterwards; no new evidence on the server or the phone (index still 3 items, staging empty); screen-off events 15:03:41 → 15:04:31 (≈ 50 s, not 2 min); location (GPS) was used by the foreground service while the screen was off. Most likely the Record tap went to the location permission prompt, so recording never started (not confirmed). Not counted as a failure of the app; to be repeated |
| 3 Screen off (§8.3) **[SAMSUNG]**, attempt 2 | **PASS, except "2 continuous minutes": NOT VERIFIED.** Observed by the tester: timer kept running while the screen was off; green dot before and during; 7 new parts saved; final "On this phone (encrypted): 11 · Verified in your vault: 11 · Waiting to upload: 0". Supporting (PC/phone): one recording (ref 16432778…), parts seq 3–10 = 7 × ≈ 30.4–31.0 s + 3.3 s closed by Stop (3 + 8 = 11, matching the screen), 15:08:41.8 → 15:12:19.0 IST, gaps between parts 2–18 ms; all 8 SEALED, server re-hash = declared SHA-256, custody DECLARED/SEALED × 8; all 8 `.ssev` files on the phone hash to the same value; index 11 items, all SEALED; staging and viewing folders empty. Screen-off spans (Android event log): 15:09:27.8–15:10:57.1 (≈ 89 s), 15:11:04–15:11:19 (15 s), 15:11:25–15:11:58 (33 s): ≈ 137 s off in total but the longest continuous span is ≈ 89 s, so the full 2 minutes was **not** reached. Parts seq 5 (15:09:43–15:10:13) and 6 (15:10:13–15:10:43) were captured entirely with the screen off; parts 3 and 4 were sealed on the server at 15:10:27 and 15:10:58, i.e. uploads continued with the screen off. App-ops: microphone used in state `fgsvc` (foreground service, app not on top) and `top`. Samsung battery: standby bucket 10 (ACTIVE), RUN_ANY_IN_BACKGROUND allow, Battery Saver off, no message seen → nothing interfered **in this run**; One UI "Sleeping apps" list itself not inspected (NOT VERIFIED). Observation: each part appeared sealed ≈ 75 s after it closed: explained later by the phone clock being 74.6 s behind the server (clock note below Test 6b), so there was no real delay |
| 4 Offline while recording (§8.4), offline phase | Setup: USB port forward removed by the tester's operator (phone → backend confirmed unreachable) + airplane mode on. Observed by the tester (photo taken DURING recording at 2:01): "3 parts saved"; hint "No connection. Evidence is safe on this phone (encrypted) and will upload when you are back online." (exact, from the photo); summary "On this phone (encrypted): 14 · Verified in your vault: 11 · Waiting to upload: 3". The tester reported those numbers as final; the phone index shows the state AFTER Stop: **16 items = 11 SEALED + 5 NOT_UPLOADED** (one recording, seq 11–15: 4 × ≈ 30.4 s + 15.3 s closed by Stop, 15:54:56.5 → 15:57:13.7 IST), so the after-Stop screen would read 16 / 11 / 5. The "14 / 11 / 3" value was the mid-recording screen; the after-Stop summary was **not observed** (not verified). Supporting (PC/phone): server still 11 items for SOS #24 (nothing arrived while offline); all 5 offline `.ssev` files exist, sizes and SHA-256 equal the index; 16 `.ssev` files in total; staging empty; airplane mode = 1 |
| 4 Offline while recording, reconnect phase | Observed by the tester before reconnecting (airplane mode off, port forward still removed): "On this phone (encrypted): 16 · Verified in your vault: 11 · Waiting to upload: 5" (matches the index). Port forward restored at 16:11:12. Supporting (PC/phone): all 5 declared and SEALED on the server between 16:11:14 and 16:11:16 (within ≈ 4 s), received = declared size, server re-hash = declared SHA-256 = phone index hash; custody DECLARED/SEALED × 5; phone index 16 × SEALED; server 16 items for SOS #24. Final on-screen summary after reconnect (observed): "On this phone (encrypted): 16 · Verified in your vault: 16 · Waiting to upload: 0"; the "No connection" hint disappeared (observed) |
| **4 Offline while recording (§8.4): result** | **PASS.** Offline: hint shown (exact text from photo), parts kept only on the phone (server unchanged at 11), encrypted files intact; back online: automatic upload of all 5 within ≈ 4 s, server-verified hashes, screen 16 / 16 / 0, hint gone. One sub-item **not verified**: the summary line immediately after Stop while still offline (only the mid-recording screen "14 / 11 / 3" was seen; the index showed 16 / 11 / 5 after Stop, and the tester saw 16 / 11 / 5 before reconnecting). Test setup note: "offline" = USB port forward removed + airplane mode on |
| 5 Photo / video via camera app (§8.5) **[SAMSUNG Camera]** | **PASS.** Observed by the tester: Samsung Camera opened for Photo and Video; photo → count 17; ≈ 5 s video → count 18; Photo then back out without a picture → count stayed 18; **no copy of the photo or video in Samsung Gallery**. Confirmation message after capture: **not noted** (not verified). Supporting (PC/phone): items 38 PHOTO image/jpeg 5 181 005 bytes and 39 VIDEO video/mp4 12 715 161 bytes (capture window 12.7 s incl. camera-app time), both SEALED with received = declared, server re-hash = declared = phone file hash; custody DECLARED/SEALED × 2; server and phone both 18 items (16 audio, 1 photo, 1 video), all SEALED; no item for the cancelled capture; staging and viewing folders empty; Android MediaStore has **no new media** since 16:12 and no new files in DCIM / Pictures / Movies → the Samsung Camera kept no shared copy on this phone (this camera-app version, via the capture intent) |
| 6 Swiped from Recents while recording (§8.6) **[SAMSUNG One UI recents]** | **PARTIAL.** Observed by the tester: green dot stayed on after swiping SafeSphere away; recording continued; count 18 → 21; reopened and stopped normally. Recording notification while closed: **not noted (not verified)**. Supporting (PC/phone): the app **process was never killed** (same PID 10363, started 14:43:28); Android removed the task at **16:22:27.65** (event log `wm_task_removed`); one recording (ref 3787068d…) seq 18–20: 16:21:42.3–16:22:13.3 (31.0 s), 16:22:13.3–16:22:43.7 (30.4 s, spans the swipe), 16:22:43.7–16:22:57.2 (13.5 s, closed by Stop); gaps 2–4 ms; app-ops: microphone used as foreground service (`fgsvc`, app not on screen) from 16:22:43.8 for 4.9 s, then on screen (`top`) from 16:22:48.7 for 8.5 s (the reopen) → the recording ran ≈ 21 s with no SafeSphere screen and ≈ 30 s after the swipe in total. 3 new `.ssev` files on the phone (21 in all); all 3 SEALED, received = declared; declared = stored = phone SHA-256 for all 3; custody seq 37–42 DECLARED/SEALED per item, in capture order; staging and viewing folders empty. Uploads: declared/sealed 16:23:28, 16:23:58, 16:24:12 (apparent ≈ 75 s gap = the phone clock offset, clock note below Test 6b). **Why PARTIAL:** on One UI a swipe from Recents only removed the task; the foreground service kept the process alive, so the crash-recovery path that §8.6 is meant to exercise (interrupted part sealed on the next start) did **not** run and is **not verified** on this phone. The observed behaviour itself is correct (visible, continuous recording) |
| 6b Force stop while recording **[SAMSUNG One UI Force stop]**, attempt 1 | **NOT VERIFIED: no recording was running when the app was force-stopped.** Observed by the tester: force-stopped; green dot off; notification disappeared. Supporting (PC/phone): Force stop really killed the process (event log 16:30:44.853 `am_kill … stop com.safesphere.app`, `stopped=true`, service stopped, task removed); but Android app-ops show SafeSphere's **last microphone use at 16:22:48** (Test 6) and none afterwards; the staging folder was empty and the phone index / server still held 21 items before reopening. So recording had not started (or had already stopped) before the force stop; the recovery path was not exercised. Not counted as an app failure; repeated below |
| 6b Force stop while recording **[SAMSUNG One UI Force stop]**, attempt 2 | **PASS.** Observed by the tester: recording at 0:31 with "1 part saved"; force-stopped from Settings after the operator's "go"; after reopening, "On this phone (encrypted): 26 · Verified in your vault: 26 · Waiting to upload: 0". Notification / green dot at this attempt's stop: **not noted (not verified)**. Supporting (PC/phone): before the stop, microphone in use (app-ops running since 16:35:49), service types 0x88, a growing part in staging. Kill confirmed: `am_kill` pid 23754 at 16:37:32.23 (phone clock), `stopped=true`, service stopped. Before reopening: staging held `47a91845…aac` (71 197 bytes, ADTS header FF F1, plaintext SHA-256 ece32173…642eeb4a) + its note; 25 items on phone and server. Reopen: process 25706 started 16:38:39.1 (phone clock). Recovered: item 47, seq 25, 11.8 s (16:37:20.54 → 16:37:32.30, end time from the file), ciphertext 71 245 bytes, SEALED; server's `plaintext_sha256` and the phone index = the staged file's hash (ece32173…, 71 197 bytes) → exactly the interrupted bytes were sealed; declared = stored = phone ciphertext SHA-256; custody seq 43–52 DECLARED/SEALED for items 43–47 in order; 26 items with 26 distinct references on server and phone (no duplicates); staging and viewing folders empty afterwards. In server time the recovered part was sealed ≈ 1.8 s after the restart (see clock note) |

**Clock note (A23):** the phone's clock was measured **74.6 s behind** the PC/server (three samples, ±0.1 s). Capture
times come from the phone, `received_at` / `sealed_at` from the server. This explains the "≈ 75 s between a part
closing and being sealed" noted in Tests 3 and 6: in a common clock each part was sealed within ≈ 0.5 s of closing,
i.e. there is **no** upload delay. It is also within the server's ±5 min capture-time tolerance.

| Test | Result |
|---|---|
| 7 Viewing in the vault (§8.7), step 1 only | **Step 1 observed:** opening the vault showed the **fingerprint** prompt (the A23 has a screen lock); after authenticating, the evidence list was shown. Steps 2–5 (audio playback, black screenshot, viewer closed after Home, photo / video viewers, temporary file deletion) **DEFERRED, not yet verified** |

#### Deferred real-phone checks (decision of the user, 2026-10-20)

Manual A23 testing was paused by the user after Test 7 step 1. The checks below have **not** been performed on a real
phone and are **not** claimed as passed. They are **DEFERRED / NOT YET VERIFIED**. They are expected to work, based on
the automated tests (backend 164, Android 357), the emulator runs (§6, §10.3, §11.5) and the A23 results above, until
real-device testing shows otherwise. No functionality is to be changed just to satisfy them.

| § | Check | Evidence so far (not a real-phone PASS) |
|---|---|---|
| 8.7 | Viewing: audio / photo / video viewers, black screenshot, viewer closed on leaving, temp file deleted (steps 2–5) | Unit tests; emulator (playback, temp cleanup, FLAG_SECURE black screenshot) |
| 8.8 | Check integrity, Remove from this phone (server re-check first), Bring back | Unit + integration tests; emulator (§10.3) |
| 8.9 | Delete everywhere: refused while ACTIVE / within 24 h, wrong password, 5-in-15-min lock, success | Integration tests; live API on MySQL (§6, §10.3) |
| 8.10 | Another account on the same phone sees nothing of the first | Unit tests (`EvidenceVaultHardeningTest`) |
| 8.11 | Emergency details screen evidence line + "Open Evidence Vault" | Emulator (Phase 5 run) |
| 8.12 | Lock screen shows no evidence content or location while recording | Not tested anywhere beyond design review **[SAMSUNG lock screen]** |
| 8.13 | Vault lock: cancel → locked panel, re-lock after Home, no-lock warning **[SAMSUNG fingerprint/PIN]** | Unit tests; emulator with PIN; A23: fingerprint prompt + unlock only (Test 7 step 1) |
| 8.14 | Background upload job after the app is swiped away (not force-stopped) **[SAMSUNG battery management]** | Emulator (§10.3). Note: on the A23 a swipe did not kill the process (Test 6), so this needs a different trigger on Samsung |
| 8.15 | Custody anchor "Unchanged since this phone first checked it" | Unit tests; emulator |
| 11.6 16–22 | Recovery passphrase: set up, back-fill, restore on a new phone, last-fetched notice, change / forgot, cross-phone deletion, secure dialog | Unit + integration tests; V11 on MySQL; live API 13/13; **emulator only** (§11.5). Not run on the A23 |

Also still open on the A23: Test 3's "screen off for 2 continuous minutes" (≈ 89 s reached), Test 6's notification
while swiped away, and Test 6b's notification / dot at the stop (all "not noted").

Summary of the A23 run: **PASS** 1, 2, 3 (except the 2-minute item), 4, 5, 6b · **PARTIAL** 6 (swipe does not kill
the process on One UI; the recovery path was then verified by 6b) · **step 1 only** 7 · **DEFERRED** the rest.

### 11.6 Manual checklist (emulator / phone)

16. Vault → "Restore on a new phone: Off" → Set up: suggestion shown, "Another one" changes it; a 10-character
    passphrase, your name, or your account password are refused with the reason; wrong account password → refused.
17. After set-up: "On since …, n of n"; record a new SOS part → count rises after upload.
18. Clear app data (or a second emulator), log in → vault shows "set up … restore here" → wrong passphrase → "does not
    open"; right one → "N items restored"; Bring back → plays; hashes match.
19. "Recovery data was last fetched on …" appears after restore.
20. Change passphrase (wrong current → refused); old passphrase no longer restores on a fresh install.
21. Delete everywhere on phone A (after 24 h) → open the vault on phone B → item shows "Deleted everywhere".
22. Screenshot of the dialog is black; pressing Home closes the dialog.
