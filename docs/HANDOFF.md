# Handoff: Phase 11 (SOS without internet) + 10B (Rise "Proof") done (2026-10-22); Phase 9 frozen

**Phase 11 + 10B implemented, compiled, tested and verified 2026-10-22** (full-autonomy brief; `docs/PHASE11.md`):
- **SOS without internet:** every SOS starts on the phone (Safety Circle SMS and location work offline);
  `SosRecorder` records it on the server later with a client reference (V18: `client_ref`, `started_offline`,
  end ≥ start CHECK). "I'm safe" works offline and is recorded later with its real time. Before this, an SOS without a
  server answer only showed "could not record" and texted nobody.
- **Rise "Proof" (10B):** tab "Rise", phone-only, encrypted per account; Doubt → Prepare → Try → Proof → Next; replay of
  the person's own record for the same self-doubt; safety worry opens SAFE preparation; quiet mode, optional screen
  lock, FLAG_SECURE, delete; no Home card, no notifications, no incident note (deviations R1–R10).
- **Results:** backend **482/0** (2 skipped); Android **527/0**; lint **0 / 85**; V18 dry run **10/10**, applied (dev DB
  **v18**, next **V19**); live MySQL **12/12** (after a first attempt stopped on two script faults, disclosed); emulator
  PASS with the backend really stopped (start offline, process death, recorded later, offline end) and for Rise. One
  bug found on the emulator and fixed with a regression test (screen stayed "not recorded" if the server answered
  while contacts loaded).
- **NOT VERIFIED:** real phone (incl. an automatic SMS sent offline on a real SIM), TalkBack, dark mode, Rise's
  screen-lock gate on a device with a lock.
- **State left:** backend and emulator stopped, `adb reverse` removed, all test accounts deleted; the new debug build is
  installed on the emulator (its account was deleted, so it will show the session-expired dialog).
- **Next:** the user's review; 10D (real phone + TalkBack for SOS offline and Rise); still deferred: token encryption
  (A14), password reset (A6), 10C collective proof (needs users and moderators).

---

**Phase 10A.4 "session continuity" implemented, compiled, tested and verified 2026-10-22** (the user's brief;
`docs/PHASE10.md` §11): token renewal (`POST /api/users/me/token`, always granted during an SOS or Safe Walk; the phone's
`SessionRenewer` renews below 12 h left) and the rule that another sign-in needs the account password to end an
ACTIVE SOS (V17 `started_session` only while ACTIVE, `ended_elsewhere`; password prompt on the SOS screen; the starting
phone re-checks when its SOS screen is visible). Backend **473/0** (2 skipped), Android **487/0**, lint **0 / 85**, V17
dry run **7/7** and applied (dev DB **v17**, next **V18**), live MySQL **9/9**, emulator PASS. Not verified: a renewal
on a device, TalkBack, real phone. All test accounts deleted; backend and emulator stopped; `adb reverse` removed.
**Next:** the user's review of 10A/10A.4; still deferred: token encryption (A14), password reset (A6).

**Phase 10A implemented, compiled, tested and verified 2026-10-22** (`docs/PHASE10.md`, design `docs/PHASE10_DESIGN.md`):
- 10A.1 honesty / identity / language: onboarding rewritten ("Safety that expands your world"), Settings, Profile,
  Safety Center and Home without placeholders (timeline and duplicate tile removed, Evidence Vault reachable without an
  SOS), tab "Nearby", summary leads with safe places, Circle and Safe Walk copy, accurate privacy notice, `feature/map`
  removed.
- 10A.2 account security: V16, `ActiveAccountCheck` (per-request account check + credentials-stamp revocation), password
  change (refused during an ACTIVE SOS/walk), Android `SessionEnder` (a stale token's 401 never signs out a newer session).
- 10A.3 coercion-aware deletion: password, refused during / 24 h after an emergency, 7-day grace with Home banner,
  cancel needs the password, postponed by an emergency, evidence files before rows, audit-referenced accounts
  anonymised, phone wipe on `account_deleted`.
- **Results:** backend **466 tests, 0 failures, 2 skipped**; Android **480/0**; lint **0 errors / 85 warnings**; V16 dry run
  **13/13**, applied (dev DB **v16**, next **V17**); live MySQL **23/23** (after a first attempt stopped by a script
  fault, disclosed); emulator (API 37) PASS for the changed screens. **NOT VERIFIED:** TalkBack, dark mode, onboarding
  pages on a device, real phone.
- **State left:** backend and emulator stopped, `adb reverse` removed, the 10A debug build installed on the emulator
  (signed out). Every account the checks created was deleted. A backup of `users` taken before V16 is in the session
  scratchpad only (not in the repo).
- **Next:** (1) the user's review of 10A; (2) recommended small "session continuity" step (`PHASE10.md` §9: token
  renewal for long SOS, a policy for a second session ending an SOS, token encryption); (3) 10B RISE "Proof" only when
  briefed. Phase 9 stays frozen.

---

**Phase 9B complete: implemented 2026-10-21, reviewed and accepted by the user on 2026-10-21, frozen**
(`docs/PHASE9.md` §27–§34): weekly, system-wide
outcome counters (V15 `evaluation_counters`, after commit, best effort, no place/person/fine time; CHECKs make a cell
unstorable), `moderation_cases.coordination_rule` (so coord-1 decisions are credited to the rule that asked), a weekly
snapshot (incl. the rules-2 shadow comparison, durable instead of the hourly log line), and the ADMIN-only
`GET /api/admin/evaluation` (rates with Wilson intervals, gate G1). Backend **453 tests, 0 failures, 2 skipped**,
Android **454/0**, lint 0 errors / 87 warnings, V15 dry run **19/19** and applied (dev DB **v15**, next **V16**), live
MySQL **21/21** (after a first attempt that failed on two script faults, disclosed in §32). Nothing user-visible; no
emulator check. Every rate is `NOT_ENOUGH_DATA` today (no real data; correct). Left in the dev DB: 29
`phase9.verify9b.*` USER accounts, 6 audit rows; counter rows from the check deleted. Backend stopped. **Next:** the
user's review of 9D.

**Phase 9D implemented 2026-10-21, awaiting the user's review** (`docs/PHASE9.md` §18–§26): neighbour join (active),
havens-alone cap = active rules **rules-1.1**, rules-2 in **SHADOW** (not promoted: no evidence). Backend **423 tests,
0 failures, 2 skipped** (final full run on the final code), Android **452/0**, lint 0 errors / 87 warnings, live MySQL
**19/19**, emulator E1–E4 PASS. Left in the dev DB: 16 USER accounts `phase9.verify9d.*` / `phase9.emu9d.*`, no test
signal. Backend and emulator stopped, `adb reverse` removed. `CLAUDE.md`, `docs/API.md` and `PHASE9_DESIGN.md` are
updated for 9D. **Next:** the user's review of 9D; 9B only when briefed.

**Phase 9C is complete: reviewed and accepted by the user on 2026-10-21** (`docs/PHASE9.md` §9–§16; the bridge file
`docs/PHASE9C_CONTINUATION.md` only records how 9C was closed). **Do not start 9D (or 9B) until the user briefs it
separately.**
Read `CLAUDE.md` (permanent rules and architecture), then `docs/PHASE9.md` (Phase 9 so far: 9C.0 fix, 9A attack
simulation and its findings, 9C) and `docs/PHASE9_DESIGN.md` (the Phase 9 design), then `docs/PHASE8.md` and
`docs/PHASE8_DESIGN.md` (Safety Intelligence as built). Per-phase details are in
`docs/PHASE4A–4D.md`, `docs/PHASE5.md`, `docs/PHASE6.md`, `docs/PHASE7.md`, `docs/TIMESTAMPS.md` and `docs/API.md`.
The folder became a git repository on 2026-10-21 (`fbe530b`); Phase 10A is committed on `main` (not pushed).

## 1. Status

| Phase | Implemented · compiled · tested | Manual verification |
|---|---|---|
| 4A–4D | yes | 4A, 4B not verified; 4C partial; 4D skipped by the user (see the phase docs) |
| 5 Evidence Vault | yes | Emulator verified; Galaxy A23 partial; rest deferred (`PHASE5.md` §11.5.1) |
| 6 Community Safety Intelligence | yes | Emulator (API 37) main flows; real phone not verified |
| 7 Verification, Trust, Moderation | yes | Emulator (API 37) PASS; TalkBack/dark mode not verified; Galaxy A23 deferred |
| **8 Safety Intelligence Engine** | **yes** (8A–8D) | **Emulator (API 37) PASS** for the picture card, radar cells, dark mode, "Check the way", walk unaffected (`PHASE8.md` §5); **TalkBack NOT VERIFIED; real phone NOT VERIFIED; a real pattern episode on MySQL NOT VERIFIED** |
| **9 AI/ML (in progress)** | **9C.0 reopen fix, 9A attack simulation (test code only), 9C coordination check (SHADOW by default), 9C.1 S15 Option B, 9C.2 group members + whole-group pause, 9C.3 guard fixes: yes. 9D (neighbour join, rules-1.1 havens cap, rules-2 SHADOW): yes, awaiting review. 9B (outcome counters, V15, ADMIN report): yes, complete (accepted 2026-10-21, frozen).** No learned model (gates not met; G1 `NOT_MET` per the 9B report) | 9C.0 **live 10/10**; 9C **live SHADOW 11/11**; 9C.1–9C.3 **live ACTIVE 25/25 on MySQL**; **emulator (API 37) moderator screens PASS** (`PHASE9.md` §14); TalkBack speech, dark-mode colours, Galaxy A23: **NOT VERIFIED** |

## 2. Phase 8 in brief

rules-1 Safety Intelligence on two axes (confirmed concern / how much is known; nothing ever says "safe"), computed on
read from exactly the visible signals; independent freshness; pattern memory (V14: one decaying number per ~1 km area
and group, from finished corroborated episodes, k = 3 episodes, reset on moderator removal); app-drawn radar area
cells (no heat map, no tiles); "Check the way" on the Safe Walk set-up screen, computed on the phone from ~5 km region
packs (the server never sees a route). Decision support only: isolated by source-scan tests from SOS, Safe Walk's
session and policies, the Safety Circle, evidence and moderation of people.

## 3. Last actual results (2026-10-21)

| Check | Result |
|---|---|
| `mvn test` | **388 tests, 0 failures**, 2 skipped (opt-in MySQL), after 9C.1–9C.3 (366 after 9C; 316 at the end of Phase 8) |
| `gradlew testDebugUnitTest` | **450 tests, 0 failures** after 9C.1–9C.3 (446 after 9C; 431 at the end of Phase 8) |
| `assembleDebug` + `lintDebug` | OK; **0 lint errors, 87 warnings** (unchanged) |
| V14 on MySQL 8.0 | dry run: 8 violations rejected; dev DB at **v14** |
| Live API (real defaults) | **22/22 PASS** |
| Emulator (Medium_Phone_API_37.0, demo settings) | `PHASE8.md` §5 items 1–8 PASS |

## 4. Environment state left behind

- **Backend: stopped.** Real defaults in `application.properties` (demo values were command-line arguments only).
  Database `safesphere` at **v15** since 2026-10-21 (9B); `evaluation_counters` empty (the live check's rows were
  deleted; the next start takes a fresh weekly snapshot); `area_patterns` empty; 6 signals have `reached_confirmed = 1`.
- Phase 9B live check (2026-10-21): 29 `phase9.verify9b.*@example.com` accounts (all USER), audit rows 2 ROLE_GRANTED +
  2 ROLE_REVOKED + 2 DISMISS; no test signal, case or flag left; the 4 pre-existing FLAGS cases unchanged.
- Test accounts: `phase8.smoke.*@example.com` (6), `phase8.demo.*@example.com` (10), random passwords never stored.
  Demo signals near `tdr1v9` (Bangalore) expire on their own. One CANCELLED demo Safe Walk of the Phase 7 emulator
  account. No account has MODERATOR or ADMIN.
- **Emulator: stopped** (Phase 8 build installed, the emulator account still signed in); `adb reverse` removed;
  notification permission granted on the emulator during the walk test.
- Phase 9 live check of 9C (2026-10-21, SHADOW): 7 `phase9.verify9c.*@example.com` accounts (all USER), 4 DISMISS
  audit rows; no test signal, case or flag left; backend stopped again. Shipped config: coordination mode SHADOW.
- Phase 9C.1–9C.3 live ACTIVE check and emulator check (2026-10-21): 56 `phase9.verify9c1.*` + 12 `phase9.emu9c1.*`
  accounts (all USER; 8 carry a 7-day pause + 1 strike from the checks), audit rows 8 RESTRICT + 3 DISMISS; no test
  signal, case or flag left; the 4 pre-existing FLAGS cases unchanged; the emulator account
  `phase7.smoke.emu.85cc36a3@example.com` was a temporary MODERATOR and is USER again. Backend and emulator stopped,
  `adb reverse` removed, night mode off; the emulator has the 9C.1–9C.3 debug build installed.
- Phase 9 live check of 9C.0 (2026-10-21): 6 `phase9.verify.*@example.com` accounts (all USER), 7 DISMISS rows in the
  audit log (append-only, 365 days); no test signal, case or standing row left; backend stopped again.
- `android/local.properties` unchanged (`api.base.url=http://127.0.0.1:8080/`).
- One pre-existing flaky backend test fixed (`EvidenceApiIntegrationTest`, `PHASE8.md` §2).

## 5. Deferred verification (exact)

1. Real phone (Galaxy A23): `PHASE8.md` §5 checklist items 1–8.
2. TalkBack walkthrough of the picture card, the radar summary and the way brief.
3. A real pattern episode on MySQL (a confirmed incident left to expire 3+ days, then three weekly ones).
4. Earlier phases' deferred items (unchanged): 4A/4B manual checks, 4C partial, Phase 5 A23 items, Phase 6 real phone,
   Phase 7 A23 + TalkBack.

## 6. Recommended next steps

- The user's manual checks above.
- ~~Home "Safety status: SAFE" sample card~~ **Done 2026-10-21** (the user chose the factual card over removing it or
  pointing to the area picture): the card states only what the phone knows. `HomeStatus` (in `HomeViewModel`) is
  *Checking* until the stored emergency record has been read and the walk recovery has finished, then *Nothing
  running* ("No SOS or Safe Walk is running on this phone. Home does not check your surroundings."), or *Safe Walk on*,
  or *Check-in missed* (MISSED / NO_RESPONSE: amber + warning icon). During an SOS the card is hidden, so the red banner
  is the one emergency element. A running SOS or walk shows at once; "nothing" only after the check. Teal or amber
  only, never green or red (`StatusTone`); the Preview badge, `SafetyState` (SAFE/CAUTION) and `hero_safe` are gone.
  Tests: `HomeViewModelTest` +5, new `HomeTextsTest` (6: scans **every** string Home's layouts and code reference
  for safety claims, the Safe badge is never used on Home, status words/icons unique, amber ⇔ warning icon).
  Results: `testDebugUnitTest` **442 tests, 0 failures**; `assembleDebug` OK; `lintDebug` **0 errors, 87 warnings**
  (unchanged). **Emulator: only "Nothing running" seen** (during the SOS tile checks below, incl. 320/360 dp and large
  text); the other states, TalkBack on the card and dark mode NOT VERIFIED; **real phone NOT VERIFIED.** Manual check: (1) cold start shows *Checking* briefly,
  then *Nothing running*; (2) start a Safe Walk → *Safe Walk on*; let a check-in lapse → *Check-in missed* (amber,
  warning icon); (3) start SOS → card hidden, red banner shown; (4) TalkBack reads "Right now: …" as one sentence;
  (5) dark mode contrast.
- **Home SOS cleanup: DONE 2026-10-21** (implemented, compiled, tested, emulator-verified; the user's requests).
  - Texts: badge `sos_badge_no_alerts` "No alerts yet" → `sos_badge_countdown` "5 s to cancel"; `cd_sos_tile` says the
    countdown can be cancelled and that SafeSphere texts the Safety Circle only if automatic SMS is switched on (4D).
    `HomeTextsTest` +1 (requires the opt-in condition, forbids "no alerts" / "not active yet" / "nobody is").
  - Layout: the badge moved from beside the title to **under "Emergency help"** in the tile's text column. Beside it,
    the badge kept its full width and squeezed the title (320 dp: "Emerg/ency"; 320 dp at 1.3×: "SO/S"; 320 dp at 2.0×:
    "SOS" and "Emergency help" disappeared). Probably the same with the old "No alerts yet" (same length; not tested).
    SOS behaviour, the tile's description and every other view are unchanged.
  - Results: `testDebugUnitTest --rerun` **443 tests, 0 failures**; `assembleDebug` OK; `lintDebug` **0 errors,
    87 warnings** (unchanged). Note: `HomeTextsTest` reads layouts from disk, which Gradle does not track as test
    inputs: after a layout-only change, run the tests with `--rerun`.
  - **Emulator (Medium_Phone_API_37.0), PASS:** TalkBack (focus via keyboard, "Display speech output") reads the tile as
    ONE item, exactly the new description, then "Double-tap to activate"; the badge is not a separate stop (all children
    non-focusable, re-checked after the layout change). Layout at 411 dp ×1.0, 360 dp ×1.0/1.3/2.0, 320 dp ×1.0/1.3/2.0:
    "SOS" always one line, "Emergency help" one line except 320 dp ×2.0 (wraps at the space), badge one line and
    unclipped. Emulator restored afterwards (size, density, font 1.0, TalkBack off, its notification permission denied).
  - **Not verified:** a real phone (Galaxy A23) and TalkBack swipe navigation by finger (adb cannot drive
    explore-by-touch; the keyboard "next item" shortcut did not fire over adb).
- **Phase 9 (AI/ML): design in `docs/PHASE9_DESIGN.md`; results in `docs/PHASE9.md`.** Verdict: no ML for anything a
  user sees (no real data, no ground truth, and retention deletes history by design). Phase 9 = an evidence layer: 9A
  adversarial simulator → 9B outcome counters (V15) → 9C coordination triage `coord-1` → 9D adjacent merge of concern
  reports + a `rules-2` candidate. A learned moderator-triage model is allowed only behind gates G1–G3 (§4.4 there).
  **Done 2026-10-21:** 9C.0 (reopen fix, live-verified) and **9A** (attack simulation through the real API, 37 tests,
  test code only). **Measured, SIMULATED:**
  - two accounts reach TAKE_EXTRA_CARE in ~1 h;
  - a ring of n accounts marks n(n−1)/2 areas, unseen by Phase 7;
  - two fake safe places turn "no information" into "no confirmed concerns";
  - one pair builds a "recurring" history in ~17 days;
  - a real warning is hidden or erased by as many accounts as back it;
  - honest groups backing 3+ of the same places count as one person;
  - 60 % of honest pairs on a 300 m street never confirm each other (grid boundary);
  - one account can scrape ~60 regions a day via `/area`.

  The coord-1, rules-2 and havens-alone prototypes fix the cases they target without false positives in the honest
  scenarios. **Decisions waiting for the user** are in `PHASE9.md` §4.

  **9C done 2026-10-21** (`PHASE9.md` §8): coord-1 R1/R2 in production, **SHADOW by default** (logs a count, opens
  nothing); no withdrawal lock for COORDINATION cases without flags (closes the detection oracle); moderators see the
  pattern in bands plus the guidance "not proof".

  **9C.1–9C.3 done 2026-10-21** (`PHASE9.md` §9–§16):
  1. **Option B** (S15): a moderator's DISMISS of a COORDINATION case lets the people counting behind THAT signal at
     that moment count separately on it (no migration; the closed case row is the mark; later joiners are not freed).
  2. **Group members as case participants** (`groupMembers`, pseudonyms + bands) and **RESTRICT `wholeGroup`**: one
     audited, appealable decision pauses every member (one by one was measured to be fragile).
  3. **Two coord-1 fixes found by the new tests:** the "dismissed group is not asked again" guard now looks at every
     signal backed only by the group's members; `Group.members()` (≥ 2 of the group's places) keeps an honest pair a
     ring confirmed once out of the list, the pause and the guard.
  4. Live ACTIVE check on MySQL **25/25**, emulator moderator screens **PASS** (TalkBack speech, dark-mode colours and
     the A23 not verified).

  **9C accepted by the user on 2026-10-21. Next: 9D, only when the user briefs it separately (9B likewise).** The shipped mode stays
  SHADOW; switching to ACTIVE needs an active moderator (still none in the dev setup).

  **9B complete: accepted by the user on 2026-10-21, frozen** (`PHASE9.md` §27–§34). What to do with it once real use starts: read
  `GET /api/admin/evaluation` (ADMIN) every few weeks; promote coord-1 to ACTIVE only with an active moderator team;
  promote rules-2 only on weeks of `SHADOW_PICTURE` counts plus moderator outcomes for the areas it changes; follow
  `UNREVIEWED_SHARE` (moderator capacity) and `RETIRED_AFTER_CONFIRMATION` (finding 6). Publication rule: system-wide
  only, values below 10 as "fewer than 10".

  Correction to an earlier note: Phase 8's
  `AreaIntelligenceModel` interface and shadow mode were **never built**; only `modelVersion` and the codes-only
  contract exist. A real route planner still needs an on-device routing graph and a new privacy review
  (`PHASE8_DESIGN.md` §16.4).

## 7. Open items / known limitations

- Phase 8 (`PHASE8.md` §7): thresholds are judgment calls; straight-line corridor; coarse Geocoder results; sparse areas
  read as "no information"; `reached_confirmed` checked every 15 min; pattern memory up to ~240 days; in-memory,
  per-instance region budget; operator-level correlation of a region download with a walk.
- Phase 7 (`PHASE7.md` §8), Phase 6 (`PHASE6.md` §6), Phase 5 (`PHASE5.md` §7, §10.4, §11.3): unchanged.
- **Phase 7 bug, found and FIXED 2026-10-21 (9C.0, `PHASE9_DESIGN.md` §1.3 F9):** after a moderator dismissed a
  COORDINATION case, the next `ModerationService.maintain()` run (≤ 15 min) opened a new one for the same signal,
  because only *open* cases were checked; each reopen also blocked withdrawal again. FLAGS and REREPORTED cases never
  looped. Fix: `maintain()` skips a signal that already has a DISMISSED COORDINATION case
  (`ModerationCaseRepository.existsBySignalIdAndOriginAndOutcome`). Closed cases are deleted after 90 days, so a
  dismissed signal can be looked at once more after that. Test `ModerationApiIntegrationTest.aDismissedCoordinationCase_
  isNotReopenedByTheNextRun_andItsReporterCanWithdrawAgain` **failed before the fix** (2 cases for the signal instead of
  1) and passes after it. `mvn test`: **317 tests, 0 failures, 2 skipped**. No migration and no API change. **Live
  verification on MySQL: 10/10 PASS** (real scheduled job, positive control; `PHASE9.md` §1). **Not verified:** the
  moderator screens on the emulator (unchanged by the fix).
- Google Play declarations needed for SMS (4D) and the microphone FGS (Phase 5). Release minification off (needs Gson
  keep rules, incl. Phase 6/7/8 DTOs). Spring Boot 3.5.16 past OSS support.
