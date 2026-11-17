package com.safesphere.app.feature.social

import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.social.data.PhotoPreparer
import com.safesphere.app.feature.social.data.remote.PersonSummaryDto
import com.safesphere.app.feature.social.data.remote.photoKey
import com.safesphere.app.feature.social.ui.JournalViewModel
import com.safesphere.app.feature.social.ui.MarksFit
import com.safesphere.app.feature.social.ui.PhotoStatus
import com.safesphere.app.testing.FakeMomentRepository
import com.safesphere.app.testing.TestPick
import com.safesphere.app.testing.FakeMediaSource
import com.safesphere.app.testing.FakeSocialRepository
import com.safesphere.app.testing.MainDispatcherRule
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Phase 17E (docs/PHASE17E.md), what N4 kept of it: the profile photo (picked, made square on the phone, uploaded,
 * removable) and the rule that words leave a narrow row before any button. Cities, "I went" and post marks left the
 * app in N4.
 */
class Phase17ETest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    // ---- Profile photo ---------------------------------------------------------------------------------------------

    @Test
    fun `a photo key goes into an address only when it is a lower-case uuid`() {
        assertEquals("0a0a0a0a-0000-4000-8000-000000000001", photoKey("0a0a0a0a-0000-4000-8000-000000000001"))
        assertNull(photoKey("0A0A0A0A-0000-4000-8000-000000000001"))
        assertNull(photoKey("../../api/admin"))
        assertNull(photoKey(""))
        assertNull(photoKey(null))
        assertNull(PersonSummaryDto("a", "asha", "Asha", "not-a-key").toDomain().photo)
    }

    @Test
    fun `the square crop is centred`() {
        assertEquals(Triple(200, 0, 600), PhotoPreparer.squareCrop(1000, 600))
        assertEquals(Triple(0, 150, 300), PhotoPreparer.squareCrop(300, 600))
        assertEquals(Triple(0, 0, 50), PhotoPreparer.squareCrop(50, 50))
    }

    @Test
    fun `a picked photo is made square on the phone, uploaded, and shown with its new key`() = runTest {
        val social = FakeSocialRepository()
        val media = FakeMediaSource()
        val vm = JournalViewModel(social, media, FakeMomentRepository())
        vm.load()
        advanceUntilIdle()
        vm.onPhotoPicked(TestPick())
        advanceUntilIdle()
        assertEquals(PhotoStatus.IDLE, vm.uiState.value.photo)
        assertEquals("0a0a0a0a-0000-4000-8000-000000000001", vm.uiState.value.profile!!.photo)
        assertArrayEquals(media.avatar, social.uploadedAvatars.single())

        vm.onRemovePhoto()
        advanceUntilIdle()
        assertNull(vm.uiState.value.profile!!.photo)
    }

    @Test
    fun `an unreadable photo is not uploaded, and a failed upload says so and keeps the old photo`() = runTest {
        val social = FakeSocialRepository()
        val media = FakeMediaSource().apply { avatar = null }
        val vm = JournalViewModel(social, media, FakeMomentRepository())
        vm.load()
        advanceUntilIdle()
        vm.onPhotoPicked(TestPick())
        advanceUntilIdle()
        assertEquals(PhotoStatus.UNREADABLE, vm.uiState.value.photo)
        assertTrue(social.uploadedAvatars.isEmpty())
        vm.onPhotoNoticeShown()

        media.avatar = byteArrayOf(1)
        social.uploadPhotoResult = ApiResult.Failure(ApiError.NO_CONNECTION)
        vm.onPhotoPicked(TestPick())
        advanceUntilIdle()
        assertEquals(PhotoStatus.FAILED, vm.uiState.value.photo)
        assertNull(vm.uiState.value.profile!!.photo)
    }

    @Test
    fun marksBar_losesWordsBeforeButtons_onNarrowScreensAndLargeText() {
        // A 412 dp phone at normal text: every word.
        assertEquals(MarksFit.Mode.FULL, MarksFit.mode(412, 1.0f))
        // A 384 dp phone (the Galaxy A23) or 412 dp at 1.15x: the comment button drops its word.
        assertEquals(MarksFit.Mode.SHORT, MarksFit.mode(384, 1.0f))
        assertEquals(MarksFit.Mode.SHORT, MarksFit.mode(412, 1.15f))
        assertEquals(MarksFit.Mode.SHORT, MarksFit.mode(320, 1.0f))
        // The case found on the emulator: 320 dp at 2.0x text pushed Save off the card. Icons only.
        assertEquals(MarksFit.Mode.ICONS, MarksFit.mode(320, 2.0f))
        assertEquals(MarksFit.Mode.ICONS, MarksFit.mode(412, 1.3f))
        // Small text gives room, but only up to a point.
        assertEquals(MarksFit.Mode.SHORT, MarksFit.mode(320, 0.5f))
    }
}
