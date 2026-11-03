---
paths:
  - "android/app/src/**/feature/social/**"
  - "android/app/src/**/feature/profile/**"
  - "android/app/src/**/feature/going/**"
  - "android/app/src/**/feature/moderation/**"
  - "android/app/src/**/core/textcheck/**"
---
# Android social features (moved verbatim from CLAUDE.md §5 on 2026-11-03)

Profiles and connections (13A), experiences (13B), discovery (13C), moderation v2 (13D), the on-phone text check
(13E), media (17C), social redesign (17E).

> **Read with care (checked against the code on 2026-11-03):** this text was written phase by phase. Since 13F
> there is no `feature/home` (no Home screen, no `HomeStatus`): "Home banner" now means the session banners in
> `MainActivity` (`SessionStatusViewModel`), "Profile" means the You tab. Since Phase 16/17E the third tab is **Saved**
> (`SavedFragment`, nav id still `cocoonFragment`); Cocoon / Want / Been are gone. `feature/rise` was removed in 13F.
> When this text and the code disagree, the code wins: flag it to the user.

- **Profiles and connections (Phase 13A, `feature/social`, `docs/PHASE13A.md`):** reached from a Profile-tab row until
  13F. `SocialRepository` / `RemoteSocialRepository` (ApiCallHandler; NOTHING stored on the phone). Pure mirrors of the
  server rules: `AgePolicy` (18+ on the UTC date; also used by `RegisterViewModel`), `HandlePolicy`, `ProfileInputPolicy`
  (`SocialPolicyTest` uses the backend's cases). Screens: `ConnectionsFragment` (own profile, find by exact handle,
  requests both ways, connections, invite codes shown once with share/copy, redeem), `ProfileSetupFragment` (one-time
  age confirmation for pre-13A accounts, handle/name/bio/city/language and interest chips), `PersonProfileFragment`
  (Connect / Accept / Request sent / Remove / Mute / Block; one-shots `message`, `closed` must be consumed),
  `BlockedMutedFragment`; shared `PersonRowAdapter`, `DateOfBirthPicker` (text mode, reattached after rotation).
  DTOs fail closed (unknown relationship = NONE, unknown codes dropped); invite codes and dates redacted in `toString()`.
  `SocialTextsTest`: no counts, follower/like/online/safe wording; quiet actions say "not told".

> ⚠ The 13C paragraph below is partly superseded by 17E (checked 2026-11-03): `CocoonFragment`, `CocoonViewModel`,
> `BeenInvitation`, Want/Been and the `/api/cocoon` and `/butterfly` endpoints no longer exist; see the 17E paragraph
> (`PostMarks`, `SavedFragment`, `SavedViewModel`).

- **Explore (Phase 17G-1, `docs/PHASE17G.md`):** `ExploreFacets` (the tag file, bundled), `ExploreLexicon` + pure `ExploreQueryParser` (the typed words are understood ON THE PHONE from `explore/explore-lexicon-v1.tsv` + the tag names: longest phrase, a city by name, limits 2/4/1/1, unknown words returned as unused, negation not understood and shown as unused; the Tamil/Tanglish list is a DRAFT) → `ExploreQuery` (closed lists only; `ExploreRequestDto` has exactly 4 fields) → `ExploreApi` / `RemoteExploreRepository` (`AppContainer.exploreRepository`; DTOs fail closed) → `ExploreViewModel` (`ExplorePhase`; typing replaces the chips, chips adjust, any change clears the answer) + `ExploreFragment` / `ExploreHitAdapter` / `MultiChips` (entry: Discover head; a result opens the place page; no history, nothing stored). Composer: optional "Good for" tags (`ComposeUiState.facets`, ≤ 4, chips disabled at four, Experience only); post page shows a read-only tags chip. `Phase17GExploreTest` (words, parser, DTOs, ViewModel, source guards: no location/storage/AI service in the Explore code).
- **"I went" and the guarded rating (Phase 17G-2, `docs/PHASE17G2.md`):** `Visit(rating)` (the person's OWN visit only), `PlaceRating.clean` (the public text must be one digit, a dot, one digit, else none), `PlacePage` gains `rating` + `yourVisit`; `DiscoverRepository` went / notWent / rate (1..5 only, never sent otherwise) / clearRating; reusable `PlaceVisits` helper (one call at a time) used by `PostCollectionViewModel` (place page: `rating`, `visit`, one-shot `visitSheet`) and `PostViewModel` (`onWent()` returns false when the post names no exact place: the composer opens as before); `WentSheet` ("How was it?": five numbered buttons ≥ 48 dp, spoken "Rate 3 out of 5", lime fill AND words for the chosen, then Share your experience / Take back / I did not go here / Done). The place page shows the drawn star + "4.3 out of 5 · from people who went"; the two place buttons are stacked full-width (the long label clipped when side by side). Nothing is stored; no list of visits; no sort mentions a rating. `Phase17G2VisitsTest`.
- **Optional smart understanding (Phase 17G-3, `docs/PHASE17G3.md`):** `ExploreApi.understand/understandingStatus`, `UnderstandRequestDto` (the
  ONLY request that carries the typed words; `toString` shows the length), fail-closed `UnderstandResponseDto`, `Understanding`,
  `ExploreRepository.understand/understandingAvailable` (text over 200 chars / blank never leaves the phone; any failure = plain Failure).
  `SmartUnderstandingChoice` (via `AppContainer.smartUnderstanding`; stores ONE account id in `ViewerPreferences` - the account that
  agreed - so another account on the same phone starts OFF; re-checked when sending). `ExploreViewModel`: when agreed + available, Search
  first asks, replaces the chips on a non-empty answer (`UnderstoodBy.SMART`), otherwise keeps the phone's chips and says "Standard
  understanding was used" once (`FALLBACK`); hand-set chips are never overwritten; unchanged words are asked once; a late answer is
  dropped (generation counter). Switch + question on the Explore screen (not Settings: Settings holds only navigation rows), shown only
  when the server offers it; the app never names the provider. `Phase17G3SmartUnderstandingTest` (+ `Phase17GExploreTest` guards).
- **Social redesign (Phase 17E, `docs/PHASE17E.md`):** `PostMarks` = optimistic butterfly/Save toggles (undo on failure, one call in flight per mark); `CardBinder.bindReaction/bindSave` (a given mark = lime fill + changed word + changed spoken label); pure `MarksFit` (screen width dp ÷ font scale: FULL ≥ 400 / SHORT ≥ 320 / ICONS) drives the card marks, You's head stacking and Saved's action row: words go first, never a button; `Cities` reads the bundled `resources/cities/cities-v1.tsv` (byte-identical to the backend's; search on the phone only); `CityPicker` sheet; `DailyStates` sheet (drawn icons, no emoji); profile photo via Photo Picker (`PhotoPreparer.prepareAvatar`), shown by `AvatarView` through the one Coil loader (memory cache only); Discover's head + city title in a scrolling `AppBarLayout` (page-coloured). `Phase17ETest`.
- **Media (Phase 17C, `docs/PHASE17C.md`):** the composer picks photos and one video (`PickVisualMedia.ImageAndVideo`,
  no permission); `ComposeMedia` stages PREPARING → UPLOADING (progress) → FINISHING (video poster) → READY, or FAILED
  (Retry from the failed step) / REFUSED (`MediaRefusal`, Remove only); Share blocked while any item is pending or failed.
  `VideoPreparer` re-packs (no re-encode) into `cache/video-upload` (deleted after Share/Remove/close, swept after a
  process death); the player gets `AppContainer.videoHeaders()` for one playback and is opened only by a tap; leaving the
  app pauses, never resumes by itself. Nothing about other people's videos is written to the phone (Q3).
- **Experiences (Phase 13B, `feature/social`, `docs/PHASE13B.md`):** separate `ExperienceApi` / `ExperienceRepository`
  (`RemoteExperienceRepository`; fail-closed DTOs: unknown precision → NONE, band → EARLIER, comment policy → NOBODY;
  cells redacted in `toString`). Pure `ComposerPolicy` (mirror of the server's `PostPolicy`), `PrivatePlacePolicy`
  (a place in a private cell or its 8 neighbours → City / No place only; no public place added there).
  `PhotoPreparer` (Photo Picker, no permission; upright, ≤ 2048 px, new JPEG q85 = no metadata; a bounds-only decode
  returns null by design). `EncryptedPlacesStore` + `PlaceMemory` (Q9: private places, 20 recent places, last audience;
  AES-GCM, Keystore-wrapped key, AAD `SSPLACES1|accountId`; cleared on sign-out and account switch; unreadable =
  never overwritten and every place counts as private). ONE Coil `ImageLoader` in `AppContainer` on the shared OkHttp
  client (JWT), memory cache only, disk cache null (Q3). Screens `ComposeFragment` (no audience preselected the first
  time, D14; choosing a place preselects AREA; exact place explains the 1–3 h delay; "Share with …"),
  `PlacePickerFragment` (one fix on "Places near me": only the 6-char area / 7-char cell leave the phone),
  `PostFragment` (bands, tip box, Perspectives via `PersonRowAdapter` rows + a "More" menu: hide/show, This helped,
  delete), `MyPostsFragment`, `PrivatePlacesFragment`; Connections: Share / Your experiences / Private places; profiles:
  "Experiences" list (`PostListViewModel`, more only on "Show more"). No Android edit screen (API only).
- **Intent and discovery (Phase 13C, `feature/social`, `docs/PHASE13C.md`):** separate `DiscoverApi` /
  `DiscoverRepository` (`RemoteDiscoverRepository`; DTOs fail closed: unknown state/section dropped, unknown reasons
  vanish, a tally on someone else's post or under 3 dropped). `Post.yourButterfly` (own state only), `Post.butterflies`
  (author only), `Post.placeId` (EXACT), `Place.city`. Pure `CityMatch` (profile city text → launch city, aliases
  Madras/Trichy/Pondy/Bangalore…). Screens: `DiscoverFragment` + `DiscoverViewModel` (city chips, not saved; Edition
  sections with "Why?" (`DiscoverTexts`), end card; From your connections with Show more + end line; search = places
  on the server, categories matched on the phone), `CocoonFragment` + `CocoonViewModel` (Want / Been, I've been →
  invitation, Want again, Take out), `PostCollectionFragment` + `PostCollectionViewModel` (`PostCollection.OfPlace` /
  `OfCategory`), `PostFragment` 🦋 / "I've been" / author's private line / "See this place". Entry: Connections →
  "Discover", "Your Cocoon" (until 13F). Nothing stored on the phone. `SocialTextsTest`: `%d` only in
  `butterfly_author_count` ("Only you can see this line"); ranking words (for you, top, most …, rank, best, feed,
  views, trending, popular) forbidden.
- **Moderation v2 (Phase 13D, `docs/PHASE13D.md`):** members: `ReportRepository` / `RemoteReportRepository`
  (`ReportApi`: `/api/reports`, `/api/social-standing`), pure `ReportReason.accepts` (reason fits target, note only and
  always for OTHER), shared `ReportViewModel` (report, standing, appeal) used by `PostFragment` ("Report this post", a
  perspective's More menu, collapsed post/perspective behind "Show it", the author's amber `moderationNotice` + "Ask for
  a review", approve), `PersonProfileFragment` ("Report this person"), `ConnectionsFragment` (amber pause notice +
  review, "Your reports", "Community guidelines"); `ReportDialogs` + `ModerationLabels`; `MyReportsFragment`,
  `GuidelinesFragment`; composer approve-first switch (only with "Anyone who can see it"). DTOs fail closed (own unknown
  state → HELD, others' unknown collapse → UNDER_REVIEW, unknown decisions/reports dropped, unknown appeal → no button).
  Moderators: `ContentModerationRepository` (`ContentModerationApi`), pure `ContentModerationPolicy`,
  `ContentQueueViewModel` (two sections in `ModerationFragment`; 403 hides them), `ContentCaseViewModel` /
  `ContentCaseFragment` (FLAG_SECURE, photos only on tap, reason dialog, confirmation). Moderation words amber, never
  red; author-facing strings never say "report(s)/reported" (`ModerationV2Test`). Nothing stored on the phone.
- **Automatic check on the phone (Phase 13E, `core/textcheck`, `docs/PHASE13E.md`):** pure Kotlin twins of the
  service's norm-1 `TextNormalizer` (offsets per character), `PhoneticKey` (spaces en/enc/ta), `HashedLexicon` (asset
  `assets/textcheck/lexicon-v1.keys`, byte-identical to `moderation-service/data/lexicon-v1.keys`, SHA-256 keys only),
  `TextCheck` (evidence rules), `LinearModel` (runtime only, no model ships), pure `DraftNudge` (Support first, Care,
  Contact, None) and `TextSpans`; `TextCheckParityTest` reads `../../moderation-service/data/vectors/*` and
  `restricted/evidence-vectors.tsv` directly. `AppContainer.textCheck` (null if the asset is unreadable: share without a
  nudge). `DraftNudgeDialogs` before Share (composer) and Add (perspective): "Our system thinks this may come across
  as…", Edit / Share anyway; contact details; support line Tele-MANAS 14416 (`ACTION_DIAL`, nothing stored); only-me
  drafts get the support line only. DTOs: `OwnModeration.source` (unknown → MODERATOR, phrases only for SYSTEM),
  `flagged`, `Collapsed.BY_SYSTEM`, `strongLanguage`; `ModerationLabels.ownNotice/collapsedLine`; list rows (`PostRows`)
  show the collapsed line, never a collapsed post's words. `ViewerPreferences` (DataStore "viewer_preferences": the
  viewer's own "Collapse strong language", switch on Connections). Moderator case screen: openedBy, "by the automatic
  check", findings with phrases, "evidence, not a verdict". `ModelPlacesTest` replaced `NoMlRuntimeTest`;
  `AiModerationTest` guards words (no score, no "you are", "our system").
