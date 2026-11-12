package com.safesphere.app.feature.social

import com.safesphere.app.R
import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.social.data.PlaceMemory
import com.safesphere.app.feature.social.domain.Audience
import com.safesphere.app.feature.social.domain.MediaKind
import com.safesphere.app.feature.social.ui.ComposeMediaTexts
import com.safesphere.app.feature.social.ui.ComposeViewModel
import com.safesphere.app.feature.social.ui.MediaStage
import com.safesphere.app.testing.FakeExperienceRepository
import com.safesphere.app.testing.FakeMediaSource
import com.safesphere.app.testing.FakePlacesStore
import com.safesphere.app.testing.MainDispatcherRule
import com.safesphere.app.testing.TestPick
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** A photo, video or poster upload that timed out says so ("too slow, tap to try again") and can be tried again. */
class UploadTimedOutComposerTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeExperienceRepository()
    private val memory = PlaceMemory(FakePlacesStore(), { 7L }, Dispatchers.Unconfined)
    private val clock = Clock.fixed(Instant.parse("2026-10-22T10:00:00Z"), ZoneOffset.UTC)
    private fun composer() = ComposeViewModel(repository, memory, FakeMediaSource(), clock)

    private fun ComposeViewModel.fillIn() {
        onBody("Quiet in the morning, good chai.")
        onNoPlace()
        onAudience(Audience.EVERYONE)
    }

    private val timedOut = ApiResult.Failure(ApiError.NO_CONNECTION, timedOut = true)

    @Test
    fun `a photo that timed out is FAILED with the too-slow words, and Retry puts it right`() = runTest {
        repository.photoResults.add(timedOut)
        val vm = composer()
        vm.fillIn()
        vm.onMediaPicked(listOf(TestPick(MediaKind.PHOTO)))
        advanceUntilIdle()

        val item = vm.uiState.value.media.single()
        assertEquals(MediaStage.FAILED, item.stage)
        assertTrue(item.timedOut)
        assertEquals(R.string.social_media_timed_out, ComposeMediaTexts.failedText(item))
        assertTrue("Share stays blocked until it is tried again or removed", vm.uiState.value.mediaFailed)

        vm.onRetryMedia(item.key)
        advanceUntilIdle()
        assertEquals(MediaStage.READY, vm.uiState.value.media.single().stage)
        vm.onShare()
        advanceUntilIdle()
        assertEquals(1, repository.drafts.single().mediaIds.size)
    }

    @Test
    fun `a video whose upload timed out can be retried`() = runTest {
        repository.videoResults.add(timedOut)
        val vm = composer()
        vm.fillIn()
        vm.onMediaPicked(listOf(TestPick(MediaKind.VIDEO)))
        advanceUntilIdle()

        val item = vm.uiState.value.media.single()
        assertEquals(MediaStage.FAILED, item.stage)
        assertTrue(item.timedOut)
        vm.onRetryMedia(item.key)
        advanceUntilIdle()
        assertEquals(MediaStage.READY, vm.uiState.value.media.single().stage)
    }

    @Test
    fun `a poster that timed out is retried from the poster step, without sending the video again`() = runTest {
        repository.posterResults.add(timedOut)
        val vm = composer()
        vm.onMediaPicked(listOf(TestPick(MediaKind.VIDEO)))
        advanceUntilIdle()
        val failed = vm.uiState.value.media.single()
        assertEquals(MediaStage.FAILED, failed.stage)
        assertTrue(failed.timedOut)
        val videosSent = repository.uploadedVideos.size

        vm.onRetryMedia(failed.key)
        advanceUntilIdle()
        assertEquals(MediaStage.READY, vm.uiState.value.media.single().stage)
        assertEquals("the video itself is not sent twice", videosSent, repository.uploadedVideos.size)
    }

    @Test
    fun `a lost connection keeps the general words, and a refusal is not called a timeout`() = runTest {
        repository.photoResults.add(ApiResult.Failure(ApiError.NO_CONNECTION))
        repository.photoResults.add(ApiResult.Failure(ApiError.VALIDATION))
        val vm = composer()
        vm.onMediaPicked(listOf(TestPick(MediaKind.PHOTO), TestPick(MediaKind.PHOTO)))
        advanceUntilIdle()

        val (offline, refused) = vm.uiState.value.media
        assertEquals(MediaStage.FAILED, offline.stage)
        assertFalse(offline.timedOut)
        assertEquals(R.string.social_photo_failed, ComposeMediaTexts.failedText(offline))
        assertEquals(MediaStage.REFUSED, refused.stage)
        assertFalse(refused.timedOut)
    }
}
