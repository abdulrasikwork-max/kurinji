# Phase 17C — Media: better photos, one video per post, progress and retry (2026-11-01)

**Implemented, compiled, tested, live-verified on MySQL, emulator-verified (API 37).** Physical phone **not verified**.
Requirements: `PHASE17_PLAN.md` §2 (17C) and decision **D-6** (accepted 2026-10-27). Migration **V27** (next: **V28**).
**No new dependency** (platform `MediaExtractor` / `MediaMuxer` / `MediaMetadataRetriever` / `VideoView`; the server
parses MP4 with the JDK only).

## 1. Starting point (inspection)

| # | Finding | Consequence |
|---|---|---|
| S1 | Phone photos: ≤ 2048 px, JPEG q85; server re-encoded at 0.85 | Soft on large screens; a better phone photo would have been re-encoded down |
| S2 | No video anywhere (server JPEG/PNG only; no player) | — |
| S3 | Share already blocked while a photo uploads or failed (17A) | Kept; extended to videos and to every stage |
| S4 | Upload bodies read into memory; `SocialProperties` refuses > 5 MiB | 50 MB videos need streaming and their own limit |
| S5 | Post deletion, `AccountEraser` and the clean-up job delete `<key>.jpg` only | A `.mp4` would have stayed on disk after erasure — fixed in `MediaStorage.delete` |
| S6 | Every media view loads `/api/media/{id}` as an image (Coil, no video decoder) | A video needs a still: the poster frame |
| S7 | Removing a photo in the composer did not cancel its upload or delete the server copy | Fixed (cancel + `DELETE /api/media/{id}`) |

## 2. What was built

### Decisions (taken autonomously within D-6; recorded here)

| # | Decision | Why |
|---|---|---|
| C-1 | Photos ≤ 3072 px, q92 on the phone; the server's re-encode ladder now starts at 0.92 | The requirement; otherwise the server undid it (S1) |
| C-2 | At most 16 MP decoded on the phone (`PhotoPreparer.MAX_DECODED_PIXELS`), **one photo at a time** (`AndroidMediaSource` mutex; uploads stay parallel) | A 27–200 MP camera photo at full size needs 108–800 MB; six decoded side by side would need ~450 MB; such a photo may come out slightly under 3072 px |
| C-3 | One video per post, counted inside the 6 items | Keeps cards and the carousel simple; bounds disk use |
| C-4 | Video ≤ 60 s **plus 0.5 s of container rounding** (stored max 60 500 ms), ≤ 50 MiB | A "60 s" camera clip is often 60.0x s by its audio frames; refusing it would confuse people (deviation from a literal 60 000) |
| C-5 | A video has a **poster frame** made on the phone, sanitised by the server like a photo and stored as `<key>.jpg` next to `<key>.mp4` (same key) | Every existing image view keeps working; every key-based deletion removes both files (S5, S6) |
| C-6 | A video without its poster is **never attached** (`409`, "not ready") | Server-side "no partial post" |
| C-7 | Separate daily limit: 10 videos (photos keep 30) | 30 × 50 MB per account before the 24 h clean-up was too much disk |
| C-8 | Metadata boxes are **overwritten with zeros (renamed `free`)**, never cut out | Removing bytes from a moov before the mdat shifts every chunk offset and breaks playback |
| C-9 | Playback streams with the current sign-in header, never written to the phone | Q3: nothing about other people is stored on the phone; byte ranges let the player seek |
| C-10 | Playback only after a tap, on its own screen; coming back to the app never resumes by itself | "No autoplay"; a VideoView inside the RecyclerView is unreliable |
| C-11 | Server refusals (400) and the daily limit (429) are final in the composer (Remove only); everything else can be retried from the step that failed | A retry of a refused file can only fail again |
| C-12 | The owner can delete an upload no post uses (`DELETE /api/media/{id}`) | Remove in the composer removes the server copy at once, not after 24 h |

### Backend
- **V27** (`post_media`): `kind` PHOTO|VIDEO (binary, default PHOTO), `duration_ms`, `poster_bytes`; the size CHECK is
  replaced by one per kind (photo ≤ 5 MiB, no duration/poster; video ≤ 50 MiB, 1–60 500 ms, poster ≤ 5 MiB or NULL),
  every NULL case written out (V23 lesson).
- **`social/media/Mp4Sanitizer`** (pure, JDK only): recognised by `ftyp` (ISO/MP4/3GPP brands; QuickTime refused);
  every box size checked (64-bit and to-end sizes; ≤ 64 top-level boxes; moov ≤ 8 MiB, ≤ 20 000 boxes, ≤ 16 deep);
  refuses fragmented files, unknown top-level boxes, anything but exactly one picture track and ≤ 1 sound track (a GPS
  or timed-metadata track is refused, not kept), external data references, encrypted/unknown codecs, chunk offsets
  outside an mdat, > 60.5 s, > 4096 px; neutralises every `udta`, `meta`, `uuid` (XMP), unknown box (also inside sample
  entries) and the payload of `free`/`skip`; zeroes creation/modification times (mvhd, tkhd, mdhd), handler names and
  the compressor name. **It never decodes the picture or sound data** (§5).
- **`MediaService`**: `uploadVideo` streams the body into a `.tmp` file of the media folder with a hard 50 MiB cap
  (declared `Content-Length` refused first), sanitises into a second `.tmp`, hashes, moves into place, then writes the
  row; the uploaded original is deleted in every case. `uploadPoster` (own, unattached video, locked; sent again =
  replaced), `deleteUnattached`, `video` (file for streaming). `MediaStorage`: `newTemporary`, `moveVideo`,
  `videoFile`, `delete` removes both suffixes, `keysOlderThan` sees `.mp4` and dedupes.
- **API**: `POST /api/media` `video/mp4`; `POST /api/media/{id}/poster`; `GET /api/media/{id}/video` (Spring answers
  `Range` with 206); `DELETE /api/media/{id}`; `MediaRef`/`MediaView` + `kind`, `durationMs`; moderators
  `GET /api/moderation/content-cases/{id}/media/{mediaId}/video`. Content types come from the row's kind, never the file.
- **`PostService.attach`**: ≤ 1 video (400), poster and files must exist (409) — inside the create transaction, so a
  refused attach leaves no post. The 13E `photos` flag already means "any media row": a new account's public video post
  is held for a moderator like one with photos (pinned by a test).

### Android
- **Photos**: `PhotoPreparer` 3072 px, q92 → 85 → 75 only to fit 5 MiB, 16 MP decode cap.
- **Video**: `VideoPreparer` — size pre-check from the picker, length/rotation/size and a poster frame from
  `MediaMetadataRetriever` (the location tag is never read), first picture + first sound track copied sample by sample
  with `MediaMuxer` (no re-encode; `setOrientationHint`, never `setLocation`), then **`Mp4Scrubber`** zeroes the
  muxer's own `meta` box (Android version; on some builds maker/model) and any `udta`/`uuid`, then the size is checked
  again. Pure rules in **`VideoRules`** (track choice, limits, rotation, flags, "0:42"). Work files in
  `cache/video-upload`, deleted after Share, on Remove, when the composer closes, and swept at the first use after a
  process death.
- **Composer**: picker `ImageAndVideo` (photos only once a video is there); `ComposeMedia` with stages PREPARING →
  UPLOADING (progress) → FINISHING (poster) → READY, or FAILED (Retry) / REFUSED (Remove); retry resumes from the failed
  step (a video is never re-packed or re-sent once the server has it); Remove cancels the upload and deletes the server
  copy; at most 6 items and 1 video (a toast says what was left out); Share stays blocked while anything is pending or
  failed. `ProgressRequestBody` reports whole percents and restarts at 0 when `ApiCallHandler` re-sends after a refresh.
- **Viewing**: feed card, Saved card, post page and composer show the still with "▶ 0:42"; TalkBack says "Video 1 of 2,
  0:08 long" / "Video, 0:08". **`VideoPlayerFragment`** (task screen) checks the first byte through the shared client
  (renews a lapsed token, own words for not found / offline), then `VideoView` + `MediaController` with the sign-in
  header; position kept across rotation; leaving the app pauses and never resumes by itself; FLAG_SECURE for a case video.
- **Moderators**: "Show video 1" (poster, hidden until tapped, D15) → "Play video 1" (FLAG_SECURE player).

## 3. Results

- **Backend: 773 tests, 0 failures, 2 skipped** (17B: 755). New `Mp4SanitizerTest` (8), `VideoApiIntegrationTest`
  (10); `SocialAccountDeletionTest` now erases a video too.
- **Android: 749 tests, 0 failures** (17B: 732). New `MediaPhase17CTest` (16); updated composer and photo-size tests.
  Lint **0 errors / 102 warnings** (17B: 101; +1 `Overdraw` on the black player screen, the accepted pattern of other
  screens). Two of my strings were caught by `SocialTextsTest` ("…%d%%" and "online") and reworded.
- **V27 on MySQL 8.0:** backup `safesphere-before-V27.sql` (scratchpad, outside the repo); dry run on a scratch copy
  with the real row: **15/15 refused + 5/5 accepted**, existing row → PHOTO; **applied** by Flyway (0.085 s).
- **Live API on MySQL 21/21 PASS:** GPS fixture; upload 201 (VIDEO, 5000 ms, 640×360); MySQL row; stored file the same
  length with no GPS/XMP/Android version/udta/meta/uuid/device/handler names; samples intact; no `.tmp` left; post
  without poster 409; stranger 404 before posting; poster 200 + MySQL; post 201 with kind/duration; stranger plays it
  (video/mp4, no-store, nosniff, Accept-Ranges, identical bytes); `Range: bytes=0-0` 206; poster as the image; 401
  without sign-in; attached poster unchangeable; 61 s refused with words; photo as video/mp4 400; other type 415;
  Remove of an unused upload (stranger 404, owner 204, row + file gone); post deletion removes `.mp4` + `.jpg`.
- **Emulator (API 37) walk-through PASS**, with a real H.264/AAC clip (the SDK's `default.mp4`) given a GPS
  `©xyz` atom and an XMP `uuid` box:
  picker offers photos and videos; two videos + a photo → one video kept; preparing → uploaded; **the phone's re-packed
  file (pulled with `run-as`) had no GPS, XMP, udta, uuid or Android version — MediaMuxer's 118-byte `meta` box was
  zeroed**; the server copy likewise, mvhd times zero, poster 82 KB; "▶ 0:08" on the composer tile, post page and feed
  card; tap → player plays, controls, plays to the end; playing → Home → back = paused, Play shown (never resumed by
  itself); work files deleted after Share and swept after a process death; a 4000×3000 photo stored as **3072×2304**;
  backend stopped → "Not added. Tap to try again" and Share blocked with the reason → backend up → Retry → added; Remove
  of a finished photo deleted its server row; moderator: "Show video 1" → "Play video 1" plays with the window SECURE.
  One accessibility bug found and fixed: after Remove the remaining cell still said "Photo 2 of 2" (13B adapter; cells
  now rebind when the count changes).
- **Portrait video (rotation 90°), emulator PASS:** the SDK's 1280×720 `default360.mp4` given a 90° `tkhd` matrix and
  a GPS atom: the phone's re-packed copy has exactly one 90° matrix (not lost, not doubled), no metadata; MySQL
  720×1280 (swapped), the server copy keeps the matrix, the poster is 720×1280; composer tile, post page and player show
  the picture in the same orientation.
- **Near the limit, real server:** a 49 MiB MP4 → 201 in 0.51 s end to end (copy, strip, hash, move; local network);
  51 MiB → a clean 400 "A video can be at most 50 MB" in 0.02 s (declared length), connection kept; no `.tmp` left.
- The first live run was 13/21: all 8 failures came from a malformed JPEG in the script's fixture (the server rightly
  called it damaged); with a real JPEG the run was 21/21.

- **State left:** all 6 test accounts and their files deleted (only the one older media row remains); scratch table
  dropped; test clips removed from the emulator; backend running (`mvn spring-boot:run`), emulator running with
  `adb reverse tcp:8080` (the build points at `127.0.0.1`, left from the A23 sprint in `local.properties`).

## 4. How to test it yourself
1. `cd backend`, `mvn spring-boot:run` (Flyway applies V27). `cd android`, `.\gradlew.bat assembleDebug`, install.
2. Share → "Add photos or a video" → pick a video under 60 s and a photo. Watch "Preparing video…", the bar, "Almost
   done…". Share is blocked until both are ready.
3. Turn the backend off, add a photo → "Not added. Tap to try again"; turn it on, tap it → added.
4. Share; open the post; tap the video → it plays; press Home and come back → paused.
5. Pick a video over 60 s or 50 MB → refused with the reason, nothing uploaded.
6. On the server, `social-media-store/<key>.mp4` contains no `udta`/`meta`/`uuid` and no position.

## 5. Not verified / limitations
- **Physical phone: not verified** (no device connected). TalkBack by ear: not verified. A rotation while playing: not
  checked on the emulator (the code keeps position and playing state; unit-tested in the ViewModel).
- **Re-packing never makes a video smaller.** A long 1080p/4K clip over 50 MB is refused ("trim it or record at a lower
  quality"); typical phone video (~17 Mbit/s) reaches 50 MB at about 25 s. Transcoding (MediaCodec) is recommended for
  a later phase.
- **Data inside the coded stream** (for example an H.264 SEI message) is neither decoded nor removed by the phone or the
  server. Phones do not write a location there; the container is where they do.
- One request per upload with Retry; **no resumable upload** (a dropped 50 MB upload starts again). Recommended later.
- The platform player gets the sign-in header for one playback; a token that lapses during a long pause makes it fail
  with "can't be played right now" → Try again (renews it).
- Uploads that are never posted stay on the server until the 24 h clean-up (Remove deletes them at once).
- The phone's 60 s check uses the container's length (`MediaMetadataRetriever`); the server checks mvhd and every mdhd.
  **The phone's "too long" refusal was not seen on a device:** a copy edited to claim 70 s still read as 8.1 s on the
  emulator (the platform counts the samples); it is covered by unit tests and the server's live check (61 s → 400).

- Recommendations for later phases (not built): transcoding to fit 50 MB, resumable uploads, poster choice by the
  author, video in Discover's "Talking now" previews.
