# Phase 17G-2 — "I went" and the guarded place rating (U5) (2026-11-03)

Status: **17G-2 implemented, compiled, tested, live-verified on real MySQL (V30 applied), emulator-verified (normal and 2.0x text).**
**Not verified:** a physical phone, TalkBack by ear, and how the daily-value memo behaves across a real IST midnight (tested with a moved
clock only). The user approved the rule change (U5 wording) and every default on 2026-11-03; plan: `PHASE17G_PLAN.md` §4.

## 1. What was built

**"I went" is now a private record. From it a person may give an optional 1 to 5 rating. A place shows ONE average, in words, only once at
least 5 people who went (and whose accounts are past probation) have rated it. The average carries no count, name or time, changes at most
once a day, and orders nothing.**

| Part | What |
|---|---|
| Rule | **U5** in `CLAUDE.md` §1; `EXPERIENCE_PLATFORM_DESIGN.md` §2.7 / §3.5 amended; the two code comments that said "no ratings" updated |
| Database | **V30** `place_visits`: `(id, user_id → users, place_id → places, rating TINYINT NULL, created_at, rated_at NULL)`, both FKs `ON DELETE CASCADE`, `UNIQUE (user_id, place_id)`, CHECK rating NULL or 1..5, CHECK `rated_at` set exactly when a rating is, index (place, rating). The rating lives on the visit row, so it cannot exist without "I went" **by construction** |
| Policy | pure `RatingPolicy`: nothing before 5 counted ratings; `(3.5 × 5 + sum) / (5 + raters)`, one decimal, half up (five 5-star ratings = **4.3**); impossible input shows nothing |
| Service | `PlaceVisitService` (went, take back, rate, take the rating back, `yourVisit`, merge) and `PlaceRatings` (the text, computed at most once per **IST day** per place, kept in memory) |
| API | `PUT/DELETE /api/places/{id}/went`, `PUT/DELETE /api/places/{id}/rating {rating 1..5}`; the place page `GET /api/places/{id}/posts` gains `rating` (text or null) and `yourVisit` (the caller's own, or null) |
| Rules in code | "went" only for a place the caller can **see** (a post there they may read through the Edition rule, or one they wrote), else 404; a rating needs the visit first (409); a person paused from posting cannot rate (403); two daily limits (20 new marks, 40 rating writes); only enabled, non-erased accounts **past the 72 h probation** count; place merges move visits and keep the rated row; account erasure covers the table |
| Android | "I went here" (place page) and "I went" (post page, when it names an exact place) mark the visit and open **"How was it?"**: five numbered buttons ≥ 48 dp with spoken "Rate 3 out of 5" ("3 out of 5, your rating" for the chosen, a lime fill AND words), then Share your experience (the composer, place filled in), Take back my rating, I did not go here, Done. The place page shows "★ 4.3 out of 5 · from people who went" (a drawn star plus words, never a count) and the person's own state on the button. Nothing is stored on the phone |

## 2. Decisions (all defaults approved by the user, 2026-11-03)

- **U5 wording** as in `CLAUDE.md` §1 (see `DECISIONS.md`). The rating shows on the **place page only**: Explore cards stay rating-free (the
  Explore isolation guard is unchanged). No "places I went" list in V1 (it would be a route record).
- **Drawn star plus words**, not the plan's "⭐ 4.6 IRL" emoji (the design system uses drawn icons; the words carry the meaning).
- **Only accounts past probation count** (72 h): five fresh throwaway accounts cannot create a rating. Numbers: 5 raters, prior 3.5 worth 5, 20 marks and 40 rating writes per person per day.
- **Daily memo, in memory.** The first read of a place's rating on an IST day fixes its text until the next IST day, so "average before and after one more rating" cannot be read within a day. Consequences, accepted and documented: the line appears (or changes) at the earliest the day after the ratings; a backend restart recomputes it; with several backend instances each has its own memo.
- **Merges in code, not SQL:** a bulk `DELETE` that reads the table it deletes from is refused by MySQL (error 1093); `mergePlace` moves rows one by one, keeping the rated row.
- Own rating changes use `PUT` (idempotent); taking "I went" back is a `DELETE` that answers 204 whatever was there.

## 3. Security, privacy and rules review

- **Private by construction:** every per-person read is by the owner's id; the only cross-person read is `totals()` (two numbers, no person). Tests: no other person's id or handle in any page; no count/average/raters/votes/time field in any DTO (`RatingIsolationTest`).
- **Isolation:** only a named list of files may mention visits or ratings; nothing that orders or lists (`PostRepository`, Explore, Edition, marks, places) reads them; no outbound call, scheduler or logging in the rating code. On the phone: nothing stored, no list of visits, a test fails on any sort that mentions a rating.
- **Abuse:** one row per person and place; limits; probation filter; paused people cannot rate; Bayesian average; daily memo. **Honest limits:** "I went" is self-declared (no proof of a visit); a small place with 5-6 raters can still be nudged by a group of older accounts; blocked people's ratings count in the anonymous average (it shows no authors).
- **Visibility:** marking needs a place the person can already see, so the endpoint cannot probe hidden places (404 = same as unknown).
- **Wording:** `SocialTextsTest` stays strict (no `%d` in social strings): the person's own number is passed as text; the public line is the only rating text and says "from people who went".
- Found while testing: the live-script's first attempt to make accounts "new" again was refused by MySQL's own `ck_users_phone_verified_after_created`, so the real database protected those rows; the check was redone updating both columns.

## 4. Results

| Check | Result |
|---|---|
| Backend | **883 / 0** (+25; 2 skipped = the opt-in real-MySQL checks): `RatingPolicyTest` 6, `Phase17g2VisitsApiTest` 10, `Phase17g2ProbationAndLimitsTest` 3, `RatingIsolationTest` 6; `SocialAccountDeletionTest` seeds a visit |
| Android | **966 / 0** (+16 `Phase17G2VisitsTest`); lint **0 errors / 104 warnings** (unchanged); `assembleDebug` OK |
| Python | 83 / 0 (unchanged) |
| MySQL | backup `safesphere-before-v30.sql` first; dry run on a scratch table **15/15** (4 accepted, 11 refused: out-of-range ratings, rated_at/rating mismatch both ways, two foreign keys, NULLs, duplicate pair); applied by Flyway (V30, 2026-11-03) |
| Live API (real MySQL) | MySQL refuses rating 7; went / twice / one row; place with nothing to read 404; page shows only my own visit and no one's handle; rating before went 409; 0 / 6 / -2 / null are 400; change; take back; "I went" taken back deletes the row; five aged 5-star raters show **4.3** as text; the value read earlier that day stays (memo seen live); five accounts on probation show none |
| Emulator (API 37) | place page and post page: "I went" → sheet → rate 4 (lime fill + words) → place page shows "You went here · 4 out of 5" and "4.3 out of 5 · from people who went" → take the rating back → "I did not go here" → button back to "I went here"; "Share your experience" opens the composer with the place filled in; **2.0x text**: place page and sheet fully readable. A first run clipped the long button label; the two place buttons are now stacked full-width |

## 5. Manual verification checklist (the user's)

1. Open a place with posts → "I went here" → rate → close → the button says "You went here · N out of 5".
2. As a second person (older than 3 days), five people rating the same place: after the next IST day the place page shows the line; it never shows a number of people.
3. "I did not go here" removes the visit and the rating.
4. 2.0x text and TalkBack on the sheet (each number is read "Rate 3 out of 5"; the chosen "3 out of 5, your rating").
5. Check that nothing in Explore or Discover ordering changed.

## 6. Viva notes

- **Why can a rating not exist without "I went"?** One row holds both; the only way to rate is to update your own visit row.
- **Why a daily memo and a probation filter?** To make single ratings hard to read off the average and fresh throwaway accounts count for nothing; the Bayesian prior stops a few extremes from swinging it.
- **Why no count and no list of who went?** A count is popularity; a list of who went where is a movement record (the "nobody can monitor another person" rule).
- **What is not guaranteed?** That a visit happened (self-declared) and that a small place's average cannot be nudged by several older accounts.
