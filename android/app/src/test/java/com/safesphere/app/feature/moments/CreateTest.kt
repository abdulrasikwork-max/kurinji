package com.safesphere.app.feature.moments

import androidx.lifecycle.SavedStateHandle
import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.core.network.MEMBERSHIP_NEEDED
import com.safesphere.app.feature.moments.data.MomentPhotoSource
import com.safesphere.app.feature.moments.data.MomentShot
import com.safesphere.app.feature.moments.data.remote.MomentRequestDto
import com.safesphere.app.feature.moments.data.remote.UploadedPhotoDto
import com.safesphere.app.feature.moments.domain.AudienceMemory
import com.safesphere.app.feature.moments.domain.DailyQuestion
import com.safesphere.app.feature.moments.domain.DraftRules
import com.safesphere.app.feature.moments.domain.MomentAudience
import com.safesphere.app.feature.moments.domain.MomentDraft
import com.safesphere.app.feature.moments.domain.MomentKind
import com.safesphere.app.feature.moments.domain.SharingStatus
import com.safesphere.app.feature.moments.domain.UploadedPhoto
import com.safesphere.app.feature.moments.ui.CreateViewModel
import com.safesphere.app.testing.FakeMomentRepository
import com.safesphere.app.testing.MainDispatcherRule
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Phase N4 (A2): Create — its rules, what it sends, the photo's life, and the guards (no gallery, no camera permission). */
class CreateTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val now = Instant.parse("2026-11-17T10:00:30Z")
    private val india = ZoneId.of("Asia/Kolkata")

    // ---- DraftRules (pure) ----

    @Test
    fun `characters are counted as people count them, after trimming`() {
        assertEquals(5, DraftRules.length("  hello \n"))
        assertEquals(3, DraftRules.length("வணக்கம்".take(3)))
        assertEquals(2, DraftRules.length("😀🎉")) // two emoji are two characters, not four UTF-16 units
        assertEquals(0, DraftRules.length("   "))
    }

    @Test
    fun `the room left shows only near the end, and goes negative when over`() {
        assertNull(DraftRules.roomLeft("a".repeat(399)))
        assertEquals(100, DraftRules.roomLeft("a".repeat(400)))
        assertEquals(0, DraftRules.roomLeft("a".repeat(500)))
        assertEquals(-3, DraftRules.roomLeft("a".repeat(503)))
    }

    @Test
    fun `words are needed unless there is a photo, and never more than 500`() {
        assertFalse(DraftRules.wordsFit("  ", hasPhoto = false))
        assertTrue(DraftRules.wordsFit("", hasPhoto = true))
        assertTrue(DraftRules.wordsFit("a".repeat(500), hasPhoto = false))
        assertFalse(DraftRules.wordsFit("a".repeat(501), hasPhoto = true))
        assertTrue(DraftRules.whereFits("a".repeat(60)))
        assertFalse(DraftRules.whereFits("a".repeat(61)))
        assertFalse(DraftRules.whereFits("Canteen\nblock B"))
    }

    @Test
    fun `a plan time is its next occurrence within 24 hours`() {
        // 10:00:30 UTC = 15:30:30 India time
        val later = DraftRules.nextPlanTime(LocalTime.of(18, 0), now, india)
        assertEquals(Instant.parse("2026-11-17T12:30:00Z"), later)
        val earlier = DraftRules.nextPlanTime(LocalTime.of(9, 15), now, india)
        assertEquals(Instant.parse("2026-11-18T03:45:00Z"), earlier)
        // The current minute has started: it means tomorrow, still inside 24 hours.
        val sameMinute = DraftRules.nextPlanTime(LocalTime.of(15, 30), now, india)
        assertEquals(Instant.parse("2026-11-18T10:00:00Z"), sameMinute)
        for (minute in 0 until 24 * 60 step 7) {
            val at = DraftRules.nextPlanTime(LocalTime.MIN.plusMinutes(minute.toLong()), now, india)
            assertTrue(at.isAfter(now) && !at.isAfter(now.plus(Duration.ofHours(24))))
            assertEquals(0, at.epochSecond % 60)
        }
    }

    @Test
    fun `the new-member line ends exactly 7 days after approval`() {
        val verified = Instant.parse("2026-11-10T10:00:30Z")
        assertEquals(verified.plus(Duration.ofDays(7)), DraftRules.newMemberUntil(verified, now.minusNanos(1_000)))
        assertNull(DraftRules.newMemberUntil(verified, now)) // exactly at the end: established
        assertNull(DraftRules.newMemberUntil(verified, now.plusSeconds(1)))
        assertNull(DraftRules.newMemberUntil(null, now))
    }

    // ---- what is sent ----

    @Test
    fun `a Say never sends plan fields, a Who's in does, and nothing that is not an id reaches the server`() {
        val say = MomentRequestDto.from(MomentDraft(MomentKind.SAY, MomentAudience.COMMUNITY, "  hi  ", "not-an-id",
            now, "Canteen", "also-not"))
        assertEquals("SAY", say.kind)
        assertEquals("COMMUNITY", say.audience)
        assertEquals("hi", say.text)
        assertNull(say.mediaId)
        assertNull(say.planAt)
        assertNull(say.planWhere)
        assertNull(say.questionId)
        val id = "11111111-2222-3333-4444-555555555555"
        val whosIn = MomentRequestDto.from(MomentDraft(MomentKind.WHOS_IN, MomentAudience.MY_PEOPLE, "", id, now, "  ", id))
        assertNull("no words next to a photo", whosIn.text)
        assertEquals(id, whosIn.mediaId)
        assertEquals(now.toString(), whosIn.planAt)
        assertNull("a blank where is left out", whosIn.planWhere)
        assertEquals(id, whosIn.questionId)
        assertFalse(whosIn.toString().contains(now.toString()))
        assertFalse(MomentDraft(MomentKind.SAY, MomentAudience.COMMUNITY, "secret words", null, null, null, null)
            .toString().contains("secret"))
    }

    @Test
    fun `an upload answer the app cannot use is not used`() {
        assertNull(UploadedPhotoDto("x", 10, 10).toDomain())
        assertNull(UploadedPhotoDto("11111111-2222-3333-4444-555555555555", 0, 10).toDomain())
        assertEquals(UploadedPhoto("11111111-2222-3333-4444-555555555555", 4, 3),
            UploadedPhotoDto("11111111-2222-3333-4444-555555555555", 4, 3).toDomain())
    }

    // ---- the view model ----

    private class Shot(override val name: String) : MomentShot

    private class FakePhotos : MomentPhotoSource {
        var jpeg: ByteArray? = byteArrayOf(1, 2, 3)
        val prepared = ArrayList<String>()
        val discarded = ArrayList<String>()
        override suspend fun prepare(shot: MomentShot): ByteArray? {
            prepared += shot.name
            return jpeg?.copyOf()
        }
        override fun discard(shot: MomentShot) {
            discarded += shot.name
        }
        override fun shotNamed(name: String): MomentShot = Shot(name)
    }

    private class FakeMemory(var saved: MomentAudience? = null) : AudienceMemory {
        override suspend fun last() = saved
        override suspend fun remember(audience: MomentAudience) {
            saved = audience
        }
    }

    private val repository = FakeMomentRepository()
    private val photos = FakePhotos()
    private val memory = FakeMemory()
    private var status: ApiResult<SharingStatus> = ApiResult.Success(SharingStatus(member = true, newMemberUntil = null))

    private fun TestScope.viewModel(saved: SavedStateHandle = SavedStateHandle(), question: DailyQuestion? = null) =
        CreateViewModel(repository, photos, memory, { status }, this, saved, now = { now }, zone = { india }).also {
            it.start(question)
            advanceUntilIdle()
        }

    @Test
    fun `the first time no audience is chosen, and sharing waits for one`() = runTest {
        val vm = viewModel()
        vm.onText("Badminton at five?")
        assertNull(vm.uiState.value.audience)
        assertFalse(vm.uiState.value.canShare)
        vm.onShare()
        advanceUntilIdle()
        assertTrue(repository.shared.isEmpty())
        vm.onAudience(MomentAudience.MY_PEOPLE)
        assertTrue(vm.uiState.value.canShare)
    }

    @Test
    fun `the last audience chosen on this phone is chosen again, and sharing remembers it`() = runTest {
        memory.saved = MomentAudience.MY_PEOPLE
        val vm = viewModel()
        assertEquals(MomentAudience.MY_PEOPLE, vm.uiState.value.audience)
        vm.onAudience(MomentAudience.COMMUNITY)
        vm.onText("Hello MIET")
        vm.onShare()
        advanceUntilIdle()
        assertEquals(MomentAudience.COMMUNITY, memory.saved)
        assertNotNull(vm.uiState.value.shared)
    }

    @Test
    fun `not a member - Create offers Get verified and nothing else`() = runTest {
        status = ApiResult.Success(SharingStatus(member = false, newMemberUntil = null))
        val vm = viewModel()
        assertTrue(vm.uiState.value.needsMembership)
        vm.onAudience(MomentAudience.COMMUNITY)
        vm.onText("Hi")
        assertFalse(vm.uiState.value.canShare)
    }

    @Test
    fun `a new member is told before sharing with MIET, not for My people`() = runTest {
        val until = now.plus(Duration.ofDays(2))
        status = ApiResult.Success(SharingStatus(member = true, newMemberUntil = until))
        val vm = viewModel()
        assertEquals(until, vm.uiState.value.newMemberUntil)
        assertTrue("shown until an audience is chosen", vm.uiState.value.showsNewMemberLine)
        vm.onAudience(MomentAudience.COMMUNITY)
        assertTrue(vm.uiState.value.showsNewMemberLine)
        vm.onAudience(MomentAudience.MY_PEOPLE)
        assertFalse(vm.uiState.value.showsNewMemberLine)
    }

    @Test
    fun `an unknown membership still opens the draft (the server decides)`() = runTest {
        status = ApiResult.Failure(ApiError.NO_CONNECTION)
        val vm = viewModel()
        assertFalse(vm.uiState.value.loading)
        assertFalse(vm.uiState.value.needsMembership)
    }

    @Test
    fun `a Who's in sends its time and where, the answered question, and trimmed words`() = runTest {
        val question = DailyQuestion("66666666-6666-6666-6666-666666666666", "Best chai on campus?")
        val vm = viewModel(question = question)
        vm.onAudience(MomentAudience.COMMUNITY)
        vm.onKind(MomentKind.WHOS_IN)
        vm.onText("  Chai run?  ")
        vm.onWhere("  Main gate ")
        vm.onPlanTime(LocalTime.of(17, 0))
        vm.onShare()
        advanceUntilIdle()
        val draft = repository.shared.single()
        assertEquals(MomentKind.WHOS_IN, draft.kind)
        assertEquals("Chai run?", draft.text)
        assertEquals("Main gate", draft.planWhere)
        assertEquals(Instant.parse("2026-11-17T11:30:00Z"), draft.planAt)
        assertEquals(question.id, draft.questionId)
    }

    @Test
    fun `a Say never sends the plan typed before switching back`() = runTest {
        val vm = viewModel()
        vm.onAudience(MomentAudience.COMMUNITY)
        vm.onKind(MomentKind.WHOS_IN)
        vm.onWhere("Library")
        vm.onPlanTime(LocalTime.of(17, 0))
        vm.onKind(MomentKind.SAY)
        vm.onText("Just thinking")
        vm.onRemoveQuestion()
        vm.onShare()
        advanceUntilIdle()
        val draft = repository.shared.single()
        assertNull(draft.planAt)
        assertNull(draft.planWhere)
        assertNull(draft.questionId)
    }

    @Test
    fun `too many words cannot be shared`() = runTest {
        val vm = viewModel()
        vm.onAudience(MomentAudience.COMMUNITY)
        vm.onText("a".repeat(501))
        assertFalse(vm.uiState.value.canShare)
        assertEquals(-1, vm.uiState.value.roomLeft)
    }

    @Test
    fun `a photo is prepared, its camera file deleted at once, uploaded at once, and sent by id`() = runTest {
        val vm = viewModel()
        vm.onAudience(MomentAudience.COMMUNITY)
        vm.onCameraOpening(Shot("a.jpg"))
        vm.onCameraResult(true)
        assertTrue(vm.uiState.value.preparingPhoto)
        assertFalse(vm.uiState.value.canShare)
        advanceUntilIdle()
        assertEquals(listOf("a.jpg"), photos.prepared)
        assertEquals(listOf("a.jpg"), photos.discarded)
        assertEquals(1, repository.uploads.size)
        val photo = vm.uiState.value.photo!!
        assertNotNull(photo.uploaded)
        assertTrue("words are optional next to a photo", vm.uiState.value.canShare)
        vm.onShare()
        advanceUntilIdle()
        assertEquals(photo.uploaded!!.mediaId, repository.shared.single().mediaId)
        assertNull("the photo is the moment's now", vm.uiState.value.photo)
        assertTrue("shared: Create closes and cannot share the same draft again", vm.uiState.value.finished)
        assertFalse(vm.uiState.value.canShare)
        assertFalse(vm.uiState.value.hasDraft)
        assertTrue(repository.discardedPhotos.isEmpty())
    }

    @Test
    fun `sharing waits while the photo is uploading`() = runTest {
        repository.uploadGate = CompletableDeferred()
        val vm = viewModel()
        vm.onAudience(MomentAudience.COMMUNITY)
        vm.onText("Look at this sky")
        vm.onCameraOpening(Shot("a.jpg"))
        vm.onCameraResult(true)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.photo!!.uploading)
        assertFalse(vm.uiState.value.canShare)
        repository.uploadGate!!.complete(Unit)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.canShare)
    }

    @Test
    fun `a cancelled camera leaves nothing behind`() = runTest {
        val vm = viewModel()
        vm.onCameraOpening(Shot("a.jpg"))
        vm.onCameraResult(false)
        advanceUntilIdle()
        assertEquals(listOf("a.jpg"), photos.discarded)
        assertTrue(photos.prepared.isEmpty())
        assertTrue(repository.uploads.isEmpty())
        assertNull(vm.uiState.value.photo)
    }

    @Test
    fun `an unreadable photo says so and its file is deleted`() = runTest {
        photos.jpeg = null
        val vm = viewModel()
        vm.onCameraOpening(Shot("a.jpg"))
        vm.onCameraResult(true)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.photoUnreadable)
        assertEquals(listOf("a.jpg"), photos.discarded)
        assertTrue(repository.uploads.isEmpty())
        vm.onPhotoUnreadableShown()
        assertFalse(vm.uiState.value.photoUnreadable)
    }

    @Test
    fun `a failed upload can be tried again`() = runTest {
        repository.uploadResults += ApiResult.Failure(ApiError.NO_CONNECTION, timedOut = true)
        val vm = viewModel()
        vm.onAudience(MomentAudience.COMMUNITY)
        vm.onCameraOpening(Shot("a.jpg"))
        vm.onCameraResult(true)
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.photo!!.failure)
        assertFalse(vm.uiState.value.canShare)
        vm.onRetryUpload()
        advanceUntilIdle()
        assertEquals(2, repository.uploads.size)
        assertNotNull(vm.uiState.value.photo!!.uploaded)
        assertTrue(vm.uiState.value.canShare)
    }

    @Test
    fun `a removed photo is deleted on the server`() = runTest {
        val vm = viewModel()
        vm.onCameraOpening(Shot("a.jpg"))
        vm.onCameraResult(true)
        advanceUntilIdle()
        val id = vm.uiState.value.photo!!.uploaded!!.mediaId
        vm.onRemovePhoto()
        advanceUntilIdle()
        assertNull(vm.uiState.value.photo)
        assertEquals(listOf(id), repository.discardedPhotos)
    }

    @Test
    fun `a photo replaced while its upload runs is never attached, and its upload is deleted`() = runTest {
        repository.uploadGate = CompletableDeferred()
        val vm = viewModel()
        vm.onAudience(MomentAudience.COMMUNITY)
        vm.onCameraOpening(Shot("a.jpg"))
        vm.onCameraResult(true)
        runCurrent()
        vm.onCameraOpening(Shot("b.jpg"))
        vm.onCameraResult(true)
        runCurrent()
        assertEquals(2, repository.uploads.size)
        repository.uploadGate!!.complete(Unit)
        advanceUntilIdle()
        val kept = vm.uiState.value.photo!!.uploaded!!.mediaId
        assertEquals("the first upload is deleted, the second kept", 1, repository.discardedPhotos.size)
        assertFalse(kept in repository.discardedPhotos)
    }

    @Test
    fun `leaving without sharing deletes the uploaded photo`() = runTest {
        val vm = viewModel()
        vm.onText("Draft")
        vm.onCameraOpening(Shot("a.jpg"))
        vm.onCameraResult(true)
        advanceUntilIdle()
        val id = vm.uiState.value.photo!!.uploaded!!.mediaId
        assertTrue(vm.uiState.value.hasDraft)
        vm.onDiscard()
        advanceUntilIdle()
        assertEquals(listOf(id), repository.discardedPhotos)
        assertFalse(vm.uiState.value.hasDraft)
    }

    @Test
    fun `a refusal keeps the draft and the server's reason, and a lost membership offers Get verified`() = runTest {
        repository.shareResult = ApiResult.Failure(ApiError.VALIDATION, "Choose a time within the next 24 hours")
        val vm = viewModel()
        vm.onAudience(MomentAudience.COMMUNITY)
        vm.onText("Plan")
        vm.onShare()
        advanceUntilIdle()
        assertEquals("Choose a time within the next 24 hours", vm.uiState.value.failure?.message)
        assertEquals("Plan", vm.uiState.value.text)
        assertNull(vm.uiState.value.shared)
        repository.shareResult = ApiResult.Failure(ApiError.FORBIDDEN, "Members only", MEMBERSHIP_NEEDED)
        vm.onShare()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.needsMembership)
    }

    @Test
    fun `the camera's photo is picked up again after the app was closed while the camera was open`() = runTest {
        val saved = SavedStateHandle()
        val first = CreateViewModel(repository, photos, memory, { status }, this, saved, now = { now }, zone = { india })
        first.start(null)
        first.onCameraOpening(Shot("11111111-2222-3333-4444-555555555555.jpg"))
        // A new view model (the process was recreated) over the same saved state:
        val again = viewModel(saved)
        assertEquals("11111111-2222-3333-4444-555555555555.jpg", again.pendingShot?.name)
        again.onCameraResult(true)
        advanceUntilIdle()
        assertNotNull(again.uiState.value.photo)
    }

    // ---- guards ----

    private fun source(path: String) = File("src/main/java/com/safesphere/app/$path").readText(Charsets.UTF_8)

    @Test
    fun `Create takes photos with the camera app only, nudges on the phone, and is not screenshot-blocked`() {
        val fragment = source("feature/moments/ui/CreateFragment.kt")
        assertTrue(fragment.contains("ActivityResultContracts.TakePicture()"))
        for (gallery in listOf("PickVisualMedia", "GetContent", "ACTION_PICK", "ACTION_GET_CONTENT", "OpenDocument")) {
            assertFalse("Create must not open $gallery", fragment.contains(gallery))
        }
        assertTrue("the button is hidden without a camera app", fragment.contains("isVisible = appContainer.momentPhotoFiles.hasCameraApp()"))
        assertTrue(fragment.contains("DraftNudgeDialogs.beforeSharing("))
        assertTrue("Today reads again wherever Create was opened from (N4-C walk finding)",
            fragment.contains("nav.getBackStackEntry(R.id.todayFragment) }.getOrNull()?.savedStateHandle?.set(RESULT_SHARED"))
        assertFalse(fragment.contains("previousBackStackEntry"))
        assertTrue("Create leaves once, never popping the screen below it", fragment.contains("if (view == null || closed) return"))
        assertFalse("D12: Create is the person's own words", fragment.contains("secure(true)") || fragment.contains("FLAG_SECURE"))
        val activity = source("MainActivity.kt")
        assertTrue("the toolbar arrow asks before a draft is lost",
            activity.contains("currentDestination?.id == R.id.createFragment) onBackPressedDispatcher.onBackPressed()"))
        val manifest = File("src/main/AndroidManifest.xml").readText(Charsets.UTF_8)
        for (permission in listOf("android.permission.CAMERA\"", "RECORD_AUDIO", "READ_MEDIA_IMAGES", "READ_EXTERNAL_STORAGE")) {
            assertFalse(permission, manifest.contains(permission))
        }
    }

    @Test
    fun `the room left is the writer's own and the account deletion forgets the remembered audience`() {
        val strings = File("src/main/res/values/strings.xml").readText(Charsets.UTF_8)
        assertTrue(strings.contains("<string name=\"create_room_left\">%1\$s left</string>"))
        val uses = File("src/main/java").walkTopDown().filter { it.extension == "kt" && it.readText().contains("R.string.create_room_left") }
            .map { it.name }.toList()
        assertEquals(listOf("CreateFragment.kt"), uses)
        val container = source("AppContainer.kt")
        assertTrue(container.contains("{ userId -> audienceMemory.forget(userId) }"))
    }
}
