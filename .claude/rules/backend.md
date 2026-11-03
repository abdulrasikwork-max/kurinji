---
paths:
  - "backend/**"
---
# Backend architecture (moved verbatim from CLAUDE.md §6 on 2026-11-03)

Loaded when working on files under `backend/`. Conventions and invariants also stay in `CLAUDE.md` §6; migrations in
`database.md`; endpoints in `docs/API.md`.

> **Read with care (checked against the code on 2026-11-03):** since 17E `ButterflyService` is replaced by
> `PostMarkService` (+ `SavedPost`; `Butterfly` = reaction) and `CityAreas` / the `City` enum by `CityCatalog`; the
> Cocoon API is gone. The "Security" bullet's list of public endpoints is incomplete: `SecurityConfig` also permits
> `POST /api/auth/refresh`, `/api/auth/sign-out` and `/api/auth/phone/start|verify|resend|change`. The code wins.

- **Stack:** Java 17, Spring Boot **3.5.16** (OSS support ended 30 Jun 2026 — upgrading to 4.x is a separate, planned step),
  Spring Web, Data JPA, Security (OAuth2 resource server for JWT), Bean Validation, Flyway, MySQL 8.
- **Packages:** `auth`, `user`, `safetycontact`, `emergency`, `evidence` (Phase 5; + `tools/VaultKeyTool`, offline only),
  `intelligence` (Phase 8: pure `AreaIntelligencePolicy` + `PatternPolicy`, `AreaIntelligenceService`, `PatternService`,
  `RegionIntelligenceService`, in-memory `RegionQueryLimiter`, `IntelligenceController`; `config/IntelligenceProperties`;
  reads signals only through `CommunityService.visibleIn` = the ONE visibility definition; must not reference
  emergency/journey/escalation/safetycontact/circlealert/evidence/moderation — `IntelligenceIsolationTest`;
  Phase 9D: `AreaIntelligencePolicy.Model` RULES_1_1 (active, `modelVersion` "rules-1.1": havens alone count once) |
  RULES_2 (rule (b) needs ≥ 3 independent people across signals, `Evidence.people` from
  `Assessment.independentSupporters`, people linkage from `SignalVisibility.VisibleSet`); `AreaModelShadow` (from the
  job, at most hourly, read-only, logs counts only); `app.intelligence.rules-2-mode` OFF|SHADOW|ACTIVE, default SHADOW),
  `social` (Phase 13A: pure `policy/` `AgePolicy`, `HandlePolicy`, `ProfileTextPolicy`, `ProfileLists`, `InviteCodes`,
  `Visibility` = THE one visibility rule; `SocialAccess` (active + age-confirmed caller), `Relationships`,
  `ProfileService`, `ConnectionService` (one row per pair, silent decline, asking back connects, invites), `BlockService`,
  in-memory `SocialLimits`; `config/SocialProperties`; must not reference safety packages and the reverse —
  `SocialIsolationTest`; four-user tests `SocialFourUserTest`; HTTP age check in `user/service/AgeGate`;
  Phase 13B: `Visibility.canSeePost/canComment/visibleAudiences`, pure `PostPolicy`, `TimeBands`, `PostDelay` (Q1),
  `PlaceNamePolicy`; `media/ImageSanitizer` (magic bytes, header size check before decoding, re-encode without
  metadata) + `media/MediaStorage` (`app.social.media-dir`, UUID names); `PlaceService`, `PostService`, `MediaService`,
  `CommentService`; `SocialMediaCleanupJob` = the ONLY `@Scheduled` in `social` (unattached uploads > 24 h, orphan
  files); `AccountEraser` deletes the person's photo files before the rows;
  Phase 13C: `Butterfly` (= Cocoon item), `ButterflyService` (Experiences of others only; Cocoon filtered by the one rule),
  `DiscoverService` (Edition, connections list, place/category pages; SQL pre-filter, then `Relationships.forLists` =
  `Visibility.showInLists` = canSeePost + not muted), pure `EditionPolicy` (IST week, theme rotation, content-only order +
  reason codes), `CityAreas` (launch-city boxes → `places.city`), `ButterflyTally` (author only, number from 3),
  `PlaceService.search` (names only);
  Phase 13D: posts/comments carry `moderation` NONE/COLLAPSED/HELD/REMOVED + reason + decision (written only by
  `moderation`; social never references `moderation` — `SocialIsolationTest`), `Visibility` (HELD/REMOVED = author only,
  `canSeeComment`, `showInEdition` leaves collapsed out, `visibleStates` for list SQL), `SocialStanding` (pause) read by
  `SocialAccess.requireCanComment/Post/Connect`, probation (`app.social.probation` 72 h: lower post/perspective limits,
  no links, `countsFrom` for reports), `PostRevision` (only while `posts.in_review`), `approve_first` /
  `awaiting_approval` (Q6);
  Phase 13E: `moderation_source` REPORTS/SYSTEM/MODERATOR (`moderate()` derives REPORTS/MODERATOR,
  `moderateBySystem()` only COLLAPSED/HELD/NONE, never REMOVED), `moderation_spans`, `strong_language`;
  `event/ContentWritten` published synchronously by `PostService.create/edit` (text changed, never ONLY_ME) and
  `CommentService.create`; the author's `ModerationView.source` is SYSTEM/MODERATOR only (a report collapse has none:
  13D M4), `collapsed` BY_SYSTEM, `supportSuggested` only in the author's write answer),
  `moderation.assessment` (Phase 13E: `ModerationServiceClient` = the ONLY caller of the Python service (java.net.http,
  `app.moderation.ai.*`, sends `{kind, text}` only, fail-closed parsing, logs exception classes only); pure
  `DecisionPolicy` policy-1 pinned to `moderation-service/data/vectors/policy-vectors.tsv`; `AssessmentService`
  (`@EventListener` inside the write transaction, never throws into it; applies only over NONE/SYSTEM; HOLD (not
  CHECK_PENDING) opens a `content_cases` row `origin` SYSTEM + `in_review`; `requestReview` = origin AUTHOR_REQUEST,
  same 202; `viewFor` for moderators; `reviewed` → `OutcomeRecorder.aiReviewed`); `ContentAssessment` (V24, no text,
  one per target); `AssessmentJob` (`@Scheduled` `app.moderation.ai.job-interval`: retries with backoff 1→30 min for
  24 h, publishes a probation hold after `probation-wait-max` 2 h, retention 90 d unless an open case/appeal); the check
  is OFF without a 16+ character `MODERATION_SERVICE_KEY` (start-up WARN); tests: stub `support/StubModerationService`,
  `AssessmentIntegrationTest` + `AssessmentProbationIntegrationTest` on their own H2 URLs, `ModelPlacesTest`),
  `community` (Phase 6: `GeoCell`, pure `SignalPolicy`, `CommunityService`, in-memory `AreaQueryLimiter`,
  `CommunityPurgeJob` = a `@Scheduled` task, deletion only (since Phase 10A the other one is `AccountDeletionJob`); `config/CommunityProperties`, `SchedulingConfig`;
  Phase 7: pure `TrustPolicy`, `Independence`, `FlagPolicy`, `TrustService`, private `ContributorStanding`, `SignalFlag`;
  Phase 9C: `SignalVisibility` = the ONE visibility definition (`CommunityService.visibleIn` delegates), pure
  `CoordinationPolicy` = coord-1 R1/R2; Phase 9D: `CommunityService.joinLoneReports` (first step of `purge`: a lone,
  counting, never-confirmed concern REPORT moves as a CONFIRM onto an OLDER visible same-category signal in a block
  sharing a SIDE, `GeoCell.adjacency`; never havens/corners/flagged/under review/self) and `confirmsNextDoor`
  (re-send guard)),
  `moderation` (Phase 7: `ModerationService`, `CaseRefs`, `ModerationController`, `AdminController`,
  `config/ModerationProperties`; Phase 9C: `CoordinationCheck` (from the job, mode OFF|SHADOW|ACTIVE, default SHADOW,
  only write = open a COORDINATION case; `CoordinationIsolationTest`), `config/CoordinationProperties`,
  `blocksWithdrawal` (COORDINATION cases without flags do not lock withdrawal); Phase 9C.1–9C.3: S15 Option B in
  `TrustService.Snapshot.supportOn` (people counting at a DISMISSED COORDINATION case count separately on THAT signal;
  read via `ModerationCaseRepository.lastClosedBySignal`, nothing stored), `CoordinationPolicy.Group.members()` (≥ 2
  of the group's places) for `CaseDetail.groupMembers`, RESTRICT `wholeGroup` and the dismissed-group guard (any
  signal backed only by members); Phase 9B: `coordination_rule` stored on every new COORDINATION case;
  Phase 13D: SIBLING tables `content_cases`/`content_reports` (Phase 7 `moderation_cases` untouched), `ReportService`
  (visible targets only, repeat = same 202, case opened or joined, collapse at `app.moderation.collapse-reports` 3
  independent counting reports via `community.service.Independence`), `ContentModerationService` (queue, detail, decisions
  CONTENT_*/PERSON_*, one strike per case, pauses never shorten a stronger one, appeals on the shared
  `moderation_appeals` (never the decider, never a reporting moderator), standing, place merge, `maintain`),
  `ContentModerationJob` (`@Scheduled` hourly; collapse + retention only), `CaseRefs.contentRef` (own namespace);
  `ModerationService.listAppeals/decideAppeal` now handle community RESTRICT appeals only; `requireModerator` shared),
  `evaluation` (Phase 9B: `OutcomeRecorder` = the only writer, typed enum-only methods, counts buffered per transaction
  and written AFTER COMMIT in their own transaction, best effort (failure = WARN with the exception class only), never
  reads; `WeeklySnapshot` from the job, first run of each ISO week, one transaction, reads `SignalVisibility`, also the
  104-week retention; pure `EvaluationReport` (rates, Wilson 95 %, minimums, gate G1); `EvaluationService` = the only
  reader, called by `ModerationService.evaluationReport` (ADMIN); `config/EvaluationProperties`; must not reference
  safety packages or moderation, and safety packages / intelligence must not reference it — `EvaluationIsolationTest`),
  `security/RoleAuthorization` +
  `AttemptLimiter`, `auth/service/AuthThrottle`
  (`config/AuthProperties`) (each `controller/service/repository/entity/dto`),
  `config` (SecurityConfig, JwtConfig, JwtProperties), `security` (JwtService, CurrentUser, 401/403 JSON handlers),
  `common/exception` + `common/response/ApiError`.
- **Conventions:**
  - Thin controllers; rules in services; DTOs are Java records; entities are never serialized.
  - Caller identity **only** from the JWT: `CurrentUser.id(jwt)` (`sub` claim). No `userId` in any request DTO.
  - Repositories returning owned data always filter by user (`findByIdAndUserId`, …). Another user's record → **404** (same as missing).
  - Throw `ApiException` subclasses: `InvalidRequestException` 400, `UnauthorizedException`/`InvalidCredentialsException` 401,
    `ResourceNotFoundException` 404, `ConflictException`/`EmailAlreadyUsedException` 409. `GlobalExceptionHandler` renders one
    JSON shape: `timestamp, status, error, message, path, fieldErrors?`. No stack traces or exception text to clients.
  - `spring.jpa.hibernate.ddl-auto=none`, `open-in-view=false`. **Flyway owns the schema.**
- **JWT:** HS256, secret from env, issuer `https://safesphere.example`, `sub` = user id, **24 h**. **C1:** each sign-in is
  a device session (V19) with a rotating refresh secret (`POST /api/auth/refresh`); logout ends it on the server
  (`POST /api/auth/sign-out`) and clears the phone. **Phase 10A:** after a password change tokens carry `cca` (credentials stamp = µs of
  `users.credentials_changed_at`); `security/ActiveAccountCheck` (inside the decoder, after exp/iss) refuses a token whose
  account is missing/erased/disabled or whose stamp differs (exact equality) → 401 with an RFC 6750 `error_description`.
  **10A.4:** tokens carry `sid` (random sign-in id) and `auth_time` (`CurrentUser.sessionId/authTime`); `POST
  /api/users/me/token` renews for the same sign-in (≤ `app.account.session-max-age` 30d since the password, always during
  an ACTIVE SOS/walk). `EmergencyEvent.startedSession` (only while ACTIVE): another sign-in ends an SOS only with the
  password (`account/service/PasswordGuard`, shared limit) and sets `ended_elsewhere`.
- **Account (Phase 10A, `account` package):** `AccountService` (password change refused during an ACTIVE SOS or Safe
  Walk; deletion request refused while ACTIVE / < 24 h after an emergency, 7-day grace; cancel with password; 5 wrong
  passwords / 15 min shared → 429, in memory; wrong password = 409), `AccountDeletionJob` (`@Scheduled`, 15 min) →
  `AccountEraser` (postpone if an emergency is recent; evidence files first; owned rows children-first + 
  `CommunityService.eraseContributor`; delete the row, or anonymise it if the audit log names it as decider);
  `AccountEraserCoverageTest` compares the eraser with every `REFERENCES users` in the migrations.
- **Explore (Phase 17G-1, V29, `docs/PHASE17G.md`):** `ExploreFacets` = the ONE tag list (`explore/explore-facets-v1.tsv`, 13 codes, byte-identical in the app, V29's CHECK list; `ExploreFacetsParityTest`); `PostFacet` + `PostFacetRepository` (tags read in batch in `PostService.respond`, deleted explicitly with the post and by `AccountEraser`: the test database has no cascade; create/edit `facets`: null keeps, [] clears, never on an Ask); pure `ExplorePolicy` (requested = categories as ONE + tags + daypart; order: matches, newest readable experience, name; max 20; no popularity input) + `ExploreService` (candidates = `PostRepository.exploreCandidates`: Everyone EXPERIENCE + EXACT place in the city, not the viewer's own, visible, newest 400 by `visible_from`, then `Relationships.forEdition`; tags/times only from those posts) + `ExploreController` `POST /api/explore/search` (structured body, `SocialLimits.explore`). `ExploreIsolationTest` scans the code (comments stripped): no safety/community/intelligence/evaluation/moderation reference, no outbound call, no count or rating or createdAt. Tests: `ExplorePolicyTest`, `Phase17gExploreApiTest`.
- **Optional smart understanding (Phase 17G-3, `docs/PHASE17G3.md`, package `explore`, NOT `social`):** `POST /api/explore/understand {text ≤ 200}` →
  `{categories ≤ 2, facets ≤ 4, daypart?}` (closed-list codes only) or **503** with code `UNDERSTANDING_OFF` / `UNDERSTANDING_UNAVAILABLE`;
  `GET /api/explore/understanding` → `{available}`. `UnderstandingService` (mode `app.explore.understanding.mode` OFF by default, text
  check identical to the service's) → `UnderstandingGate` (our own per-person 20 / global 300 caps per UTC day + a 10-minute circuit
  breaker after any provider failure, in memory) → `UnderstandingClient` (java.net.http, the ONLY caller; talks to our internal Python
  service with `X-Moderation-Key`, never to a provider; reads the answer fail-closed against `Interest` / `ExploreFacets` / `Daypart`).
  The provider key never exists in the JVM. `UnderstandingIsolationTest` (island: imports only closed lists + plumbing, stores and
  schedules nothing, no provider names), `ExploreVocabularyParityTest`, `ModelPlacesTest` (third pinned client; provider address/key only
  in `understand.py`).
- **Social redesign (Phase 17E, V28, `docs/PHASE17E.md`):** `CityCatalog` = the ONE city list (`cities-v1.tsv`; codes
  are Strings; `PlaceCityBackfill` gives existing places a city at start-up, idempotent); `PostMarkService` (reaction on
  others' Experiences, save on others' Experiences/Asks; writes outside a transaction so a lost unique race just finds
  the row, Phase 14 rule; Saved filtered by the one visibility rule); `ProfilePhotoService` (new key per upload, old
  file after commit, served only through `canSeeProfile` + current key); `DailyStateService` (IST day, expiry on read,
  CONNECTED/SELF only; purge in `SocialMediaCleanupJob`, still the ONE `@Scheduled` in social); moderation
  `PERSON_REMOVE_PHOTO` (not a strike, not appealable, case stays open). `Phase17eSocialApiTest`, `CityCatalogTest`.
- **Media (Phase 17C, V27, `docs/PHASE17C.md`):** `Mp4Sanitizer` is pure (JDK only) and never decodes video; metadata
  boxes are renamed `free` and zeroed in place (offsets stay valid); a video upload is streamed to `.tmp` files in the
  media folder (hard cap `app.social.max-video-bytes`), never held in memory; content types come from the row's kind;
  every key-based deletion removes `<key>.jpg` and `<key>.mp4`; `videos-per-day` 10 (in memory).
- **Phone verification (Phase 17B, V26, `docs/PHASE17B.md`):** `auth/phone`: `PhoneVerificationService` (open inside
  the sign-up/start transaction, `deliver` after commit with undo on provider refusal, `verify` under a row lock,
  `resend`, `change`, `cleanUp`), `PhoneNumbers`, `PhoneChallenge`, `OtpProperties` (`app.otp.*`), `OtpSender` →
  `DevFileOtpSender` (default) | `Fast2SmsOtpSender` (`OtpSenderConfig`); `PhoneVerificationException` codes;
  `User.isActivated()` = phone OR (pre-V26) e-mail verified, used by `ActiveAccountCheck` and refresh; login and new
  sign-ins need `isPhoneVerified()`. `TestAccounts.phoneFor/markVerified` for tests; `CapturingOtpSenderConfig` captures
  codes. `ModelPlacesTest` pins the two outbound HTTP clients.
- **E-mail verification (V25, REMOVED from the flow in 17B; history):** sign-up creates an UNVERIFIED account and NO session;
  `EmailVerificationService` issues a 256-bit token (only SHA-256 stored, one row per account, 60 min), the e-mail goes
  out after commit on the `verificationMailExecutor` thread (never logged); `POST /api/auth/verify-email` consumes it
  with one conditional DELETE (single use, race-safe) and sets `email_verified_at`; login of an unverified account =
  403 `EMAIL_NOT_VERIFIED` (only after the right password); resend needs e-mail + password, always 202, limited;
  `ActiveAccountCheck` and refresh refuse unverified accounts; unverified accounts go after 7 days; an unverified
  address whose link expired can be signed up again. `TestAccounts.registerVerifiedAndLogin/markVerified` for tests.
- **Security:** stateless; `POST /api/auth/register|login` public, everything else authenticated. CSRF off (no cookies).
  CORS: no origins by default. BCrypt passwords (72-byte limit enforced). Login errors generic and timing-equalised.
- **Tests:** `@SpringBootTest` + MockMvc, real JWT flow, H2 in MySQL mode with **Flyway disabled** and Hibernate `create-drop`
  (`src/test/resources/application.properties`). Helper `support/TestAccounts`. Two-user ownership tests are the norm.
  ⚠ H2 does not exercise MySQL-only DDL (generated columns, CHECKs): **verify every new migration on real MySQL.**
