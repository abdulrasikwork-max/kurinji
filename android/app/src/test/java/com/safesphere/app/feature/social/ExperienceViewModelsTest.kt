package com.safesphere.app.feature.social

import com.safesphere.app.core.location.GeoHash
import com.safesphere.app.core.location.LocationAccess
import com.safesphere.app.core.location.LocationUnavailableReason
import com.safesphere.app.core.location.LocationUpdate
import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.social.data.PhotoSource
import com.safesphere.app.feature.social.data.PlaceMemory
import com.safesphere.app.feature.social.data.PlacesData
import com.safesphere.app.feature.social.data.PlacesLoad
import com.safesphere.app.feature.social.domain.Audience
import com.safesphere.app.feature.social.domain.CommentKind
import com.safesphere.app.feature.social.domain.ComposerIssue
import com.safesphere.app.feature.social.domain.Interest
import com.safesphere.app.feature.social.domain.PostPage
import com.safesphere.app.feature.social.domain.PostType
import com.safesphere.app.feature.social.domain.Precision
import com.safesphere.app.feature.social.domain.PrivatePlace
import com.safesphere.app.feature.social.domain.PrivatePlaceKind
import com.safesphere.app.feature.social.ui.ComposeViewModel
import com.safesphere.app.feature.social.ui.PickerNotice
import com.safesphere.app.feature.social.ui.PlacePickerViewModel
import com.safesphere.app.feature.social.ui.PostListViewModel
import com.safesphere.app.feature.social.ui.PostViewModel
import com.safesphere.app.feature.social.ui.PrivatePlacesNotice
import com.safesphere.app.feature.social.ui.PrivatePlacesViewModel
import com.safesphere.app.testing.FakeDiscoverRepository
import com.safesphere.app.testing.FakeExperienceRepository
import com.safesphere.app.testing.FakeLocationProvider
import com.safesphere.app.testing.FakePlacesStore
import com.safesphere.app.testing.MainDispatcherRule
import java.time.Clock
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Phase 13B: composer, place picker, post view, lists and private places against in-memory fakes. */
class ExperienceViewModelsTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeExperienceRepository()
    private val discover = FakeDiscoverRepository()
    private val store = FakePlacesStore()
    private val memory = PlaceMemory(store, { 7L }, Dispatchers.Unconfined)
    private val clock = Clock.fixed(Instant.parse("2026-10-22T10:00:00Z"), ZoneOffset.UTC)
    private val noPhotos = PhotoSource { null }

    private val home = GeoHash.encode(13.0500, 80.2824, GeoHash.BLOCK)
    private val nextDoor = PrivateCells.neighbourOf(home)
    private val farAway = GeoHash.encode(13.0500, 80.3000, GeoHash.BLOCK)

    private fun homeIsPrivate() {
        store.load = PlacesLoad.Loaded(PlacesData(privatePlaces = listOf(PrivatePlace("h", PrivatePlaceKind.HOME, home))))
    }

    // ---- composer ---------------------------------------------------------------------------------------------

    @Test
    fun `composer - no audience is preselected on the first post, and nothing is shared without one`() = runTest {
        val vm = ComposeViewModel(repository, memory, noPhotos, clock)
        advanceUntilIdle()
        assertNull(vm.uiState.value.audience)
        assertEquals(YearMonth.of(2026, 10), vm.uiState.value.month)
        vm.onBody("Went early.")
        vm.onNoPlace()
        vm.onShare()
        advanceUntilIdle()
        assertTrue(ComposerIssue.NO_AUDIENCE in vm.uiState.value.issues)
        assertTrue(vm.uiState.value.showIssues)
        assertTrue(repository.drafts.isEmpty())
    }

    @Test
    fun `composer - sharing sends the draft, remembers the audience and the place, and opens the post`() = runTest {
        val vm = ComposeViewModel(repository, memory, noPhotos, clock)
        advanceUntilIdle()
        vm.onBody("Went early.")
        vm.onPlaceChosen(FakeExperienceRepository.place(cell = farAway))
        assertEquals(Precision.AREA, vm.uiState.value.precision)             // public posts default to the area
        vm.onAudience(Audience.CONNECTIONS)
        vm.onShare()
        advanceUntilIdle()
        assertEquals("new-post", vm.uiState.value.sharedPostId)
        val draft = repository.drafts.single()
        assertEquals(Audience.CONNECTIONS, draft.audience)
        assertEquals(Precision.AREA, draft.precision)
        val saved = (store.load as PlacesLoad.Loaded).data
        assertEquals(Audience.CONNECTIONS, saved.lastAudience)
        assertEquals(farAway, saved.recent.single().cell)
        // The next composer starts from the remembered audience.
        val next = ComposeViewModel(repository, memory, noPhotos, clock)
        advanceUntilIdle()
        assertEquals(Audience.CONNECTIONS, next.uiState.value.audience)
    }

    @Test
    fun `composer - a place near a private place can only be shown as the city or not at all`() = runTest {
        homeIsPrivate()
        val vm = ComposeViewModel(repository, memory, noPhotos, clock)
        advanceUntilIdle()
        vm.onPlaceChosen(FakeExperienceRepository.place(cell = nextDoor))
        val state = vm.uiState.value
        assertTrue(state.placeIsPrivate)
        assertEquals(Precision.CITY, state.precision)
        assertEquals(listOf(Precision.CITY, Precision.NONE), state.allowedPrecisions)
        vm.onPrecision(Precision.EXACT)
        assertEquals(Precision.CITY, vm.uiState.value.precision)             // refused on the phone
    }

    @Test
    fun `composer - unreadable private places fail closed`() = runTest {
        store.load = PlacesLoad.Unreadable
        val vm = ComposeViewModel(repository, memory, noPhotos, clock)
        advanceUntilIdle()
        vm.onPlaceChosen(FakeExperienceRepository.place(cell = farAway))
        assertTrue(vm.uiState.value.privatePlacesUnknown)
        assertTrue(vm.uiState.value.placeIsPrivate)
    }

    @Test
    fun `composer - an exact place says it is delayed`() = runTest {
        val vm = ComposeViewModel(repository, memory, noPhotos, clock)
        advanceUntilIdle()
        vm.onPlaceChosen(FakeExperienceRepository.place(cell = farAway))
        assertFalse(vm.uiState.value.isDelayed)
        vm.onPrecision(Precision.EXACT)
        assertTrue(vm.uiState.value.isDelayed)
    }

    // ---- place picker -----------------------------------------------------------------------------------------

    @Test
    fun `picker - one fix, only the 6-character area is sent, places near a private place are marked`() = runTest {
        homeIsPrivate()
        val location = FakeLocationProvider()
        repository.nearby = listOf(FakeExperienceRepository.place("near-home", cell = nextDoor),
            FakeExperienceRepository.place("elsewhere", cell = farAway))
        val vm = PlacePickerViewModel(repository, memory, location)
        advanceUntilIdle()
        vm.onNearMe()
        runCurrent()
        location.emitFix(FakeLocationProvider.fix(latitude = 13.0500, longitude = 80.3000, accuracy = 10f))
        advanceUntilIdle()
        assertEquals(listOf(farAway.take(6)), repository.nearbyAreas)
        val rows = vm.uiState.value.nearby!!
        assertTrue(rows.first { it.place.publicId == "near-home" }.nearPrivate)
        assertFalse(rows.first { it.place.publicId == "elsewhere" }.nearPrivate)
        assertTrue(vm.uiState.value.canAddHere)
        vm.onAddPlace("Book stall", Interest.HOBBIES_MAKING)
        advanceUntilIdle()
        assertEquals(farAway, repository.createdPlaces.single().third)       // the 7-character cell, nothing more
        assertEquals("new-place", vm.uiState.value.chosen?.publicId)
    }

    @Test
    fun `picker - at a private place no public place can be added`() = runTest {
        homeIsPrivate()
        val location = FakeLocationProvider()
        val vm = PlacePickerViewModel(repository, memory, location)
        advanceUntilIdle()
        vm.onNearMe()
        runCurrent()
        location.emitFix(FakeLocationProvider.fix(latitude = 13.0500, longitude = 80.2824, accuracy = 10f))
        advanceUntilIdle()
        assertTrue(vm.uiState.value.hereIsPrivate)
        assertEquals(PickerNotice.NEAR_PRIVATE, vm.uiState.value.notice)
        vm.onAddPlace("Our street", Interest.CITY_LIFE)
        advanceUntilIdle()
        assertTrue(repository.createdPlaces.isEmpty())
    }

    @Test
    fun `picker - permission denied, location off, and a rough fix are handled`() = runTest {
        val location = FakeLocationProvider(access = LocationAccess.NONE)
        val vm = PlacePickerViewModel(repository, memory, location)
        advanceUntilIdle()
        vm.onNearMe()
        advanceUntilIdle()
        assertEquals(PickerNotice.NEEDS_PERMISSION, vm.uiState.value.notice)
        location.access = LocationAccess.PRECISE
        vm.onNearMe()
        runCurrent()
        location.emit(LocationUpdate.Unavailable(LocationUnavailableReason.LOCATION_DISABLED))
        advanceUntilIdle()
        assertEquals(PickerNotice.LOCATION_OFF, vm.uiState.value.notice)           // Phase 16: its own notice and button
        location.locationEnabled = false
        vm.onNearMe()
        advanceUntilIdle()
        assertEquals("switched off is known at once, no waiting", PickerNotice.LOCATION_OFF, vm.uiState.value.notice)
        location.locationEnabled = true
        location.access = LocationAccess.APPROXIMATE
        vm.onNearMe()
        runCurrent()
        location.emitFix(FakeLocationProvider.fix(latitude = 13.0500, longitude = 80.3000, accuracy = 900f))
        advanceUntilIdle()
        assertFalse(vm.uiState.value.canAddHere)                             // viewing works, adding does not
        assertTrue(vm.uiState.value.nearby != null)
    }

    // ---- post view --------------------------------------------------------------------------------------------

    @Test
    fun `post - kinds fit the post, replies carry the parent, hide and helped reach the server`() = runTest {
        repository.postResult = ApiResult.Success(FakeExperienceRepository.post(type = PostType.ASK))
        repository.perspectives = listOf(FakeExperienceRepository.perspective("a1", CommentKind.ANSWER))
        val vm = PostViewModel(repository, discover, "post-1")
        advanceUntilIdle()
        assertEquals(listOf(CommentKind.ANSWER, CommentKind.QUESTION), vm.uiState.value.kinds)
        assertFalse(vm.uiState.value.canSend)
        vm.onKind(CommentKind.ANSWER)
        vm.onText("Take the bus.")
        vm.onSend()
        advanceUntilIdle()
        assertEquals(Triple(CommentKind.ANSWER, "Take the bus.", null), repository.sentPerspectives.last())
        assertEquals("", vm.uiState.value.text)
        vm.onReplyTo(repository.perspectives.first())
        vm.onText("Which one?")
        vm.onSend()
        advanceUntilIdle()
        assertEquals(Triple(CommentKind.REPLY, "Which one?", "a1"), repository.sentPerspectives.last())
        vm.onHide(repository.perspectives.first(), true)
        vm.onHelped(repository.perspectives.first(), true)
        advanceUntilIdle()
        assertEquals(listOf("a1" to true), repository.hidden)
        assertEquals(listOf("a1" to true), repository.helped)
    }

    @Test
    fun `post - not visible is not found, and a closed post takes no perspective`() = runTest {
        repository.postResult = ApiResult.Failure(ApiError.NOT_FOUND)
        val gone = PostViewModel(repository, discover, "post-1")
        advanceUntilIdle()
        assertTrue(gone.uiState.value.notFound)
        repository.postResult = ApiResult.Success(FakeExperienceRepository.post(canComment = false))
        val closed = PostViewModel(repository, discover, "post-1")
        advanceUntilIdle()
        closed.onKind(CommentKind.QUESTION)
        closed.onText("Hello?")
        assertFalse(closed.uiState.value.canSend)
    }

    @Test
    fun `post - the author deletes the post and the screen closes`() = runTest {
        repository.postResult = ApiResult.Success(FakeExperienceRepository.post(own = true))
        val vm = PostViewModel(repository, discover, "post-1")
        advanceUntilIdle()
        vm.onDeletePostConfirmed()
        advanceUntilIdle()
        assertEquals(listOf("post-1"), repository.deletedPosts)
        assertTrue(vm.uiState.value.closed)
    }

    // ---- lists ------------------------------------------------------------------------------------------------

    @Test
    fun `lists - more only on an explicit tap, with the cursor the server gave`() = runTest {
        repository.pages = mapOf(
            null to PostPage(listOf(FakeExperienceRepository.post("a")), "a"),
            "a" to PostPage(listOf(FakeExperienceRepository.post("b")), null)
        )
        val vm = PostListViewModel(repository)
        vm.showProfile("profile-1")
        advanceUntilIdle()
        assertEquals(listOf("a"), vm.uiState.value.items.map { it.publicId })
        assertTrue(vm.uiState.value.hasMore)
        assertEquals(1, repository.pageRequests.size)                        // nothing loads by itself
        vm.onShowMore()
        advanceUntilIdle()
        assertEquals(listOf("a", "b"), vm.uiState.value.items.map { it.publicId })
        assertFalse(vm.uiState.value.hasMore)
        assertEquals(listOf("profile-1" to null, "profile-1" to "a"), repository.pageRequests)
    }

    @Test
    fun `lists - a profile the viewer may not see shows an empty list, not an error`() = runTest {
        repository.pages = emptyMap()
        val vm = PostListViewModel(repository)
        vm.showProfile("hidden")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.loaded)
        assertTrue(vm.uiState.value.items.isEmpty())
        assertNull(vm.uiState.value.failure)
    }

    // ---- private places ---------------------------------------------------------------------------------------

    @Test
    fun `private places - marked from one precise fix, only the cell is kept, removed again`() = runTest {
        val location = FakeLocationProvider()
        val vm = PrivatePlacesViewModel(memory, location)
        advanceUntilIdle()
        vm.onMarkHere(PrivatePlaceKind.HOSTEL)
        runCurrent()
        location.emitFix(FakeLocationProvider.fix(latitude = 13.0500, longitude = 80.2824, accuracy = 12f))
        advanceUntilIdle()
        assertEquals(PrivatePlacesNotice.ADDED, vm.uiState.value.notice)
        val place = vm.uiState.value.places.single()
        assertEquals(home, place.cell)
        assertEquals(PrivatePlaceKind.HOSTEL, place.kind)
        vm.onRemove(place)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.places.isEmpty())
    }

    @Test
    fun `private places - a rough or approximate fix is refused, an unreadable list can be started again`() = runTest {
        val location = FakeLocationProvider(access = LocationAccess.APPROXIMATE)
        val vm = PrivatePlacesViewModel(memory, location)
        advanceUntilIdle()
        vm.onMarkHere(PrivatePlaceKind.HOME)
        runCurrent()
        location.emitFix(FakeLocationProvider.fix(accuracy = 15f))
        advanceUntilIdle()
        assertEquals(PrivatePlacesNotice.NEEDS_PRECISE, vm.uiState.value.notice)
        store.load = PlacesLoad.Unreadable
        val unreadable = PrivatePlacesViewModel(memory, location)
        advanceUntilIdle()
        assertTrue(unreadable.uiState.value.unreadable)
        unreadable.onReset()
        advanceUntilIdle()
        assertFalse(unreadable.uiState.value.unreadable)
    }

    private object PrivateCells {
        /** A cell sharing a side with [cell] (one cell further east). */
        fun neighbourOf(cell: String): String {
            val b = GeoHash.bounds(cell)
            return GeoHash.encode((b.latMin + b.latMax) / 2, b.lngMax + (b.lngMax - b.lngMin) / 2, GeoHash.BLOCK)
        }
    }
}
