package com.safesphere.app.feature.social

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.safesphere.app.R
import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.core.network.ProgressRequestBody
import com.safesphere.app.feature.moderation.data.remote.MediaViewDto
import com.safesphere.app.feature.social.data.MediaRefusal
import com.safesphere.app.feature.social.data.Mp4Scrubber
import com.safesphere.app.feature.social.data.PlaceMemory
import com.safesphere.app.feature.social.data.VideoOutcome
import com.safesphere.app.feature.social.data.VideoRules
import com.safesphere.app.feature.social.data.remote.MediaRefDto
import com.safesphere.app.feature.social.domain.Audience
import com.safesphere.app.feature.social.domain.MediaKind

import com.safesphere.app.feature.social.ui.ComposeMediaTexts
import com.safesphere.app.feature.social.ui.ComposeNotice
import com.safesphere.app.feature.social.ui.ComposeViewModel
import com.safesphere.app.feature.social.ui.MediaStage
import com.safesphere.app.feature.social.ui.VideoPlayerViewModel
import com.safesphere.app.testing.FakeExperienceRepository
import com.safesphere.app.testing.FakeMediaSource
import com.safesphere.app.testing.FakePlacesStore
import com.safesphere.app.testing.MainDispatcherRule
import com.safesphere.app.testing.TestPick
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Phase 17C (decision D-6): photos and a video in the composer (progress, retry from the failed step, no partial post,
 * one video per post, removal), the phone's video rules, the metadata scrub of the re-packed file, upload progress,
 * fail-closed answers, and the player's "never by itself" rule.
 */
class MediaPhase17CTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val temp = TemporaryFolder()

    private val repository = FakeExperienceRepository()
    private val media = FakeMediaSource()
    private val memory = PlaceMemory(FakePlacesStore(), { 7L }, Dispatchers.Unconfined)
    private val clock = Clock.fixed(Instant.parse("2026-10-22T10:00:00Z"), ZoneOffset.UTC)

    private fun composer() = ComposeViewModel(repository, memory, media, clock)

    /** A draft that can be shared apart from its media. */
    private fun ComposeViewModel.fillIn() {
        onBody("Quiet in the morning, good chai.")
        onNoPlace()
        onAudience(Audience.EVERYONE)
    }

    // ---- composer ----

    @Test
    fun `a photo is prepared, sent with its progress, and only then part of the post`() = runTest {
        val vm = composer()
        vm.fillIn()
        vm.onMediaPicked(listOf(TestPick(MediaKind.PHOTO, preview = "local-photo")))
        assertEquals(MediaStage.PREPARING, vm.uiState.value.media.single().stage)
        assertEquals("local-photo", vm.uiState.value.media.single().preview)  // shown before the upload is done
        vm.onShare()
        assertTrue(repository.drafts.isEmpty())                              // not while it is on its way
        advanceUntilIdle()
        val item = vm.uiState.value.media.single()
        assertEquals(MediaStage.READY, item.stage)
        assertEquals(1f, item.progress)
        vm.onShare()
        advanceUntilIdle()
        assertEquals(listOf(item.mediaId), repository.drafts.single().mediaIds)
    }

    @Test
    fun `no connection - Retry or Remove, never shared without it`() = runTest {
        repository.photoResults.add(ApiResult.Failure(ApiError.NO_CONNECTION))
        val vm = composer()
        vm.fillIn()
        vm.onMediaPicked(listOf(TestPick(MediaKind.PHOTO)))
        advanceUntilIdle()
        assertEquals(MediaStage.FAILED, vm.uiState.value.media.single().stage)
        vm.onShare()
        advanceUntilIdle()
        assertTrue(repository.drafts.isEmpty())
        assertTrue(vm.uiState.value.showIssues)
        vm.onRetryMedia(vm.uiState.value.media.single().key)
        advanceUntilIdle()
        assertEquals(MediaStage.READY, vm.uiState.value.media.single().stage)
        vm.onShare()
        advanceUntilIdle()
        assertEquals(1, repository.drafts.single().mediaIds.size)
    }

    @Test
    fun `what the server or the phone refuses is final - Remove only`() = runTest {
        repository.photoResults.add(ApiResult.Failure(ApiError.VALIDATION))
        repository.photoResults.add(ApiResult.Failure(ApiError.TOO_MANY_ATTEMPTS, "You have added many photos today."))
        val vm = composer()
        vm.onMediaPicked(listOf(TestPick(MediaKind.PHOTO), TestPick(MediaKind.PHOTO)))
        advanceUntilIdle()
        val (first, second) = vm.uiState.value.media
        assertEquals(MediaRefusal.NOT_ACCEPTED, first.refusal)
        assertEquals(MediaRefusal.DAILY_LIMIT, second.refusal)
        assertTrue(vm.uiState.value.mediaFailed)
        vm.onRetryMedia(first.key)
        advanceUntilIdle()
        assertEquals(2, repository.uploadedPhotos.size)                      // no retry of a refusal
        media.photo = null
        vm.onMediaPicked(listOf(TestPick(MediaKind.PHOTO)))
        advanceUntilIdle()
        assertEquals(MediaRefusal.UNREADABLE, vm.uiState.value.media.last().refusal)
        assertEquals(R.string.social_media_unreadable, ComposeMediaTexts.refusal(MediaRefusal.UNREADABLE))
        vm.uiState.value.media.map { it.key }.forEach(vm::onRemoveMedia)
        assertFalse(vm.uiState.value.mediaFailed)
    }

    @Test
    fun `a video - re-packed once, sent, then its poster - ready only after both`() = runTest {
        repository.posterResults.add(ApiResult.Failure(ApiError.NO_CONNECTION))
        val vm = composer()
        vm.fillIn()
        vm.onMediaPicked(listOf(TestPick(MediaKind.VIDEO)))
        advanceUntilIdle()
        val failed = vm.uiState.value.media.single()
        assertEquals(MediaStage.FAILED, failed.stage)                         // the poster did not arrive
        assertEquals("v1", failed.mediaId)                                    // the video itself did
        assertEquals(42_000L, failed.durationMs)
        assertEquals(FakeMediaSource.video().posterFile, failed.preview)
        vm.onShare()
        advanceUntilIdle()
        assertTrue(repository.drafts.isEmpty())                              // no partial post

        vm.onRetryMedia(failed.key)
        advanceUntilIdle()
        assertEquals(MediaStage.READY, vm.uiState.value.media.single().stage)
        assertEquals(1, media.preparedVideos)                                 // not re-packed again
        assertEquals(1, repository.uploadedVideos.size)                       // not sent again
        assertEquals(listOf("v1", "v1"), repository.posters.map { it.first })
        assertArrayEquals(byteArrayOf(9, 9), repository.posters.last().second)
        vm.onShare()
        advanceUntilIdle()
        assertEquals(listOf("v1"), repository.drafts.single().mediaIds)
        assertEquals(listOf(FakeMediaSource.video()), media.discarded)         // work files gone after sharing
    }

    @Test
    fun `a video over 60 seconds or 50 MB is refused on the phone and never sent`() = runTest {
        media.videoOutcome = VideoOutcome.Refused(MediaRefusal.TOO_LONG)
        val vm = composer()
        vm.fillIn()
        vm.onMediaPicked(listOf(TestPick(MediaKind.VIDEO)))
        advanceUntilIdle()
        val item = vm.uiState.value.media.single()
        assertEquals(MediaStage.REFUSED, item.stage)
        assertEquals(R.string.social_media_too_long, ComposeMediaTexts.refusal(item.refusal))
        assertTrue(repository.uploadedVideos.isEmpty())
        vm.onShare()
        advanceUntilIdle()
        assertTrue(repository.drafts.isEmpty())
        vm.onRemoveMedia(item.key)
        vm.onShare()
        advanceUntilIdle()
        assertTrue(repository.drafts.single().mediaIds.isEmpty())
    }

    @Test
    fun `one video per post and six items at most - the rest is not added and the person is told`() = runTest {
        val vm = composer()
        vm.onMediaPicked(listOf(TestPick(MediaKind.VIDEO), TestPick(MediaKind.VIDEO), TestPick(MediaKind.PHOTO)))
        assertEquals(listOf(MediaKind.VIDEO, MediaKind.PHOTO), vm.uiState.value.media.map { it.kind })
        assertEquals(ComposeNotice.ONE_VIDEO_ONLY, vm.uiState.value.notice)
        assertFalse(vm.uiState.value.canAddVideo)
        vm.onNoticeHandled()
        vm.onMediaPicked(List(6) { TestPick(MediaKind.PHOTO) })
        assertEquals(6, vm.uiState.value.media.size)
        assertEquals(ComposeNotice.TOO_MANY_ITEMS, vm.uiState.value.notice)
        assertFalse(vm.uiState.value.canAddMedia)
        advanceUntilIdle()
    }

    @Test
    fun `Remove during an upload stops it - Remove of a finished one deletes the server's copy`() = runTest {
        val gate = CompletableDeferred<Unit>()
        repository.videoGate = gate
        val vm = composer()
        vm.onMediaPicked(listOf(TestPick(MediaKind.VIDEO)))
        advanceUntilIdle()
        val uploading = vm.uiState.value.media.single()
        assertEquals(MediaStage.UPLOADING, uploading.stage)
        assertEquals(0.25f, uploading.progress)
        vm.onRemoveMedia(uploading.key)
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.media.isEmpty())
        assertTrue(repository.posters.isEmpty())                             // the cancelled upload went no further
        assertTrue(repository.deletedUploads.isEmpty())                      // nothing known on the server yet
        assertEquals(1, media.discarded.size)

        repository.videoGate = null
        vm.onMediaPicked(listOf(TestPick(MediaKind.PHOTO)))
        advanceUntilIdle()
        val ready = vm.uiState.value.media.single()
        vm.onRemoveMedia(ready.key)
        advanceUntilIdle()
        assertEquals(listOf(ready.mediaId), repository.deletedUploads)
    }

    @Test
    fun `closing the composer deletes the re-packed videos`() = runTest {
        val store = ViewModelStore()
        val vm = ViewModelProvider(store, viewModelFactory { initializer { composer() } })[ComposeViewModel::class.java]
        vm.onMediaPicked(listOf(TestPick(MediaKind.VIDEO)))
        advanceUntilIdle()
        store.clear()
        assertEquals(listOf(FakeMediaSource.video()), media.discarded)
    }

    @Test
    fun `status words and progress per stage`() {
        val item = com.safesphere.app.feature.social.ui.ComposeMedia("k", MediaKind.VIDEO)
        assertEquals(-1, ComposeMediaTexts.progress(item))
        assertEquals(42, ComposeMediaTexts.progress(item.copy(stage = MediaStage.UPLOADING, progress = 0.42f)))
        assertEquals(-1, ComposeMediaTexts.progress(item.copy(stage = MediaStage.FINISHING)))
        assertNull(ComposeMediaTexts.progress(item.copy(stage = MediaStage.READY)))
        assertNull(ComposeMediaTexts.progress(item.copy(stage = MediaStage.FAILED)))
        for (reason in MediaRefusal.entries) ComposeMediaTexts.refusal(reason) // every refusal has words
    }

    // ---- the phone's video rules ----

    @Test
    fun `only the first picture and sound tracks are copied - anything the muxer cannot write is refused`() {
        val tracks = listOf(VideoRules.Track(0, "application/gps"), VideoRules.Track(1, "video/avc"),
            VideoRules.Track(2, "audio/mp4a-latm"), VideoRules.Track(3, "video/hevc"), VideoRules.Track(4, "audio/mp4a-latm"))
        assertEquals(VideoRules.Choice.Copy(1, 2), VideoRules.chooseTracks(tracks, 35))
        assertEquals(VideoRules.Choice.Copy(0, null), VideoRules.chooseTracks(listOf(VideoRules.Track(0, "video/hevc")), 26))
        assertEquals(VideoRules.Choice.Refuse(MediaRefusal.UNREADABLE),
            VideoRules.chooseTracks(listOf(VideoRules.Track(0, "audio/mp4a-latm")), 35))
        assertEquals(VideoRules.Choice.Refuse(MediaRefusal.UNSUPPORTED),
            VideoRules.chooseTracks(listOf(VideoRules.Track(0, "video/dolby-vision")), 35))
        assertEquals(VideoRules.Choice.Refuse(MediaRefusal.UNSUPPORTED),       // sound is never dropped silently
            VideoRules.chooseTracks(listOf(VideoRules.Track(0, "video/avc"), VideoRules.Track(1, "audio/opus")), 35))
        assertEquals(VideoRules.Choice.Refuse(MediaRefusal.UNSUPPORTED),
            VideoRules.chooseTracks(listOf(VideoRules.Track(0, "video/av01")), 33))
        assertEquals(VideoRules.Choice.Copy(0, null), VideoRules.chooseTracks(listOf(VideoRules.Track(0, "video/av01")), 34))
    }

    @Test
    fun `length, size, rotation and flags`() {
        assertNull(VideoRules.checkLength(60_500))
        assertEquals(MediaRefusal.TOO_LONG, VideoRules.checkLength(60_501))
        assertEquals(MediaRefusal.UNREADABLE, VideoRules.checkLength(null))
        assertEquals(MediaRefusal.UNREADABLE, VideoRules.checkLength(0))
        assertNull(VideoRules.checkSize(50L * 1024 * 1024))
        assertEquals(MediaRefusal.TOO_LARGE, VideoRules.checkSize(50L * 1024 * 1024 + 1))
        assertNull(VideoRules.checkSize(null))
        assertEquals(90, VideoRules.orientationHint(90))
        assertEquals(270, VideoRules.orientationHint(-90))
        assertEquals(0, VideoRules.orientationHint(45))
        assertEquals(1080 to 1920, VideoRules.displaySize(1920, 1080, 90))
        assertEquals(1920 to 1080, VideoRules.displaySize(1920, 1080, 180))
        assertEquals(1, VideoRules.bufferFlags(1))
        assertEquals(8, VideoRules.bufferFlags(4))
        assertEquals(0, VideoRules.bufferFlags(2))                            // encrypted flag is never passed on
        assertEquals("0:01", VideoRules.durationLabel(400))
        assertEquals("0:42", VideoRules.durationLabel(42_999))
        assertEquals("1:00", VideoRules.durationLabel(60_500))
    }

    // ---- the scrub of the re-packed file ----

    @Test
    fun `the muxer's meta box and any udta or uuid box become zeros - the file keeps its length and samples`() {
        val samples = "PICTURE-SAMPLES".toByteArray()
        for (moovFirst in listOf(true, false)) {
            val moov = box("moov", box("mvhd", ByteArray(100)),
                box("trak", box("tkhd", ByteArray(84)), box("mdia", box("mdhd", ByteArray(24)),
                    box("udta", "+13.0827+080.2707/".toByteArray()))),
                box("meta", "com.android.version 14 SM-A235F".toByteArray()),
                box("udta", box("©xyz", "+13.0827+080.2707/".toByteArray())))
            val mdat = box("mdat", samples)
            val uuid = box("uuid", ByteArray(16) + "exif:GPSLatitude".toByteArray())
            val ftyp = box("ftyp", "mp42".toByteArray() + ByteArray(4) + "isommp42".toByteArray())
            val bytes = if (moovFirst) ftyp + uuid + moov + mdat else ftyp + mdat + uuid + moov
            val file = temp.newFile().apply { writeBytes(bytes) }
            assertTrue(Mp4Scrubber.hasMetadata(file))
            assertEquals(4, Mp4Scrubber.scrub(file))                         // uuid, moov/meta, moov/udta, mdia/udta
            val out = file.readBytes()
            assertEquals(bytes.size, out.size)
            for (leak in listOf("udta", "meta", "uuid", "13.0827", "android", "SM-A235F", "GPSLatitude")) {
                assertFalse(leak, String(out, Charsets.ISO_8859_1).contains(leak))
            }
            assertTrue(String(out, Charsets.ISO_8859_1).contains("PICTURE-SAMPLES"))
            assertFalse(Mp4Scrubber.hasMetadata(file))
        }
    }

    @Test(expected = Mp4Scrubber.NotMp4::class)
    fun `a file that is not laid out in boxes is refused`() {
        Mp4Scrubber.scrub(temp.newFile().apply { writeBytes(ByteArray(10) { 0x7F }) })
    }

    // ---- upload progress ----

    @Test
    fun `progress goes 0 to 1 in whole percents and starts again when the call is sent once more`() {
        val bytes = ByteArray(300_000) { (it % 251).toByte() }
        val seen = mutableListOf<Float>()
        val body = ProgressRequestBody(bytes.toRequestBody("video/mp4".toMediaType())) { seen += it }
        assertEquals(bytes.size.toLong(), body.contentLength())
        assertEquals("video/mp4", body.contentType().toString())
        repeat(2) {
            seen.clear()
            val sink = Buffer()
            body.writeTo(sink)
            assertArrayEquals(bytes, sink.readByteArray())
            assertEquals(0f, seen.first())
            assertEquals(1f, seen.last())
            assertEquals(seen.sorted(), seen)
            assertTrue(seen.size <= 102)
        }
    }

    // ---- answers fail closed ----

    @Test
    fun `media answers - an unknown kind or a video without a plausible length is left out`() {
        assertEquals(MediaKind.PHOTO, MediaRefDto("m", 10, 10).toDomain()?.kind)            // before 17C
        assertEquals(MediaKind.VIDEO, MediaRefDto("v", 640, 360, "VIDEO", 42_000).toDomain()?.kind)
        assertEquals(42_000, MediaRefDto("v", 640, 360, "VIDEO", 42_000).toDomain()?.durationMs)
        assertNull(MediaRefDto("p", 10, 10, "PHOTO", 5).toDomain()?.durationMs)               // only a video has one
        assertNull(MediaRefDto("v", 640, 360, "VIDEO", null).toDomain())
        assertNull(MediaRefDto("v", 640, 360, "VIDEO", 61_000).toDomain())
        assertNull(MediaRefDto("x", 10, 10, "AUDIO").toDomain())
        assertNull(MediaRefDto(null, 10, 10).toDomain())
        assertEquals(true, MediaViewDto("v", 1, 1, "VIDEO", 5_000).toDomain()?.isVideo)
        assertEquals(false, MediaViewDto("p", 1, 1).toDomain()?.isVideo)
        assertNull(MediaViewDto("v", 1, 1, "VIDEO", null).toDomain())
        assertNull(MediaViewDto("x", 1, 1, "GIF").toDomain())
    }

    // ---- the player ----

    @Test
    fun `the player checks the video first and never starts again by itself after the person leaves`() = runTest {
        val vm = VideoPlayerViewModel(repository, "http://test/api/media/v1/video")
        advanceUntilIdle()
        assertEquals(VideoPlayerViewModel.State.Ready, vm.state.value)
        assertEquals(listOf("http://test/api/media/v1/video"), repository.checkedVideos)
        assertTrue(vm.playWhenReady)                                          // opened by the person's tap
        vm.onLeft(12_000, keepPlaying = true)                                 // a rotation while playing
        assertTrue(vm.playWhenReady)
        assertEquals(12_000, vm.positionMs)
        vm.onLeft(15_000, keepPlaying = false)                                // left the app
        assertFalse(vm.playWhenReady)
        assertEquals(15_000, vm.positionMs)
        vm.onFinished()
        assertEquals(0, vm.positionMs)

        repository.checkVideoResult = ApiResult.Failure(ApiError.NOT_FOUND)
        vm.onTryAgain()
        advanceUntilIdle()
        assertEquals(VideoPlayerViewModel.State.Problem(R.string.video_not_found), vm.state.value)
        repository.checkVideoResult = ApiResult.Failure(ApiError.NO_CONNECTION)
        vm.onTryAgain()
        advanceUntilIdle()
        assertEquals(VideoPlayerViewModel.State.Problem(R.string.video_offline), vm.state.value)
        vm.onPlaybackError()
        assertEquals(VideoPlayerViewModel.State.Problem(R.string.video_cannot_play), vm.state.value)
    }


    private fun box(type: String, vararg parts: ByteArray): ByteArray {
        val payload = ByteArrayOutputStream().apply { parts.forEach { write(it) } }.toByteArray()
        return ByteBuffer.allocate(8).putInt(8 + payload.size).array().copyOfRange(0, 4) +
            type.toByteArray(Charsets.ISO_8859_1) + payload
    }
}
