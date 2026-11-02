package com.safesphere.app.feature.social

import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.social.data.PlaceMemory
import com.safesphere.app.feature.social.data.PhotoPreparer
import com.safesphere.app.feature.social.data.remote.PersonProfileDto
import com.safesphere.app.feature.social.data.remote.PersonRefDto
import com.safesphere.app.feature.social.data.remote.PostDto
import com.safesphere.app.feature.social.data.remote.photoKey
import com.safesphere.app.feature.social.domain.Cities
import com.safesphere.app.feature.social.domain.DailyState
import com.safesphere.app.feature.social.domain.Interest
import com.safesphere.app.feature.social.domain.PlacePage
import com.safesphere.app.feature.social.domain.PostType
import com.safesphere.app.feature.social.domain.Precision
import com.safesphere.app.feature.social.domain.Relationship
import com.safesphere.app.feature.social.ui.ComposeViewModel
import com.safesphere.app.feature.social.ui.JournalViewModel
import com.safesphere.app.feature.social.ui.MarksFit
import com.safesphere.app.feature.social.ui.PhotoStatus
import com.safesphere.app.feature.social.ui.PostCollection
import com.safesphere.app.feature.social.ui.PostCollectionViewModel
import com.safesphere.app.testing.FakeDiscoverRepository
import com.safesphere.app.testing.FakeExperienceRepository
import com.safesphere.app.testing.TestPick
import com.safesphere.app.testing.FakeMediaSource
import com.safesphere.app.testing.FakePlacesStore
import com.safesphere.app.testing.FakeSocialRepository
import com.safesphere.app.testing.MainDispatcherRule
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
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
 * Phase 17E (docs/PHASE17E.md): the city catalog on the phone (D-5), profile photos, today's state (D-7), "I went"
 * (plan §4) and the marks on every post list (D-3), against in-memory fakes; DTOs fail closed.
 */
class Phase17ETest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    // ---- Cities (D-5) ---------------------------------------------------------------------------------------------

    @Test
    fun `the app bundles exactly the server's catalog`() {
        val app = File("src/main/resources/cities/cities-v1.tsv").readBytes()
        val server = File("../../backend/src/main/resources/cities/cities-v1.tsv").readBytes()
        assertArrayEquals(server, app)
        assertTrue(Cities.all.size >= 60)
        assertEquals("CHENNAI", Cities.all.first().code)
        assertEquals(Cities.all.size, Cities.all.map { it.code }.toSet().size)
        assertTrue(Cities.all.none { it.isOther })
    }

    @Test
    fun `search runs on the phone - names first, then other names, then states, alphabetical, never by popularity`() {
        assertEquals(Cities.all, Cities.search("", limit = 1000))
        assertEquals("MUMBAI", Cities.search("bombay").first().code)          // an other name
        assertEquals("THIRUVANANTHAPURAM", Cities.search("trivandrum").first().code)
        val ma = Cities.search("ma").map { it.name }
        assertEquals(ma.takeWhile { it.lowercase().startsWith("ma") }, ma.takeWhile { it.lowercase().startsWith("ma") }.sorted())
        assertTrue(ma.first().lowercase().startsWith("ma"))
        assertTrue(Cities.search("kerala").map { it.code }.containsAll(listOf("KOCHI", "MUNNAR", "ALAPPUZHA")))
        assertTrue(Cities.search("zzqx").isEmpty())
        assertTrue(Cities.search("  Chen  ").first().code == "CHENNAI")
    }

    @Test
    fun `an area names its city, and a code from the server is found or nothing (never guessed)`() {
        assertEquals("CHENNAI", Cities.cityOf("tf31fw")?.code)
        assertNull(Cities.find("ATLANTIS"))
        assertTrue(Cities.find("OTHER")!!.isOther)
        assertNull(Cities.listCity("OTHER"))
        assertEquals("GOA", Cities.listCity("GOA")?.code)
    }

    // ---- Profile photo ---------------------------------------------------------------------------------------------

    @Test
    fun `a photo key goes into an address only when it is a lower-case uuid`() {
        assertEquals("0a0a0a0a-0000-4000-8000-000000000001", photoKey("0a0a0a0a-0000-4000-8000-000000000001"))
        assertNull(photoKey("0A0A0A0A-0000-4000-8000-000000000001"))
        assertNull(photoKey("../../api/admin"))
        assertNull(photoKey(""))
        assertNull(photoKey(null))
        val author = PostDto(publicId = "p", type = "EXPERIENCE",
            author = PersonRefDto("a", "asha", "Asha", "not-a-key")).toDomain()!!.author!!
        assertNull(author.photo)
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
        val vm = JournalViewModel(social, media)
        vm.load()
        advanceUntilIdle()
        vm.onPhotoPicked(TestPick(com.safesphere.app.feature.social.domain.MediaKind.PHOTO))
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
        val vm = JournalViewModel(social, media)
        vm.load()
        advanceUntilIdle()
        vm.onPhotoPicked(TestPick(com.safesphere.app.feature.social.domain.MediaKind.PHOTO))
        advanceUntilIdle()
        assertEquals(PhotoStatus.UNREADABLE, vm.uiState.value.photo)
        assertTrue(social.uploadedAvatars.isEmpty())
        vm.onPhotoNoticeShown()

        media.avatar = byteArrayOf(1)
        social.uploadPhotoResult = ApiResult.Failure(ApiError.NO_CONNECTION)
        vm.onPhotoPicked(TestPick(com.safesphere.app.feature.social.domain.MediaKind.PHOTO))
        advanceUntilIdle()
        assertEquals(PhotoStatus.FAILED, vm.uiState.value.photo)
        assertNull(vm.uiState.value.profile!!.photo)
    }

    // ---- Today's state (D-7) -------------------------------------------------------------------------------------

    @Test
    fun `today's state is set, changed and cleared, one change at a time`() = runTest {
        val social = FakeSocialRepository()
        val vm = JournalViewModel(social, FakeMediaSource())
        vm.load()
        advanceUntilIdle()
        vm.onDailyState(DailyState.CURIOUS)
        vm.onDailyState(DailyState.BUSY_DAY) // while the first is on its way: ignored
        advanceUntilIdle()
        assertEquals(DailyState.CURIOUS, vm.uiState.value.profile!!.dailyState)
        assertTrue(vm.uiState.value.stateSaved)
        vm.onStateSavedShown()
        vm.onDailyState(null)
        advanceUntilIdle()
        assertNull(vm.uiState.value.profile!!.dailyState)
        assertFalse(vm.uiState.value.stateSaved)
        assertEquals(listOf(DailyState.CURIOUS, null), social.dailyStates)
    }

    @Test
    fun `someone else's state is read only from a connection, and an unknown state is dropped`() {
        fun dto(relationship: String, state: String?) = PersonProfileDto("p", "bala", "Bala", relationship = relationship,
            dailyState = state).toDomain()
        assertEquals(DailyState.QUIET_DAY, dto(Relationship.CONNECTED.name, "QUIET_DAY").dailyState)
        assertNull(dto(Relationship.NONE.name, "QUIET_DAY").dailyState)
        assertNull(dto(Relationship.REQUESTED.name, "QUIET_DAY").dailyState)
        assertNull(dto(Relationship.CONNECTED.name, "TRAVELLING").dailyState)
    }

    @Test
    fun `no daily state word says where someone is or that they are free right now`() {
        val strings = File("src/main/res/values/strings.xml").readText(Charsets.UTF_8)
        val names = DailyState.entries.map { "daily_state_" + it.name.lowercase() }
        val forbidden = listOf("home", "away", "travel", "out of town", "free now", "available", "online", "here", "nearby")
        for (name in names) {
            val text = Regex("<string name=\"$name\">(.*?)</string>").find(strings)?.groupValues?.get(1)
            assertTrue("$name is missing", text != null)
            forbidden.forEach { assertFalse("$name says \"$it\"", text!!.lowercase().contains(it)) }
        }
        val note = Regex("<string name=\"daily_state_sheet_note\">(.*?)</string>").find(strings)!!.groupValues[1]
        assertTrue(note.contains("connections") && note.contains("midnight") && note.contains("Nobody is notified"))
    }

    // ---- "I went" (plan §4) -------------------------------------------------------------------------------------

    @Test
    fun `I went fills in the post's place once, and a place that cannot be read is simply left out`() = runTest {
        val repository = FakeExperienceRepository()
        val place = FakeExperienceRepository.place(id = "pl-1", name = "Marina Beach")
        repository.placeById = mapOf("pl-1" to place)
        val memory = PlaceMemory(FakePlacesStore(), { 7L }, Dispatchers.Unconfined)
        val clock = Clock.fixed(Instant.parse("2026-11-02T10:00:00Z"), ZoneOffset.UTC)
        val vm = ComposeViewModel(repository, memory, FakeMediaSource(), clock)
        advanceUntilIdle()
        vm.prefill("pl-1", Interest.NATURE_OUTDOORS)
        vm.prefill("pl-1", Interest.FOOD_CAFES) // once only (a rotation must not reset what the person changed)
        advanceUntilIdle()
        val state = vm.uiState.value
        assertEquals(place, state.place)
        assertEquals(Precision.AREA, state.precision)    // a chosen place starts at its area (design §4.2)
        assertEquals(PostType.EXPERIENCE, state.type)
        assertEquals(Interest.NATURE_OUTDOORS, state.category)
        assertNull("no audience is chosen for the person (D14)", state.audience)
        assertEquals(listOf("pl-1"), repository.placeReads)

        val other = ComposeViewModel(repository, memory, FakeMediaSource(), clock)
        advanceUntilIdle()
        other.prefill("gone", null)
        advanceUntilIdle()
        assertNull(other.uiState.value.place)
        assertNull(other.uiState.value.failure)
    }

    // ---- Marks on every list (D-3) -------------------------------------------------------------------------------

    @Test
    fun `a place page reacts and saves from its cards, and puts a refused mark back`() = runTest {
        val discover = FakeDiscoverRepository()
        val place = FakeExperienceRepository.place(id = "pl-1")
        discover.placePages = mapOf(null to PlacePage(place, listOf(FakeExperienceRepository.post(id = "a")), null))
        val vm = PostCollectionViewModel(discover)
        vm.show(PostCollection.OfPlace("pl-1"))
        advanceUntilIdle()
        val post = vm.uiState.value.items.single()
        vm.marks.react(post)
        vm.marks.react(post) // the first is still on its way
        advanceUntilIdle()
        assertTrue(vm.uiState.value.items.single().youReacted)
        assertEquals(listOf("react:a"), discover.markCalls)

        discover.markResult = ApiResult.Failure(ApiError.SERVER)
        vm.marks.save(vm.uiState.value.items.single())
        advanceUntilIdle()
        assertFalse(vm.uiState.value.items.single().saved)
        assertEquals(ApiError.SERVER, vm.uiState.value.failure!!.error)
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
