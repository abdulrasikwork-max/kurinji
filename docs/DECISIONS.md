# Decision log (moved verbatim from CLAUDE.md §13 on 2026-11-03)

Every important decision with its reason, in the order it was made (viva material). The permanent ones are
summarised in `CLAUDE.md` §13. Add each new decision here as one row; add it to CLAUDE.md too only if it is a
permanent rule. (The row "17B: phone OTP replaces e-mail links" was split over two lines in CLAUDE.md and is joined
here so the table renders.)

| Decision | Reason |
|---|---|
| XML Views + Fragments, no Compose | Approved in Phase 1; viva-friendly; consistency |
| Manual `AppContainer`, no Hilt | Small object graph; less magic to explain |
| Feature-first packages | Features grow independently; future modularisation |
| Flyway, `ddl-auto=none` | Reviewed, versioned schema changes |
| JWT 24 h, no refresh tokens | Kept simple in Phase 2; revisit later |
| Spring resource-server JWT (not a custom filter) | Less custom security code |
| Foreign record → 404 | Does not reveal that IDs exist |
| "One primary" / "one active" via generated columns + unique index | Correct even under concurrent requests |
| Countdown client-side; event recorded at zero | Cancelling records nothing |
| Phase 4 in gated sub-phases 4A–4D | Size and risk; build + test between each |
| Location upload queue in memory only; phone stores ids + start time only | Minimum location retention on the device |
| `UNIQUE (emergency_id, recorded_at)` + all-or-nothing batches | Offline re-sends are safe and simple |
| `START_NOT_STICKY` tracking service, recovery on next open | A restarted service would lack while-in-use location access |
| Phone counts down `due − serverTime`; server checks "missed" against its own deadline (30 s) | Clock drift irrelevant; reports stay plausible |
| SOS links the active walk server-side by JWT user | No client-supplied journey ownership |
| Home active-session banner | Tracking can only resume from a visible screen after a restart |
| Movement concern = the same "Are you OK?" prompt opened early (L1 + reason), L2 on no answer | Ladder "L1 minor concern → prompt; L2 no response/anomaly"; bubble only warns, never L3. Confirmed by the user 2026-10-19 |
| Destination coordinates on the server, deleted at a normal end | Phone keeps no destination/location; minimum retention |
| Arrival is a hint, never auto-complete | Ending monitoring near the door is unsafe |
| Phone sends Safety Circle SMS (opt-in), server only records status | No server gateway in Phase 4; SMS works offline; server keeps no number or text |
| All-clear / resolved follow-up messages; SOS cancelled within the location wait tells nobody | Calm, proportional communication; no needless alarm |
| Emergency Context Packet: read model + explicit allow-list projections, owner-only endpoint | Approved design; nothing copied; no accidental leak |
| Level 4 = `ACTION_DIAL` 112, recorded as "dialer opened" | No claimed emergency-service integration; no CALL_PHONE |
| Evidence encrypted on the phone, per-item key in the Keystore; server stores ciphertext only | Server/DB breach reveals no evidence; delete key = crypto-shred |
| Escrow to an OFFLINE RSA recovery key (server has the public half only) | Recoverable after phone loss without making the server able to read evidence |
| Server re-hashes every byte before SEALED; hash-chained custody ledger incl. downloads | "Verified" is a server fact; tampering and access are auditable |
| Audio in 30 s AAC/ADTS parts; photo/video via camera app | Loss limited to one part; crash-recoverable; no CAMERA permission, no new library |
| Deletion needs the password and is refused during an ACTIVE emergency | An unlocked phone in the wrong hands cannot destroy evidence |
| Deletion also refused 24 h after the end; 5 wrong passwords → 429; attempts logged in custody | Coerced "I'm safe" + immediate deletion; online guessing; attempts are evidence |
| Vault viewing gated by the phone's screen lock; Keystore key still NOT auth-bound | Capture while locked; removing the screen lock would otherwise destroy all keys |
| Upload resumption via platform JobScheduler (not WorkManager) | No new dependency; same OS mechanism |
| Phone re-checks the vault copy on the server before deleting its own | "Verified once" ≠ "intact now"; never delete the last good copy |
| Restore on a new phone: user passphrase → PBKDF2 → wraps a per-account recovery key that wraps item keys | Self-service recovery without server readability; passphrase change = one re-wrap; approved 2026-10-20 (option D) |
| Recovery envelope only with the account password; RK reset deferred | Stolen token gives no guessing material; one RK keeps every copy openable |
| Community = place signals: merge per (cell, category), one answer per person, bands not counts, no text/photos/authors | Safety intelligence, not engagement; report bombing pointless; no accusations of people (Phase 6, autonomous decision under the user's brief 2026-10-20) |
| Phone sends geohash cells only; precision earned by a 2nd person (k = 2) | Server never has coordinates; one account cannot pin a home |
| Random publication delay; only `counts_from` stored; 24 h new-account probation | Reporter cannot be linked on the spot; real time never recorded; sock puppets slower |
| Area look-ups = own area only, 15 distinct areas/h, limiter in memory | Anti-scraping without a DB log of where viewers look |
| Safety Radar drawn by the app, no map tiles | No new dependency / API key; tile servers would learn viewed locations |
| Trust private (tiers only weigh contributions; never shown, not even to the person) | Trust > popularity; no status system, nothing to track or game (Phase 7, autonomous under the user's brief 2026-10-20) |
| Independence rule: accounts sharing ≥ 3 live signals and ≥ half of their activity count once | Sock-puppet resistance without device ids, phone numbers or IPs; fades with retention |
| Content hidden automatically (reversible quarantine), people restricted only by a human | Automatic punishment fails unsafely; flags cannot silence a well-corroborated warning |
| Case-scoped HMAC pseudonyms, reason codes only, audit log, appeal to a different moderator | Moderators act without knowing or tracking people; moderator abuse is auditable |
| Community pause never touches SOS / Safe Walk / Safety Circle / evidence | A safety app must fail safely |
| Phase 9: no learned model for anything users see; measure first (9A attack simulation through the real API) | No real data, no ground truth, deliberate forgetting; a model would learn our test scripts (`PHASE9_DESIGN.md`, 2026-10-21) |
| coord-1 (k-core + exclusive pairs) only opens a case for a human; SHADOW by default | Catches the rings and padded pairs 9A measured; honest communities misread as rings must cost moderator time, never their voice |
| COORDINATION cases do not lock withdrawal (flags still do) | The 409 told a ring it was detected; withdrawing removes the fake anyway (9C, changes a Phase 7 decision) |
| S15 Option B: a moderator's DISMISS of a COORDINATION case frees THAT signal's counting people (frozen to those seen), via the closed case row | Honest groups regain their voice without storing a social fact; a mistaken dismissal cannot be topped up with puppets (9C.1, the user's choice of option B) |
| A case lists the coord-1 group's members; one RESTRICT can pause them all | One by one was measured to be fragile (the pattern vanishes before the last member); each pause is still audited and appealable per person (9C.2) |
| Group membership = backs ≥ 2 of the group's places | A ring member confirming an honest pair's place pulled them into the 2-core (found in testing); detection unchanged (9C.3) |
| Neighbour join in the clean-up job, only once the report counts; lone reports only, into OLDER signals, stored as CONFIRM | A write-time merge (the design) is a silent probe of a single report's hidden block and misses pairs that were both pending; "older only" stops capture; the effect equals "I saw this too" (9D-1) |
| Side neighbours only, never corners | Measured: 300 m street 39.6 % → 78.6 % honest pairs joined, 2.0 % of places 300 m apart joined (corners: 94.6 % / 17.9 %) (9D-1) |
| rules-1.1: safe places alone count once (information ≤ LITTLE) | "No confirmed concerns" could only come from havens, and two accounts faked it; a change that only removes reassurance ships (9D-3) |
| rules-2 stays SHADOW (counted, never shown) | It also downgrades honest pairs' "extra care"; no real-world evidence of the honest/fake proportion; weakening a warning needs evidence (9D-2) |
| Outcome counters: weekly, system-wide, after commit, best effort, ADMIN-only; nothing that decides reads them (9B) | Rules can be evaluated without keeping history, places or people; measurement can never break or steer a safety app |
| The rule that opened a COORDINATION case is stored on the case (V15) | A whole-group pause removes the pattern before the decision; recomputing would credit the decision to nothing (9B, deviation from the design) |
| Counter CHECKs: upper-case dimension, versions with one dot, binary-collated closed lists | A cell, id or free text cannot be stored; `rules-1.1` must fit; an `IN` CHECK under a case-insensitive collation let `support_core` through (dry run) |
| Roles read from the DB per request; ADMIN only via SQL | Instant revocation; no API path to the top privilege |
| Sign-in/sign-up limits in memory, failures only, per account + per address | No IP/e-mail log at rest; short account window limits victim lock-out |
| Safety Intelligence = deterministic rules on two axes (confirmed concern, information), no score, no ML | Explainable, attack-resistant (counts independent people), honest about "unknown"; no ground truth for ML (Phase 8, autonomous under the user's brief 2026-10-20) |
| Picture computed from the ONE visible set; neighbourhood = max of cells; havens capped and never cancel concerns | No timing/moderation oracle; busy areas not penalised; lure resistance |
| Independent freshness (linked group = its oldest member) for intelligence | Sock puppets cannot keep an incident "today" |
| Pattern memory = one decaying number per ~1 km area and group, k-gated (3 episodes), reset on removal | Recurrence without history, people or times; forgets within ~8 months |
| App-drawn radar cells, no heat map, no tiles | Unknown is visible (hatched), real precision, no third-party location leak |
| "Check the way" computed on the phone from ~5 km region packs | Server never learns a route; only confirmed signals rank; unknown ≠ safe |
| **No monitoring of a person by anyone else, permanently** (no family/partner/guardian mode, no location on request) | Safety the person controls expands their world; a monitoring feature would turn a safety tool into a control tool (Phase 10) |
| Revocation by a credentials stamp in the token, exact equality, checked on every request (Phase 10A) | Ends stolen / old sessions and disabled accounts at once without refresh tokens; no clock or second-rounding issue (deviation from `iat`) |
| Password change refused during an ACTIVE SOS or Safe Walk (Phase 10A) | Revocation would otherwise let anyone who knows the password cut a running safety session off the server |
| Account deletion: password, refused during / 24 h after an emergency, 7-day grace with a Home banner, cancel needs the password, sign-in does not cancel, postponed by an emergency, files before rows (Phase 10A) | Coerced deletion is recoverable and visible; fresh evidence is protected; no ciphertext left without an owner |
| Accounts named in the moderation audit log are anonymised, not deleted (CHECK-enforced tombstone) (Phase 10A) | The audit trail survives; the person's data does not |
| Wrong passwords on account endpoints answer 409, not 401 (Phase 10A) | A 401 means "signed out" on the phone |
| The phone removes a deleted account's evidence only on the server's `account_deleted` answer or the person's tap | A sign-out must never destroy evidence; a cancelled or postponed deletion must never wipe a living account |
| Token renewal for the same sign-in, always during an SOS/walk, else ≤ 30 days since the password (10A.4) | A long SOS never loses its server connection; renewal is safe because a password change revokes every token |
| Another sign-in ends an ACTIVE SOS only with the password, and the record says so (10A.4) | Nobody can silently end someone's SOS; a lost starting phone does not trap the person in an endless SOS |
| No automatic "resolved" SMS when an SOS was ended from another phone (10A.4) | Only the person on a phone can say they are OK |
| Only working rows on every screen; no "Coming soon" anywhere; tab "Nearby"; summary leads with safe places; no "avoid" (Phase 10A) | Honesty is the product's claim; places, not people; going out, not staying away (`HonestyTextsTest`) |
| Every SOS starts on the phone; the server is told later with a client reference (Phase 11) | SMS and GPS work without internet, the server does not; the emergency button must not depend on a round trip; one path for online and offline |
| Phone times only for an SOS the server could not hear of, clamped; idempotency per person | Exact server times online; a true record offline; no cross-account oracle |
| Rise phone-only, encrypted per account, no Home card, no notifications, no private incident note (10B) | Coercive control is the main threat model; a note of an incident is the most dangerous text to be found and is not evidence |
| Device sessions with a rotating refresh secret (SHA-256 stored, 60 s lost-answer grace, later reuse ends the session, 90-day sliding idle limit) (C1) | An SOS after weeks unopened must reach the server without the password; copies are noticed; revocation stays immediate |
| Expired ≠ revoked on the phone: a refused refresh is "sign in to sync" and deletes nothing (C1) | Authentication failing for sync must never destroy a running SOS, its ending or the offline Safety Circle copy |
| Self-doubt vs. safety concern; support never ranked; stereotypes never named (10B, tested) | Worries lead to preparation, doubts to proof; support is strength; naming a stereotype repeats it |
| **Final product direction: a social platform for real-world experiences; safety becomes the trust layer underneath (2026-10-22, the user's decision; design approved)** | A safety app is opened only when something is wrong; experiences give an everyday reason to open it, and the safety system is why people can explore with confidence (`EXPERIENCE_PLATFORM_DESIGN.md` §1–§2) |
| Content is written for the next person; 🦋 = "I want to experience this" (saves to the Cocoon), no public counts | Separates the product from Instagram: intent and real-world impact instead of approval and popularity |
| No followers, mutual Connections only, private connection lists, no DMs, no presence | Following someone's real-world posts is monitoring; DMs are where most harassment happens |
| Location privacy built into the post shape (cells, precision per post, time bands, private places on the phone, no live posting) | Public experiences must not reveal anyone's routine |
| AI produces evidence, a deterministic policy decides, only humans restrict people; collapse rather than remove; no shadow bans (D2, decided 2026-10-22) | Moderation that can be explained and appealed; an AI mistake must not silence anyone |
| Tamil/Tanglish abuse model as the research contribution; Chennai launch community (D5, decided 2026-10-22) | The launch community writes that way; general moderation tools handle it poorly; measurable, publishable work |
| 13E: rules (normaliser + hashed lexicon + context rules) decide; the linear model is built but OFF; no transformer (E1/E2) | No D3 dataset exists; a model trained on developer-written text learns the developer; a model must beat the rules on held-out data without more false actions on banter, counter-speech and identity mentions before it acts |
| 13E: the Python service gives evidence, Java `DecisionPolicy` decides, the machine never removes or restricts (E3/E5/E11) | D2; decisions tested and explainable; humans keep every serious decision |
| 13E: outage = publish; only new accounts wait, at most 2 h (E7) | Infrastructure failure must never silence people |
| 13E: quoted / reported speech and identity terms never act alone (E15/E16) | People describing harassment they faced, and people naming their own community, must not be silenced |
| 13E: self-harm never stored, only a support line (E12) | Health data is the most sensitive; support, not surveillance |
| 13E: one normaliser spec, two implementations, shared vectors, byte-identical hashed lexicon on the phone (E21/E22) | Phone nudge and server check agree; the app never contains readable slurs |
| 13F: visible brand Kurinji now; package/applicationId and code names stay SafeSphere (F1, the user's decision) | The phase is the Kurinji identity; D1 trademark/domain/store checks remain open |
| 13F: mist/ink/bloom palette, yeoman orange for intent only, bundled Young Serif + Mukta Malar (F2) | Not the cream-serif-terracotta cliché; colours from Neelakurinji and the Tamil Yeoman; one face for Tamil and Latin |
| 13F: postmark = where/when showing only the revealed precision; ticket = the tip; drawn butterfly; kolam for empty states | Privacy made visible; "written for the next person" made concrete; no emoji |
| 13F: safety banners in MainActivity on every tab and reading screen | Safety guarantees must not depend on one tab being open |
| 13F: SOS shortcut/tile start the countdown via a non-exported activity + in-memory request (F3, F11) | Fast access; another app cannot forge a start through the exported launcher activity |
| 13F: "Let someone know" = the person's own share sheet, editable note, no location, no link (F4) | Care without monitoring; nothing stored or sent by Kurinji |
| 13F: "Going there?" bridge imports neither social nor safety code; Safe Walk only gets a pre-filled field | Keeps the isolation; nothing starts by itself |
| 14: every list of others' posts is ordered by `visible_from`, never `created_at` (also the profile list) | The order of a list must not reveal the time the Q1 delay hides |
| 14: on a perspective case, the post's author counts as taking part (case and appeal) | They are a party and can already hide it; deciding about its writer is a conflict of interest |
| 14: never catch a unique-key error inside a transaction and carry on; write the row in its own transaction or retry the whole attempt in a new one | Spring marks the transaction rollback-only, so the commit fails (500) and "it already exists" cannot be honoured |
| V25: no session of any kind before the e-mail is verified; existing accounts grandfathered | Ownership of the address is proven before the account can act; no production users exist, every row is a dev/test account |
| V25: token in the URL fragment, confirmed by a tap on a static same-origin page; no App Link | The fragment never reaches a server log or Referer; scanners cannot use the link up; App Links need an HTTPS domain that does not exist yet |
| V25: resend needs e-mail + password, always 202, sent asynchronously | Only the owner can trigger mail; answer and timing reveal nothing about accounts |
| V25: SMTP via `spring-boot-starter-mail` behind `VerificationMailer`; nothing is sent without MAIL_HOST + MAIL_FROM | Provider-neutral; credentials only from the environment; mail being unavailable never verifies anyone |
| U1 (2026-10-26, the user): a post shows the number of comments the viewer can read; no other public count | A comment number says "there is a conversation here" without ranking people; computed through the comment visibility rule, so it equals what the viewer could count themselves |
| U2 (2026-10-26, the user): "Talking now" ordered by the newest visible comment, never by totals | Shows what people are reacting to without a popularity ranking that rewards pile-ons |
| U3 (2026-10-26, the user): Discover auto-loads pages but always ends ("You're all caught up") | Natural browsing without an endless feed |
| U4 (2026-10-26, the user): red/black/white brand; emergency UI = siren icon + words + black/red treatment, never colour alone | A bold, recognisable social brand; the safety meaning of the SOS UI survives because it never depended on hue alone |
| Phase 17 decisions (2026-10-27, the user; `PHASE17_PLAN.md` §3): D-1 phone OTP via a provider-neutral sender, e-mail + password stays the sign-in; D-2 AI Explore language understanding by the Claude API with request text + candidate place facts only (amends D2 for this one use), deterministic engine works without it; D-3 butterfly = reaction, Saved separate, no public count; D-4 shake SOS only (opt-in, app open or Safe Walk), voice deferred; D-5 cities table; D-6 video without metadata, tap to play, no new player library; D-7 daily state for connections only, clears at midnight; D-8/U5 ratings only from people who went, shown from 5, Bayesian, never a sort by rating/activity alone; D-9 Nearby reports stay without text/photos | Recorded as each sub-phase's ground rules; each still gets its own plan |
| 17C: metadata boxes in a video are overwritten (renamed `free`, zeroed), never cut out | Cutting bytes out of a moov in front of the mdat shifts every chunk offset and breaks playback; overwriting leaves no metadata byte and keeps the file valid |
| 17C: a video's poster frame is made on the phone, sanitised as a photo and stored under the video's storage key; no poster = no attach | Every image view keeps working; every key-based deletion removes both files; server-side "no partial post" |
| 17C: video plays only after a tap, streamed with the sign-in header, never written to the phone | No autoplay; Q3 (nothing about other people stored on the phone); ranges let the player seek |
| 17B: phone OTP replaces e-mail links; e-mail + password stays the sign-in; the server makes and checks codes, a provider only delivers (`OtpSender`); dev sink by default, Fast2SMS only with the user's key | Sign-ups were stuck (no mail server); a provider-neutral sender keeps codes, limits and privacy rules in our tested code; Rs 100 budget fits Fast2SMS (KYC + first top-up) |
| 17B: an interrupted sign-up resumes by signing in (new challenge token, same live code, no second SMS); nothing about a verification is stored on the phone | A closed app must never leave an unusable account; the token is a credential, kept in memory only |
| 17B: area-level location reads use balanced accuracy and the platform's recent fix (found on the A23 indoors) | A GPS-only request returned nothing indoors although a good Wi-Fi/cell position existed; adding a place still needs ≤ 100 m |
| 17A: the server sends stable codes (`AGE_NOT_CONFIRMED`, `PROFILE_NEEDED`) for refusals the app must act on; the app keeps ONE age state and opens the age screen by itself at most once per sign-in | A refusal shown on every screen with no way forward was the reported bug; codes avoid matching on words |
| 17E (E17-1): "Ink & Lime" brand; supersedes U4's colour only | With red no longer the brand, red means only emergency again (an extra cue on top of U4's shape-and-words rule); lime is the young growth on the Kurinji hills |
| 17E (D-3, E17-5/6): butterfly = reaction on others' Experiences, Save = private bookmark of Experiences and Asks; both idempotent; Cocoon rows became saves, no reaction invented | One mark for both made a private list into a signal for the author; optimistic toggles must survive double taps and lost answers |
| 17E (D-7, E17-4): today's state is a mood or intent from a closed list, connections only, the IST day only, no time, no history | "Travelling" / "free now" reveal absence from home and presence |
| 17E (E17-8): profile photo square, re-encoded twice, a new random key per upload, served only through the profile visibility rule; moderators can remove it (not a strike, case stays open) | Caches can never show an old photo; a kept URL stops working on a change or a block; new public imagery needs a moderation path |
| 17E (D-5, E17-7): one city catalog file read by the backend, bundled in the app and seeded by V28 (tested equal); city search runs on the phone | H2 tests run without Flyway; search words never leave the phone |
| 17E (17E-V2): on narrow screens / large text the marks lose their words before any button (`MarksFit`), spoken labels unchanged | Found on the emulator at 320 dp / 2.0x: Save was pushed off the card |
| IRL rebrand (2026-11-03, the user): visible name IRL everywhere a person reads it (strings, SMS, one server message); package, applicationId, signing and code names unchanged | A rebrand must update the installed app in place; renaming identifiers would make a new app and break existing installs and data |
| IRL icon "the Meet": two paths meeting under an off-axis sun; adaptive icon only (no density PNGs at minSdk 26) | Real-world connection, togetherness and exploring in one silhouette that holds at 48 px; a centred dot read as a figure, which the brief ruled out |
| IRL opening screen: "LET'S GO →" only on the first run; returning people are routed on by themselves; the button is pinned and never scrolls away | The opening must never add a tap between a returning person and SOS; a first-run call to action must always be reachable |
| 17F (F17-1, D-4): the shake gesture is opt-in (off by default, per account on the phone), three firm back-and-forth shakes in 2 s, and it only ever opens the SAME cancellable 5 s countdown; server trigger `SHAKE` accepted (VOICE and DURESS_PIN still refused), no migration (the column has no CHECK; confirmed on MySQL) | A gesture must never start an SOS without a chance to say "no"; back-and-forth reversal tells a shake from running, a knock or a drop; the trigger is only a label for the person's own history |
| 17F (F17-2): the accelerometer is read only while IRL is on the screen or a Safe Walk runs (the one existing foreground service keeps the app alive) AND, off the screen, only if notifications are allowed; off the screen the countdown is a notification with a Cancel button and at zero the SOS starts on the phone exactly as the SOS screen would | No always-on listening, no new service, no boot receiver; with no notification there would be no way to cancel, so it then does not listen in the background at all |
| 17F (F17-3): voice SOS stays unavailable and the app says so (no "coming soon") | Always-on microphone listening is not reliable on Android 14+ and would be an always-on microphone |
| 17F (F17-4, amends the Phase 5 rule "audio only on tap"): audio can start by itself in an SOS ONLY after the person agreed in a one-time question (first visit to You → Safety, default No, dismissable with Back = ask next time, never shown while an SOS or walk runs, changeable in SOS options), with the microphone permission asked at that moment (never during an SOS), from the visible SOS screen, once the server has the SOS, once per SOS (a Stop is final, also after a restart) | The tap in the middle of an emergency is the thing a frightened person cannot do; consent in advance keeps the recording person-controlled, visible (notification) and encrypted on the phone |
| 17F (F17-5): an old position is called the LAST KNOWN one, with its time and age, whatever the status says (the Safety Circle message always said "Last known location") | A stale position shown as current could send help to the wrong place |
| 17F (F17-6, D-9): the Nearby report is one step (choose, privacy text, share) with no free text and no photo | Fewer taps in a stressful moment; a place signal still describes a place, never a person |
| 17F (F17-7): the Safe Walk line is a straight line drawn by the app from state held in memory, labelled "not a route"; no trail, no stored destination, hidden when the destination is unknown | The Safety Bubble already measures against this straight line; showing it makes the "Are you OK?" prompts understandable without claiming a route is safe or known |
| 17F (V5, user-approved 2026-11-03): the Nearby report is a Material bottom sheet with Share and Cancel in a footer outside the scrolling choices, not a system alert dialog | On the A23 at 2.0x text the dialog gave its choices nearly all the height and Cancel was clipped; a footer outside the scroll area can never be pushed off the sheet (capping the dialog height would have been more fragile) |
| 17F (V6-V8): words drawn on the Safe Walk line and the radar are placed by pure, tested helpers that never move a word onto a mark or another word; marks keep clear of the person's ring; a signal's badge sits under its title | Text follows the phone's text size (sp), so placement must hold at 2.0x; the radar and the line only change where things are DRAWN, the list and the checks are untouched |
