# Phase 9C continuation: closed (2026-10-21)

This file was the bridge between the session that built 9C.0, 9A and 9C and the session that finished 9C. Every item
it listed is now done; the record of what was built, run and verified is **`docs/PHASE9.md` §9–§16**. Status words
per `CLAUDE.md` §3. The folder is still not a git repository.

## What the list said, and where it ended up

| Item (old §8) | Status | Where |
|---|---|---|
| 1. S15 Option B (9C.1) | implemented, compiled, tested, live-verified (MySQL, ACTIVE run), emulator-verified | `PHASE9.md` §9 |
| 2. Group members as case participants (design + privacy note first) | design note written (`PHASE9.md` §10), implemented with a whole-group pause, tested, live-verified, emulator-verified | `PHASE9.md` §10 |
| 3. Live ACTIVE check on MySQL | **25/25 PASS** (after one script-only failure, disclosed) | `PHASE9.md` §13 |
| 4. Emulator check of the moderator screen | PASS for content, FLAG_SECURE, the pause flow, the Option B text, night-mode structure; **TalkBack speech and dark-mode colours NOT VERIFIED**; Galaxy A23 NOT VERIFIED | `PHASE9.md` §14 |
| 5. Security and privacy review | written | `PHASE9.md` §15 |
| 6. Docs | `PHASE9.md`, `PHASE9_DESIGN.md`, `PHASE7.md`, `API.md`, `HANDOFF.md`, `CLAUDE.md` updated | — |
| 7. Mark 9C complete only after the user's review | **complete: reviewed and accepted by the user on 2026-10-21** | — |

Found and fixed on the way (`PHASE9.md` §11): the dismissed-group guard was too narrow (a withdrawal let a dismissed
group be asked again), and a ring member could pull an honest pair into the ring's core (sheltering behind their
dismissed place, or getting them paused with the ring). Both reproduced against the old code first.

## Still true (carry forward)
- **Shipped mode SHADOW.** ACTIVE needs an active moderator (none in the dev setup) and ideally real SHADOW counts.
- **Do not change:** the 9C.0 R0 guard; 9A (pinned measurements change only on purpose); no withdrawal lock for a
  COORDINATION case without flags; the one visibility definition in `SignalVisibility`; shipped config (demo values
  only as command-line arguments); no migration for 9C; no ML library, Python, new dependency or permission.
- **Privacy constraints:** coord-1 only opens a case; moderators see case pseudonyms and bands only; logs hold counts
  only; the support graph lives in memory for one run; Option B stores nothing new; no stored "these accounts are
  separate" fact.
- **Phase 9D gate:** 9C was accepted on 2026-10-21; 9D (and 9B) start only when the user briefs them separately.
