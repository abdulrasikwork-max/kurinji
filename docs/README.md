# Documentation index

Start with `../CLAUDE.md` (permanent rules), then `HANDOFF.md` (latest state on top). Each phase has a results doc;
larger phases also have a design or plan doc written and approved before the work.

## Cross-cutting references
- `STATUS.md` — full status record per phase (moved from CLAUDE.md §2 on 2026-11-03): test counts, verification, code map.
- `DECISIONS.md` — the decision log with reasons (moved from CLAUDE.md §13 on 2026-11-03).
- `API.md` — every endpoint by phase; appendix = compact per-phase endpoint and config index.
- `HANDOFF.md` — one handoff per phase, newest first (results, state left behind, next steps).
- `DESIGN_SYSTEM.md` — IRL "Ink & Lime" design system; History section = earlier identities.
- `design/` — `irl-icon.md` (current icon), `irl/`, `kurinji-16-icon.md`, `kurinji-13f-direction.html` (history).
- `PLATFORM_CONSTRAINTS.md` — what Android allows and what it does not (honest limits).
- `TIMESTAMPS.md` — UTC storage rules and the one-time fix.
- `IRL_DIRECTION.md` — **the current product direction (2026-11-15)**: verified community, moments, "Who's in?", verification, safety placement, phases N0–N8.
- `PHASE_N6.md` — the approved N6 design (safety trim, automatic server-side SOS alerts, On my way; D20–D25 locked 2026-11-18; built 2026-11-18, "As built" B1–C; live SMS verified).
- `PHASE_N7.md` — the PROPOSED N7 design (notifications and the IRL signature; D26–D42; waiting for approval). Sheets `design/irl/n7-*.png`.
- `PHASE_N5.md` — the approved N5 design (responses and plans; D14–D19 locked 2026-11-17) and what each unit built.
- `PHASE_N4.md` — the locked N4 decisions and plan (moments, Today, Create, keep, daily question; voice deferred; 7-day new-member period) and what each unit built ("As built"): B1 done (backend moment core, V35 written but not applied), B2 to C not started.
- `PHASE_N3.md` — plan, the owner's 10 decisions and results of the verified MIET community (N3: membership, verification, bans, re-link, staff screens, V34).
- `PHASE_N2.md` — plan, the owner's decisions and results of phone-only sign-in (N2).
- `PHASE_N1.md` — plan and results of the strip phase. `history/CLAUDE_2026-11-15.md` — `CLAUDE.md` as it was before the direction change. `history/CLAUDE_2026-11-18.md` — the OLD social-code rules moved out of `CLAUDE.md` in N6-C (size limit).
- `EXPERIENCE_PLATFORM_DESIGN.md` — the PREVIOUS product direction (2026-10-22, superseded on 2026-11-15), decisions D1–D15; appendix = product history.
- `AUDIT_NEXT.md` — 2026-10-22 audit (safety findings still apply; Part II product direction superseded).
- `EMAIL_SETUP.md`, `EMAIL_VERIFICATION.md` — V25 e-mail verification (superseded by Phase 17B).
- `licences/` — third-party licences.

## Phases
| Phase | Docs |
|---|---|
| 1 | `PHASE1_README.md` |
| 2–3 | `PHASE2.md`, `PHASE3.md` |
| 4A–4D | `PHASE4A.md` (appendix: approved Phase 4 design), `PHASE4B.md`, `PHASE4C.md`, `PHASE4D.md` |
| 5 | `PHASE5.md` (Evidence Vault, hardening §10, restore §11) |
| 6–7 | `PHASE6.md` (place signals), `PHASE7.md` (trust + moderation) |
| 8 | `PHASE8_DESIGN.md`, `PHASE8.md` |
| 9 | `PHASE9_DESIGN.md`, `PHASE9.md` (9A, 9B, 9C, 9D results), `PHASE9C_CONTINUATION.md` (closed) |
| 10 | `PHASE10_DESIGN.md`, `PHASE10.md` (10A results) |
| 11 | `PHASE11.md` (SOS without internet, C1 sessions, 10B Rise) |
| 13A–13F | `PHASE13A_PLAN.md` + `PHASE13A.md` … `PHASE13F_PLAN.md` + `PHASE13F.md` |
| 14–15 | `PHASE14.md` (security audit), `PHASE15.md` (acceptance) |
| 16 | `PHASE16.md` (UX reset) |
| IRL redesign | `IRL_REDESIGN.md` (2026-11-11: Home · Explore · ✚ · You, short composer, You as a profile; A23 walk and fixes) |
| 17 | `D12_DEPLOYMENT.md` + `../deploy/README.md` (production deployment prep, blockers) + `../deploy/LAPTOP.md` (L1: friend pilot from a laptop through a Cloudflare Tunnel), `TARGETSDK36_PLAN.md`, `PHASE17J.md` (release readiness) + `RELEASE_PRIVACY_PLAY.md` (privacy / Play draft), `PHASE18_TRUST_PLAN.md` (planned: rating trust rules + the Next-person card), `PHASE17_PLAN.md`, `PHASE17A.md`, `PHASE17B_PLAN.md` + `PHASE17B.md`, `PHASE17C.md`, `PHASE17D.md`, `PHASE17E.md`, `PHASE17F.md`, `PHASE17G_PLAN.md` + `PHASE17G.md` (17G-1 Explore) + `PHASE17G3.md` (17G-3 optional AI understanding) + `PHASE17G2.md` (17G-2 "I went" + guarded rating) + `REAL_DEVICE_SPRINT.md` (real-device sprint: Track A/B record + Track C handoff) |

## As previously listed in CLAUDE.md §4 (kept verbatim, 2026-11-03)
├── docs/      API.md, PHASE17F.md (emergency: shake SOS, audio consent, last known, Nearby, radar, Safe Walk line), PHASE17E.md (social redesign), PHASE17D.md (location UX), PHASE17C.md (media), PHASE16.md (UX reset), EMAIL_SETUP.md, EMAIL_VERIFICATION.md (V25), PHASE15.md (acceptance), PHASE14.md (security audit), PHASE2.md, PHASE3.md, PHASE4A.md, PHASE4B.md, PHASE4C.md, PHASE4D.md, PHASE5.md, PHASE6.md, PHASE7.md, PHASE8_DESIGN.md, PHASE8.md, PHASE9_DESIGN.md, PHASE9.md (9A, 9B, 9C, 9D results), PHASE9C_CONTINUATION.md (closed), PHASE10_DESIGN.md, PHASE10.md (10A results), PHASE11.md (SOS without internet + 10B Rise), AUDIT_NEXT.md (2026-10-22 audit), EXPERIENCE_PLATFORM_DESIGN.md (the new product direction, Phase 13), PHASE13A_PLAN.md + PHASE13A.md (13A), PHASE13B_PLAN.md + PHASE13B.md (13B), PHASE13C_PLAN.md + PHASE13C.md (13C), PHASE13D_PLAN.md + PHASE13D.md (13D), PHASE13E_PLAN.md + PHASE13E.md (13E), TIMESTAMPS.md, HANDOFF.md, DESIGN_SYSTEM.md, PLATFORM_CONSTRAINTS.md, PHASE1_README.md
