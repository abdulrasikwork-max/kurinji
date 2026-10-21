# Phase 7: Verification, Trust and Moderation

Written 2026-10-20; **completed the same day** (second session). Status vocabulary per `CLAUDE.md` §3.

| Part | Status |
|---|---|
| Backend (V13, trust, independence, flags, moderation, appeals, roles, sign-in limits) | **implemented, compiled, tested** (261 backend tests), **V13 applied and constraint-checked on MySQL 8.0**, **live API smoke-tested (58/58)** |
| Android: errors (403/429), confidence + "How sure is this?", flags, pause banner + appeal | **implemented, compiled, unit-tested, emulator-verified (API 37)** |
| Android: moderator screens (Profile entry, case queue, case detail, decisions, appeals) | **implemented, compiled, unit-tested, emulator-verified (API 37)** |
| Real phone (Galaxy A23) | **NOT VERIFIED — DEFERRED** (checklist §10) |
| TalkBack walkthrough, dark mode on a device | **NOT VERIFIED — DEFERRED** |

## 1. What Phase 7 adds, in one paragraph

Phase 6 signals were protected by probation, limits and "k = 2 distinct accounts". Phase 7 makes them **trustworthy
without making people visible**: the server now counts **independent people** (accounts that keep backing each other
count once), weighs contributions by a **private** track record, shows a plain-language **confidence** with its
reasons, lets people **flag** abusive signals, automatically **quarantines** (hides, reversibly) signals that
independent people flag, and gives **human moderators** a queue where contributors appear only as **case-scoped
pseudonyms**. Moderators can dismiss, remove, or pause a person's community contributions; every decision is in an
append-only **audit log**, and a pause can be **appealed to a different moderator**. Sign-in and sign-up now have the
missing **rate limits** (Phase 2 gap). A community pause never touches SOS, Safe Walk, the Safety Circle or evidence.

## 2. Threat model (additions to `PHASE6.md` §2)

| # | Threat | Defence in Phase 7 | Residual risk |
|---|---|---|---|
| 1 | Fake accounts / account farming | Registration limit per network address (10/h, in memory); 24 h probation (Phase 6); new accounts count for nothing until then | Many addresses (botnets, mobile IP rotation). No phone/ID verification (privacy choice, §6) |
| 2,16 | Several accounts of one person, Sybils | **Independence rule** (§3.2): linked accounts count once for precision, confidence, retirement and flags | Patient sybils that behave like independent locals; legitimate friends may be linked (they then count once — conservative) |
| 3,4 | Coordinated reports / confirmations | Independence + weights; COORDINATION case opened automatically when a signal's only support is one linked group | Large, well-separated sock-puppet farms |
| 5 | Coordinated "No longer true" | Retirement needs ≥ 2 **independent** disputers whose weight ≥ the support's weight | — |
| 6 | Location spoofing | Unchanged: phone-asserted cells; nearness at shown precision. No movement history is kept to detect "impossible travel" (it would be a location trail) | Spoofing is not detectable; documented |
| 7,8 | Scraping, enumeration | Phase 6 limits; random UUIDs for signals, cases, appeals, audit rows; hidden signals answer 404 exactly like missing ones; moderator requests limited (600/h) | Many accounts × limits |
| 9,10 | Login brute force, account takeover | **10 failed sign-ins per account and 30 per address per 15 min** (429, checked before the password), unknown e-mails counted the same (no enumeration) | Someone who knows an e-mail can delay that person's NEW sign-ins for 15 min (existing 24 h sessions and SOS keep working). JWT unchanged (24 h, no refresh) |
| 11,12 | Report bombing, harassment, targeting a home/business | Flags (TARGETS_SOMEONE), auto-quarantine, removal with a strike, 30-day tombstone (re-creation is swallowed; independent re-reports reopen a case), withdrawal refused while under review | Moderators must actually work the queue |
| 13 | Targeted geographic manipulation | Per-area limits (Phase 6) + independence + flags | — |
| 14 | Sparse-area de-anonymisation | Unchanged precision rule, now needing 2 **independent** people | As Phase 6 |
| 15 | Timing correlation | Flags and moderation are never public; flag answer identical whatever it caused | — |
| 17 | Bots | Burst limit: 10 community writes per 10 min per account (outbox re-sends are free) | — |
| 18 | Database abuse | CHECKs on every new enum/state, generated "one open case per signal" key, append-only audit log whose actor cannot be deleted (no cascade) | DB administrator can still change rows |
| 19 | Moderator abuse | Case-scoped pseudonyms; no names/e-mails/ids/other SafeSphere data in any moderator view; cannot see or act on a case they took part in; cannot decide the appeal of their own decision; reason codes only (no free text); full audit log (ADMIN reads it) | A moderator can still make bad calls → appeals + audit |
| 20 | Privilege escalation | Role read from the DB on every request (not from the token or the client); unknown JSON fields ignored (`role` in a sign-up is ignored, tested); ADMIN can only be set in the database; an admin cannot change their own or another admin's role | Database access = full control (as before) |
| 21,22 | Replay, duplicates | Unique (signal, user) for answers and flags; idempotent report re-send; decisions on a closed case → 409; row lock + `@Version` on cases and appeals | JWT replay within its 24 h life (TLS required in production) |
| 23 | Abuse through account creation | Registration limit + probation | — |
| 24 | Response differences | Identical 202 bodies for report/flag; hidden = 404; standing view contains no score | 409 on withdraw tells a contributor their signal is under review (deliberate transparency) |

## 3. Trust model

### 3.1 Private standing (`contributor_standing`, `TrustPolicy`)
Counters only, **no places and no times**: corroborated reports, refuted reports, strikes, flags upheld / rejected,
`restricted_until`. All counters **halve every 90 days** (purge job), so good and bad history fade.

| Tier | Weight (half-points) | Rule |
|---|---|---|
| RESTRICTED | 0 | paused by a moderator, or account disabled — contributions count for nothing (immediately, also old ones) |
| LIMITED | 1 | a strike, or ≥ 3 refuted reports and more refuted than corroborated |
| STANDARD | 2 | everyone else (after the 24 h probation) |
| TRUSTED | 3 | account ≥ 30 days, ≥ 3 independently corroborated reports, corroborated ≥ 3 × refuted, no strike |

- **Corroborated** = someone *independent* (not linked) also supports the same signal; credited once per report
  (`signal_contributions.credited`). **Refuted** = a report retired by "no longer true" that nobody independent ever
  backed, or removed by a moderator as false. A fixed place (backed, then retired) is never held against anyone.
- The tier is **never shown** — not to others, not to the person. It only changes weights. No badges, levels or ranks.

### 3.2 Independence (`Independence`)
Two accounts are **linked** when they share ≥ 3 live signals on the same side (support, "no longer true", or flags)
**and** that is at least half of the smaller account's own live activity. Counting picks the strongest participants
first and skips anyone linked to someone already counted. Uses only live rows (deleted by normal retention), so links
fade on their own. Active locals who share popular places but do much else are not linked.

### 3.3 What people see (`SignalPolicy`)
`I` = independent supporters, `W` / `WD` = weight of supporters / disputers.
- visible: `W ≥ 2` and not retired (a LIMITED person's lone report waits for an independent report of the same place)
- precision BLOCK only when `I ≥ 2` (k = 2 now means two **independent** people)
- retired: ≥ 2 independent disputers and `WD ≥ W`
- **confidence**: HIGH if `I ≥ 3` and `W − WD ≥ 7`; MODERATE if `I ≥ 2` and `W − WD ≥ 4`; else LOW. A single report is
  always LOW, even from a TRUSTED person. Shown as "Not yet confirmed" / "Confirmed by people nearby" / "Strongly
  confirmed" — never "verified".
- **reasons** (aggregate): SINGLE_REPORT, CONFIRMED_BY_OTHERS, ESTABLISHED_CONTRIBUTORS (only with ≥ 2 people, so a lone
  reporter's tier is never revealed), LINKED_SUPPORT_COUNTED_ONCE, DISPUTED.

## 4. Moderation model

- **Flags** (`POST /signals/{id}/flags`): reasons FALSE_INFORMATION, TARGETS_SOMEONE, SPAM_OR_COORDINATED (no text). Same
  visibility and nearness rules as answers; one per person; not by a contributor; 10/day; new accounts' flags count after
  probation. Flags are never shown; moderators see counts per reason.
- **FlagPolicy**: any counted flag opens a case (one OPEN case per signal); **quarantine** when ≥ 2 independent flaggers,
  flag weight ≥ 4 and ≥ the signal's support weight — so two flags cannot silence a well-corroborated warning (it goes to
  review but stays visible). Quarantine hides; it never touches a person.
- **Automatic cases**: FLAGS, REREPORTED (a removed signal re-reported by ≥ 2 independent people during its 30-day
  tombstone), COORDINATION (a visible signal whose only support is one linked group).
- **Decisions** (reason codes only): QUARANTINE · DISMISS (signal restored, flaggers' "rejected" counter +1, they cannot
  flag it again) · REMOVE (contributions deleted, 30-day hidden tombstone, reporters: FALSE_INFORMATION → refuted +1,
  TARGETS_SOMEONE / SPAM → strike) · RESTRICT a participant by case pseudonym for 1–90 days (default 7; strike +1;
  never shortens a longer pause).
- **People** are only ever affected by a human decision. A pause blocks report/answer/flag (403 with a calm message);
  viewing signals and every safety feature keep working (tested: emergencies and Safety Circle return 200).
- **Appeals**: the person sees `GET /standing` (paused, until, reason, appeal status — no score) and can ask once per
  decision (`POST /appeals`). Another moderator decides UPHELD / OVERTURNED (lifts the pause if it is the newest, removes
  the strike).
- **Audit log** (`moderation_actions`): actor, action, reason, case ref, category, subject, time. Append-only; ADMIN reads
  it (`GET /api/admin/moderation-log`). Actor rows cannot be cascaded away.
- **Roles**: `users.role` USER / MODERATOR / ADMIN; checked from the database on every request by `RoleAuthorization`
  (SecurityConfig) **and** again in `ModerationService`. ADMIN appoints/removes moderators (`PUT /api/admin/roles`,
  audited); ADMIN itself only via SQL. Revocation is effective on the next request with the same token (tested).
- **Retention**: closed cases 90 days, audit log 365 days (appeals with it), tombstones 30 days; cases and flags go with
  their signal.

## 5. Database (V13, `V13__community_trust_and_moderation.sql`)
`users.role` (+CHECK) · `contributor_standing` · `place_signals.moderation`/`moderation_until` (+CHECKs: REMOVED ⇔ until)
· `signal_contributions.credited` (+CHECK: only REPORT) · `signal_flags` (unique signal+user, reason CHECK) ·
`moderation_cases` (generated `open_marker` + unique ⇒ one OPEN case per signal, origin/state CHECKs, `version`) ·
`moderation_actions` (action/reason/role CHECKs, actor FK without cascade, subject FK cascade) · `moderation_appeals`
(unique per action, state CHECK, `version`). No e-mail, name, coordinate, IP or free-text column in any new table
(checked on MySQL). Next migration: **V14**.

## 6. Decisions (with reasons)

| Decision | Reason |
|---|---|
| Trust is private and only weighs contributions | Trust > popularity; a visible level would be a social status and a target |
| Independence from shared live activity, not device ids / phone numbers / IPs | No new personal data; fades with retention; explainable |
| No phone/ID verification | Would tie a safety-report account to a real identity; sybil resistance comes from probation + independence + limits instead (documented residual risk) |
| Content automatically, people only by humans | Automatic punishment of people fails unsafely; quarantine is reversible |
| Quarantine needs flag weight ≥ support weight | Prevents silencing real, corroborated warnings by flag-bombing |
| Case-scoped pseudonyms (HMAC with a random key per server start) | Moderators can act on a participant without knowing who they are or following them across cases |
| Reason codes, no moderator free text | Free text invites personal data and opinions about people |
| A pause never blocks safety features | A safety app must fail safely; moderation must not disable SOS |
| Withdrawal refused while a case is open | Stops "withdraw to escape review"; bounded by the signal's lifetime. **Changed in Phase 9C:** not for a COORDINATION case without flags (the 409 told a ring it was detected; `PHASE9.md` §8) |
| Sign-in limits in memory, per account + per address, failures only | No IP/e-mail log at rest; account limit kept short because it can delay a victim's new sign-in |
| Roles read from the DB per request, ADMIN only via SQL | Instant revocation; no API path to the highest privilege |

## 7. Tests (actually run on 2026-10-20)

| Suite | Result |
|---|---|
| Backend `mvn test` | **261 tests, 0 failures, 2 skipped** (opt-in MySQL tests). New: `IndependenceTest` 4, `TrustPolicyTest` 5, `FlagPolicyTest` 6, `SignalPolicyTest` +8 (20), `AttemptLimiterTest` 3, `AuthThrottleApiIntegrationTest` 5, `CommunityTrustApiIntegrationTest` 6, `CommunityBurstApiIntegrationTest` 1, `ModerationApiIntegrationTest` 19 (incl. queue `youTookPart`, every decision audited with actor/reason/case and no person named, paused contributor can still start an SOS and reach Safe Walk, Safety Circle and the Evidence Vault key); `CommunityApiIntegrationTest` allow-list updated |
| Android `testDebugUnitTest` | **403 tests, 0 failures** (24 new: `CommunityTrustTest` 7, `ModerationTest` 16, `RemoteAuthRepositoryTest` +1; `ProfileViewModelTest` adapted) |
| Android `assembleDebug`, `lintDebug` | OK; **0 lint errors, 87 warnings** (85 before Phase 7; +2 = `accessibilityHeading` is API 28+, the same pattern used across the app) |

Required test areas → tests: moderator authorization / normal-user denial → `ModerationApiIntegrationTest.normalUsers_cannotReach…`,
`access_tellsTheAppWhatToShow`, `aDisabledModerator_hasNoAccess`, `roleChanges_takeEffect…`, Android `ModerationTest`
(`a normal account sees nothing…`, `a role removed meanwhile (403)…`, Profile entry); case queue / detail →
`caseView_usesCaseScopedPseudonyms…`, `the queue shows open cases and appeals`; decisions → `dismiss_…`, `remove_…`,
`restriction_…`, Android `decisions send only…`, `impossible decisions are never sent`, `a 409 … reloads the case`;
appeals → `restriction_…` (same moderator 403, second moderator decides), Android `appeal decisions…`; audit →
`everyDecision_isAudited…`, `roleChanges_…`; conflict of interest → `aModeratorWhoTookPart_cannotSeeOrDecideTheCase`,
Android `no decision is offered on a case the moderator took part in`; paused contributor → `restriction_…`,
`aRestrictedPersonsContributions_stopCountingAtOnce`, Android `CommunityTrustTest`; regression of SOS / Safe Walk /
Safety Circle / Evidence Vault → the full existing suites (Emergency, Journey, SafetyContact, Evidence: all pass) plus
the paused-contributor checks in `restriction_…`.
| V13 dry run on MySQL 8.0 (scratch `p7_` tables, dropped afterwards) | DDL OK; **16 constraint violations rejected as expected**; cascades verified; actor deletion blocked |
| V13 on the dev database | Flyway "Successfully applied 1 migration … now at version v13" |
| Live API, real defaults | **26/26 PASS**: 401/403 boundaries, DB role → access with the same token, admin grant/revoke + audit, ADMIN not grantable, report pending (probation), standing has no score, free-text flag 400, 11th sign-in 429 even with the right password, other account unaffected, paused account's token still reaches `/api/emergencies`, 11th registration/h 429 |
| Live API, demo settings (`probation=0s, delay=0s` **for the demo only**) | **32/32 PASS**: flag → one flag hides nothing → two flags quarantine, identical flag answers, UNDER_REVIEW, withdraw 409, case with 3 pseudonymous participants and no e-mail/name, restrict → 403 / area 200 / SOS 200, appeal, same-moderator 403, second moderator UPHELD, REMOVE, closed case 409, re-creation hidden; MySQL rows checked (tombstone, strikes, resolved flags, flagger credit, audit order, appeal decider) |
| Scheduled clean-up job on MySQL | Ran without errors; credited the Phase 6 demo reports that a second person had backed (`credited = 1`) and left uncorroborated ones alone |

## 8. Known issues and limitations (honest)

- Moderation in the app covers OPEN cases and appeals; closed cases and the admin functions (role changes, audit log)
  are API-only (`docs/API.md`). The "My Community Contributions" Profile item is still "Coming soon".
- A moderator cannot write notes on a case (deliberate: reason codes only).
- Rate limiters (sign-in, sign-up, bursts, moderator requests, area look-ups) are **in memory, per instance**: reset on
  restart, a cluster needs a shared store. Behind a reverse proxy the peer address is the proxy (configure a trusted
  proxy only).
- The sign-in account limit can delay a victim's new sign-ins for 15 min if an attacker knows the e-mail.
- Registration still answers 409 for an existing e-mail (enumeration; mitigated by the address limit; fixing it needs
  e-mail verification, which has no channel yet).
- Corroboration is credited by the 15-minute job; a report retired before the job runs can count as "refuted" once.
- **Fixed 2026-10-21:** a dismissed COORDINATION case used to be reopened by the next clean-up run (the condition still
  held, and only open cases were checked). Now a signal with a dismissed COORDINATION case is not reopened (until that
  closed case is deleted after 90 days). Details in `HANDOFF.md` §7.
- **Changed in Phase 9C (2026-10-21):** withdrawal is no longer refused during a COORDINATION case without flags, and
  the case view gains a `coordination` pattern (bands). Measured: dismissing such a case did not make an honest
  group's signals count again (`PHASE9.md` §8.6). **Changed in 9C.1:** it now does, for that one signal and the people
  counting at the dismissal (S15 Option B, `PHASE9.md` §9). **9C.2:** a case also lists the other members of a coord-1
  group (`groupMembers`) and a moderator can pause the whole group in one decision (`PHASE9.md` §10).
- Legitimate friends who always confirm each other may be linked and count once (conservative by design).
- Case pseudonyms change when the server restarts (random key, never stored) — a moderator must reload the case.
- `REMOVE` deletes the signal's contributions, so contributors no longer see it in "your reports" (no removal notice).
- Tokens of a disabled account still work for 24 h for non-moderation endpoints (pre-existing; their community
  contributions already count for nothing).
- Dev database leftovers: test accounts `phase7.smoke.*@example.com` (28 accounts in the DB are USER; **no account has
  MODERATOR or ADMIN**), demo signals near `tdr1v9q` / `tdr4p8x` (some quarantined or removed), cases, flags, audit and
  appeal rows from the smoke and emulator runs. All community rows expire on their own; nothing else was deleted.

## 9. Second session (2026-10-20): what was added to finish Phase 7

- **Android `feature/moderation`**: `ModerationApi`, `ModerationDtos` (unknown or missing conflict flags default to
  "offer no decision"; pseudonyms and cells redacted in `toString()`), `RemoteModerationRepository`,
  `ModerationPolicy` (pure: reason codes per decision mirror the server, no decision on a case you took part in or a
  closed one, no appeal decision on your own restriction, reading order), `ModerationViewModel` (queue: asks `/access`
  first, a 403 at any point ⇒ `NotAllowed` and nothing stays on screen), `ModerationCaseViewModel` (only possible
  decisions are sent; a 409/403 reloads the case), `ModerationFragment`, `ModerationCaseFragment` (both **FLAG_SECURE**),
  adapters, `ModerationTexts`, navigation, Profile entry gated by `/api/moderation/access` (hidden on any failure).
- **Backend**: `CaseSummary.youTookPart` so the queue labels a moderator's own cases instead of failing on tap (the
  server still answers 403). Tests for it, for audit content, and for safety-feature regression while paused.
- **UX fixes found on the emulator**: "Withdraw" is no longer offered for an item under review (the server always
  refuses it there); appeal confirmation now explains the effect; plural/wording fixes.
- `docs/API.md`: full Phase 7 section.

**Security / privacy review of the moderator screens** (done 2026-10-20): authorization stays on the server (role read
from the DB per request, conflict-of-interest and separation-of-duties checks in `ModerationService`); the app's
hiding is convenience only and fails closed (unknown flags ⇒ no decision offered; any `/access` failure ⇒ no entry; a
403 ⇒ data cleared). Nothing identifying is ever on screen or in logs (pseudonyms truncated to 6 characters on screen,
redacted in DTO `toString()`); FLAG_SECURE blocks screenshots, screen recording and the recent-apps preview. No new
permission, no new dependency, no data stored on the phone (moderation data lives only in the ViewModels).
Residual: a moderator can still read a block cell of a flagged place — required to judge "aimed at a home"; covered by
the audit log, the per-moderator rate limit and the conflict-of-interest rule.

## 10. Verification checklist and results

Emulator run on **Medium_Phone_API_37.0**, 2026-10-20, backend with
`--app.community.new-account-probation=0s --app.community.publish-delay-min=0s --app.community.publish-delay-max=0s`
**for the demo only** (real defaults restored afterwards: the backend was stopped; `application.properties` was never
changed); moderators made with SQL and reset to USER afterwards. Screens were read with `uiautomator` dumps.

| # | Check | Result |
|---|---|---|
| 1 | Two-person signal → "Confirmed by people nearby"; "How sure is this?" lists "Independent people nearby said the same." + footer | **PASS** |
| 2 | Single report → "Not yet confirmed · … · placed only to about 1 km" | **PASS** |
| 3 | "Report a problem" → reason list → anonymous-flag confirmation → card shows "You flagged this for review", no answer buttons | **PASS** |
| 4 | Second independent flag (API) → signal gone from the app; the app user's own flagged report shows "being reviewed by moderators", no Withdraw | **PASS** |
| 5 | Pause by moderator 1 (API) → banner with date + reason; Report disabled; "Ask for a review" → "You asked for a review…"; Safety Circle and Safe Walk screens open normally | **PASS** (SOS start while paused verified by API test, not tapped on the emulator) |
| 6 | Moderator 2 lifts the pause **in the app** → user's banner gone, Report enabled | **PASS** |
| 7 | 10 wrong passwords (API) → correct password in the app shows "Too many attempts. Please wait a few minutes and try again." | **PASS** |
| 8 | TalkBack walkthrough; dark mode contrast of the banner | **NOT VERIFIED** (card content descriptions include the confidence — read from the accessibility tree; TalkBack itself not run) |
| M1 | Normal user: no Moderation entry in Profile; moderator: entry shown | **PASS** |
| M2 | Queue: open cases (hidden-for-review label), own case labelled "You took part…" and not openable, appeal of moderator 1's decision with both buttons | **PASS** |
| M3 | Case detail: block cell, flag reasons, confidence + reasons, people only as "Person d0d77d" etc.; no e-mail or name anywhere on screen | **PASS** |
| M4 | Remove with reason "Not true" in the app → "This case is closed (removed)"; reporter's private refuted counter +1, no strike; audit rows RESTRICT (mod 1), APPEAL_OVERTURNED and REMOVE (mod 2) | **PASS** |
| M5 | FLAG_SECURE: a device screenshot of the moderation screen is completely black | **PASS** |
| R | Same checks on the Samsung Galaxy A23 | **NOT VERIFIED — DEFERRED** |

Remaining real-phone checklist (deferred): items 1–7 and M1–M5 on the A23 (`CLAUDE.md` §12 for USB set-up), plus
TalkBack and dark mode (item 8).

## 11. Viva notes
- **What / why**: make community intelligence trustworthy without exposing anyone: independence, private weights,
  confidence with reasons, flags, human moderation with pseudonyms, audit and appeals; plus sign-in limits.
- **Key classes**: `Independence`, `TrustPolicy`, `SignalPolicy`, `FlagPolicy` (pure, tested); `TrustService`,
  `CommunityService`, `ModerationService`, `CaseRefs`, `RoleAuthorization`, `AuthThrottle`, `AttemptLimiter`.
- **Likely questions**: Why not show trust levels? (status systems invite gaming and make people trackable.) How do you
  stop sock puppets without phone numbers? (they keep backing the same things; linked accounts count once; probation.)
  Why can two flags not hide a well-confirmed warning? (flag-bombing would silence real danger.) Why can a moderator not
  decide an appeal of their own decision? (separation of duties.) Why are roles not in the JWT? (revocation would wait
  24 h.) Why are limiters in memory? (a DB log of IPs/e-mails or viewed areas is itself personal data.)
