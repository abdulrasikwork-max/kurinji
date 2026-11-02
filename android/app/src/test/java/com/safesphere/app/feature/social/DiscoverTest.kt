package com.safesphere.app.feature.social

import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.social.data.remote.ButterflyTallyDto
import com.safesphere.app.feature.social.data.remote.DiscoverItemDto
import com.safesphere.app.feature.social.data.remote.EditionDto
import com.safesphere.app.feature.social.data.remote.EditionSectionDto
import com.safesphere.app.feature.social.data.remote.PlaceDto
import com.safesphere.app.feature.social.data.remote.PostDto
import com.safesphere.app.feature.social.data.remote.SavedItemDto
import com.safesphere.app.feature.social.domain.ButterflyTally
import com.safesphere.app.feature.social.domain.Cities
import com.safesphere.app.feature.social.domain.City
import com.safesphere.app.feature.social.domain.DiscoverItem
import com.safesphere.app.feature.social.domain.DiscoverPage
import com.safesphere.app.feature.social.domain.DiscoverReason
import com.safesphere.app.feature.social.domain.EditionSectionKind
import com.safesphere.app.feature.social.domain.Interest
import com.safesphere.app.feature.social.domain.PlacePage
import com.safesphere.app.feature.social.domain.PostPage
import com.safesphere.app.feature.social.domain.PostType
import com.safesphere.app.feature.social.domain.SavedPage
import com.safesphere.app.feature.social.domain.TimeBand
import com.safesphere.app.feature.social.ui.DiscoverFeed
import com.safesphere.app.feature.social.ui.DiscoverUiState
import com.safesphere.app.feature.social.ui.FeedItem
import com.safesphere.app.feature.social.ui.HeaderKind
import com.safesphere.app.feature.social.ui.NearNotice
import com.safesphere.app.feature.social.ui.DiscoverViewModel
import com.safesphere.app.feature.social.ui.PostCollection
import com.safesphere.app.feature.social.ui.PostCollectionViewModel
import com.safesphere.app.feature.social.ui.PostViewModel
import com.safesphere.app.feature.social.ui.SavedViewModel
import com.safesphere.app.testing.FakeDiscoverRepository
import com.safesphere.app.testing.FakeDiscoverRepository.Companion.savedItem
import com.safesphere.app.testing.FakeExperienceRepository
import com.safesphere.app.testing.FakeLocationProvider
import com.safesphere.app.core.location.LocationAccess
import com.safesphere.app.feature.social.domain.Edition
import com.safesphere.app.feature.social.domain.EditionSection
import kotlinx.coroutines.test.runCurrent
import com.safesphere.app.testing.FakeSocialRepository
import com.safesphere.app.testing.MainDispatcherRule
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Phase 13C, 17E: the butterfly (a reaction), Saved (a bookmark), Discover and place/category pages, against in-memory
 * fakes; DTOs fail closed.
 */
class DiscoverTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val experiences = FakeExperienceRepository()
    private val discover = FakeDiscoverRepository()
    private val social = FakeSocialRepository()
    private val location = FakeLocationProvider()

    private fun discoverVm() = DiscoverViewModel(discover, social, experiences, location)

    private fun city(code: String): City = Cities.find(code)!!

    // ---- Cities.match (was CityMatch) --------------------------------------------------------------------------

    @Test
    fun `profile city text names a catalog city or none`() {
        assertEquals("CHENNAI", Cities.match("Chennai")?.code)
        assertEquals("CHENNAI", Cities.match("  madras ")?.code)
        assertEquals("TIRUCHIRAPPALLI", Cities.match("Trichy")?.code)
        assertEquals("BENGALURU", Cities.match("Bangalore")?.code)
        assertEquals("PUDUCHERRY", Cities.match("Pondicherry")?.code)
        assertEquals("THOOTHUKUDI", Cities.match("Tuticorin")?.code)
        assertEquals("MUMBAI", Cities.match("Bombay")?.code)          // Phase 17E: the catalog reaches beyond Tamil Nadu
        assertNull(Cities.match("Other"))
        assertNull(Cities.match("Atlantis"))
        assertNull(Cities.match(""))
        assertNull(Cities.match(null))
    }

    // ---- DTOs fail closed -----------------------------------------------------------------------------------------

    @Test
    fun `a tally reaches only the author and only when consistent`() {
        val own = PostDto(publicId = "p", type = "EXPERIENCE", own = true, butterflies = ButterflyTallyDto(5, false))
        assertEquals(ButterflyTally(5, false), own.toDomain()!!.butterflies)
        val notOwn = own.copy(own = false)
        assertNull("a tally on someone else's post is dropped", notOwn.toDomain()!!.butterflies)
        val tooSmall = own.copy(butterflies = ButterflyTallyDto(2, false))
        assertNull("a number under the floor is dropped", tooSmall.toDomain()!!.butterflies)
        val unclear = own.copy(butterflies = ButterflyTallyDto(4, null))
        assertNull("an unknown flag means 'fewer than 3' and a number then contradicts it", unclear.toDomain()!!.butterflies)
        val fewer = own.copy(butterflies = ButterflyTallyDto(null, true))
        assertEquals(ButterflyTally(null, true), fewer.toDomain()!!.butterflies)
    }

    @Test
    fun `unknown states, kinds, reasons and cities are never guessed`() {
        // Phase 17E: the viewer's marks are read only as a clear true; on one's own post they never count.
        val plain = PostDto(publicId = "p", type = "EXPERIENCE").toDomain()!!
        assertFalse(plain.youReacted || plain.saved)
        val marked = PostDto(publicId = "p", type = "EXPERIENCE", youReacted = true, saved = true).toDomain()!!
        assertTrue(marked.youReacted && marked.saved)
        val own = PostDto(publicId = "p", type = "EXPERIENCE", own = true, youReacted = true, saved = true).toDomain()!!
        assertFalse(own.youReacted || own.saved)
        assertNull(SavedItemDto(null, "THIS_WEEK").toDomain())
        assertEquals(TimeBand.EARLIER, SavedItemDto(PostDto(publicId = "p", type = "EXPERIENCE"), "SOON").toDomain()!!.savedPeriod)
        val item = DiscoverItemDto(PostDto(publicId = "p", type = "EXPERIENCE"), listOf("HAS_TIP", "MOST_LIKED", "HAS_TIP"))
        assertEquals(listOf(DiscoverReason.HAS_TIP), item.toDomain()!!.reasons)
        val edition = EditionDto("SALEM", "2026-10-19", "HIDDEN_GEMS", listOf(
            EditionSectionDto("TRENDING", listOf(item)),
            EditionSectionDto("CITY", listOf(item)),
            EditionSectionDto("ASKS", emptyList())))
        val parsed = edition.toDomain()!!
        assertEquals(listOf(EditionSectionKind.CITY), parsed.sections.map { it.kind })
        assertEquals(Interest.HIDDEN_GEMS, parsed.theme)
        assertNull(EditionDto("OTHER", null, null, null).toDomain())
        assertNull(EditionDto("ATLANTIS", null, null, null).toDomain())
        assertEquals("KOCHI", EditionDto("KOCHI", null, null, null).toDomain()!!.city.code)
        assertNull(PlaceDto("pl", "Fort", "FOOD_CAFES", "tf2c1xz", "tf2c1x", "OTHER").toDomain()!!.city)
        assertNull(PlaceDto("pl", "Fort", "FOOD_CAFES", "tf2c1xz", "tf2c1x", "ATLANTIS").toDomain()!!.city)
        assertEquals(city("MADURAI"), PlaceDto("pl", "Fort", "FOOD_CAFES", "tf2c1xz", "tf2c1x", "MADURAI").toDomain()!!.city)
        // A CITY post may name OTHER; an unknown code is no city.
        assertTrue(PostDto(publicId = "p", type = "EXPERIENCE", precision = "CITY", city = "OTHER").toDomain()!!.city!!.isOther)
        assertNull(PostDto(publicId = "p", type = "EXPERIENCE", precision = "CITY", city = "ATLANTIS").toDomain()!!.city)
    }

    // ---- Post view: the butterfly and Save --------------------------------------------------------------------

    @Test
    fun `the butterfly and save are shown at once, sent once, and taken back`() = runTest {
        val vm = PostViewModel(experiences, discover, "post-1")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.canReact && vm.uiState.value.canSave)
        vm.onReact()
        assertTrue("shown before the answer", vm.uiState.value.post!!.youReacted)
        vm.onReact() // a double tap while the first is on its way sends nothing
        advanceUntilIdle()
        vm.onSave()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.post!!.saved)
        assertTrue(vm.uiState.value.savedNow)
        vm.onSavedShown()
        vm.onReact()
        vm.onSave()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.post!!.youReacted || vm.uiState.value.post!!.saved)
        assertFalse(vm.uiState.value.savedNow)
        assertEquals(listOf("react:post-1", "save:post-1", "unreact:post-1", "unsave:post-1"), discover.markCalls)
    }

    @Test
    fun `a refused mark is put back and says why`() = runTest {
        val vm = PostViewModel(experiences, discover, "post-1")
        advanceUntilIdle()
        discover.markResult = ApiResult.Failure(ApiError.NO_CONNECTION)
        vm.onSave()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.post!!.saved)
        assertFalse(vm.uiState.value.savedNow)
        assertEquals(ApiError.NO_CONNECTION, vm.uiState.value.failure!!.error)
    }

    @Test
    fun `own posts have no marks, and Questions are saved but get no butterfly`() = runTest {
        experiences.postResult = ApiResult.Success(FakeExperienceRepository.post(own = true))
        val own = PostViewModel(experiences, discover, "post-1")
        advanceUntilIdle()
        assertFalse(own.uiState.value.canReact || own.uiState.value.canSave)
        own.onReact()
        own.onSave()
        experiences.postResult = ApiResult.Success(FakeExperienceRepository.post(type = PostType.ASK))
        val ask = PostViewModel(experiences, discover, "post-1")
        advanceUntilIdle()
        assertFalse(ask.uiState.value.canReact)
        assertTrue(ask.uiState.value.canSave)
        ask.onReact()
        ask.onSave()
        advanceUntilIdle()
        assertEquals(listOf("save:post-1"), discover.markCalls)
    }

    @Test
    fun `a post that became invisible shows as not found`() = runTest {
        val vm = PostViewModel(experiences, discover, "post-1")
        advanceUntilIdle()
        discover.markResult = ApiResult.Failure(ApiError.NOT_FOUND)
        vm.onReact()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.notFound)
        assertNull(vm.uiState.value.post)
    }

    // ---- Saved ------------------------------------------------------------------------------------------------

    @Test
    fun `saved lists newest first, loads more near the end, and removes at once`() = runTest {
        discover.savedPages = mapOf(
            null to SavedPage(listOf(savedItem("a"), savedItem("b")), "b"),
            "b" to SavedPage(listOf(savedItem("c"), savedItem("a")), null)) // a repeat is shown once
        val vm = SavedViewModel(discover)
        vm.reload()
        advanceUntilIdle()
        assertEquals(listOf("a", "b"), vm.uiState.value.items.map { it.post.publicId })
        assertTrue(vm.uiState.value.hasMore)
        vm.onShowMore()
        advanceUntilIdle()
        assertEquals(listOf("a", "b", "c"), vm.uiState.value.items.map { it.post.publicId })
        assertFalse(vm.uiState.value.hasMore)
        vm.onShowMore()
        advanceUntilIdle()
        assertEquals("no request after the end", listOf(null, "b"), discover.savedCursors)

        vm.onRemove(vm.uiState.value.items[1])
        assertEquals(listOf("a", "c"), vm.uiState.value.items.map { it.post.publicId })
        advanceUntilIdle()
        assertEquals(listOf("unsave:b"), discover.markCalls)
    }

    @Test
    fun `a removal the server refused comes back where it was`() = runTest {
        discover.savedPages = mapOf(null to SavedPage(listOf(savedItem("a"), savedItem("b"), savedItem("c")), null))
        val vm = SavedViewModel(discover)
        vm.reload()
        advanceUntilIdle()
        discover.markResult = ApiResult.Failure(ApiError.NO_CONNECTION)
        vm.onRemove(vm.uiState.value.items[1])
        advanceUntilIdle()
        assertEquals(listOf("a", "b", "c"), vm.uiState.value.items.map { it.post.publicId })
        assertEquals(ApiError.NO_CONNECTION, vm.uiState.value.failure!!.error)
    }

    @Test
    fun `an empty saved list says so, a failure does not look empty`() = runTest {
        val vm = SavedViewModel(discover)
        vm.reload()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.isEmpty)
        discover.savedFailure = ApiResult.Failure(ApiError.SERVER)
        val failing = SavedViewModel(discover)
        failing.reload()
        advanceUntilIdle()
        assertFalse(failing.uiState.value.isEmpty)
        assertEquals(ApiError.SERVER, failing.uiState.value.failure!!.error)
    }

    // ---- Discover ---------------------------------------------------------------------------------------------

    @Test
    fun `the edition city comes from the profile when it names a catalog city`() = runTest {
        social.ownProfileResult = ApiResult.Success(FakeSocialRepository.ME.copy(city = "Madras"))
        val vm = discoverVm()
        vm.start()
        advanceUntilIdle()
        assertEquals(city("CHENNAI"), vm.uiState.value.city)
        assertFalse(vm.uiState.value.needsCity)
        assertEquals(listOf(city("CHENNAI")), discover.editionCities)
    }

    @Test
    fun `without a known city in the profile, the person chooses one`() = runTest {
        social.ownProfileResult = ApiResult.Success(FakeSocialRepository.ME.copy(city = "Atlantis"))
        val vm = discoverVm()
        vm.start()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.needsCity)
        assertTrue(discover.editionCities.isEmpty())
        vm.onCity(Cities.other)
        assertTrue("OTHER has no edition", discover.editionCities.isEmpty())
        vm.onCity(city("MADURAI"))
        advanceUntilIdle()
        assertEquals(listOf(city("MADURAI")), discover.editionCities)
        assertEquals(city("MADURAI"), vm.uiState.value.edition!!.city)
    }

    @Test
    fun `people you know load with the feed, the next page near the end, and then it ends (U3)`() = runTest {
        val item = DiscoverItem(FakeExperienceRepository.post(id = "c1"), listOf(DiscoverReason.FROM_A_CONNECTION))
        discover.connectionPages = mapOf(
            null to DiscoverPage(listOf(item), "c1"),
            "c1" to DiscoverPage(listOf(item.copy(post = FakeExperienceRepository.post(id = "c2"))), null))
        val vm = discoverVm()
        vm.start()
        advanceUntilIdle()
        assertEquals(listOf<String?>(null), discover.connectionCursors)
        assertTrue(vm.uiState.value.connectionsHasMore)
        assertEquals(FeedItem.Loading, DiscoverFeed.build(vm.uiState.value).last())
        vm.onNearEnd()
        advanceUntilIdle()
        assertEquals(listOf("c1", "c2"), vm.uiState.value.connections.map { it.post.publicId })
        assertFalse(vm.uiState.value.connectionsHasMore)
        assertEquals(FeedItem.End, DiscoverFeed.build(vm.uiState.value).last())
        vm.onNearEnd()
        advanceUntilIdle()
        assertEquals("no request after the end", 2, discover.connectionCursors.size)
    }


    @Test
    fun `marks from the feed change the same post everywhere, once per tap, and are put back when refused`() = runTest {
        val post = FakeExperienceRepository.post(id = "s1")
        discover.connectionPages = mapOf(null to DiscoverPage(listOf(DiscoverItem(post, emptyList())), null))
        val vm = discoverVm()
        vm.start()
        advanceUntilIdle()
        vm.onSave(post)
        vm.onSave(post) // a double tap sends nothing twice
        vm.onReact(post)
        advanceUntilIdle()
        assertEquals(listOf("save:s1", "react:s1"), discover.markCalls)
        val marked = vm.uiState.value.connections.single().post
        assertTrue(marked.saved && marked.youReacted)
        assertEquals("s1", vm.uiState.value.savedNow)
        vm.onSavedShown()
        assertNull(vm.uiState.value.savedNow)

        discover.markResult = ApiResult.Failure(ApiError.NO_CONNECTION)
        vm.onSave(marked)
        assertFalse("shown at once", vm.uiState.value.connections.single().post.saved)
        advanceUntilIdle()
        assertTrue("put back", vm.uiState.value.connections.single().post.saved)
        assertEquals("unsave:s1", discover.markCalls.last())
    }
    @Test
    fun `near me sends only the 6-character area, and says what to do when location cannot help`() = runTest {
        experiences.nearby = listOf(FakeExperienceRepository.place())
        val vm = discoverVm()
        vm.onNearMe()
        runCurrent()
        location.emitFix(FakeLocationProvider.fix(latitude = 13.0500, longitude = 80.2824, accuracy = 400f))
        advanceUntilIdle()
        assertEquals(1, experiences.nearbyAreas.size)
        assertEquals(6, experiences.nearbyAreas.single().length)
        assertEquals(1, vm.uiState.value.nearby?.size)

        location.access = LocationAccess.NONE
        vm.onNearMe()
        advanceUntilIdle()
        assertEquals(NearNotice.NEEDS_PERMISSION, vm.uiState.value.nearNotice)
        location.access = LocationAccess.APPROXIMATE
        location.locationEnabled = false
        vm.onNearMe()
        advanceUntilIdle()
        assertEquals(NearNotice.LOCATION_OFF, vm.uiState.value.nearNotice)
        assertEquals("no request without a fix", 1, experiences.nearbyAreas.size)
    }

    @Test
    fun `the feed - talking now, the city with a question after every third post, the theme, then people you know`() {
        fun item(id: String, type: PostType = PostType.EXPERIENCE) =
            DiscoverItem(FakeExperienceRepository.post(id = id, type = type), listOf(DiscoverReason.IN_THIS_CITY))
        val edition = Edition(city("CHENNAI"), null, Interest.HIDDEN_GEMS, listOf(
            EditionSection(EditionSectionKind.CITY, (1..4).map { item("c$it") }),
            EditionSection(EditionSectionKind.ASKS, listOf(item("a1", PostType.ASK), item("a2", PostType.ASK))),
            EditionSection(EditionSectionKind.THEME, listOf(item("c1"), item("t1"))),
            EditionSection(EditionSectionKind.TALKING, listOf(item("c2")))))
        val state = DiscoverUiState(city = city("CHENNAI"), edition = edition,
            connections = listOf(item("t1"), item("k1")), connectionsLoaded = true)
        val feed = DiscoverFeed.build(state)
        val order = feed.map {
            when (it) {
                is FeedItem.Header -> "#" + it.kind
                is FeedItem.Talking -> "rail:" + it.items.joinToString { i -> i.post.publicId }
                is FeedItem.PostRow -> it.item.post.publicId
                else -> it.javaClass.simpleName
            }
        }
        assertEquals(listOf("Shortcuts", "#TALKING", "rail:c2", "#CITY", "c1", "c2", "c3", "a1", "c4", "a2",
            "#THEME", "t1", "#CONNECTIONS", "k1", "End"), order)
        assertEquals(HeaderKind.THEME, (feed[10] as FeedItem.Header).kind)
    }

    @Test
    fun `the feed ends with an invitation to find people when nobody you know has posted`() {
        val state = DiscoverUiState(city = city("CHENNAI"), edition = Edition(city("CHENNAI"), null, null, emptyList()),
            connectionsLoaded = true)
        val feed = DiscoverFeed.build(state)
        assertTrue(FeedItem.ConnectPrompt in feed)
        assertEquals(FeedItem.Empty, feed.last())

        // Emulator finding: a connection's posts already shown in the city list must not turn into "find people".
        val shown = DiscoverItem(FakeExperienceRepository.post(id = "b1"), listOf(DiscoverReason.IN_THIS_CITY))
        val connected = DiscoverUiState(city = city("CHENNAI"), connectionsLoaded = true, connections = listOf(shown),
            edition = Edition(city("CHENNAI"), null, null, listOf(EditionSection(EditionSectionKind.CITY, listOf(shown)))))
        val again = DiscoverFeed.build(connected)
        assertFalse(FeedItem.ConnectPrompt in again)
        assertEquals(FeedItem.End, again.last())
    }

    @Test
    fun `search needs two characters and searches places in the chosen city`() = runTest {
        val vm = discoverVm()
        vm.onQuery("a")
        vm.onSearch()
        advanceUntilIdle()
        assertTrue(discover.searches.isEmpty())
        vm.onCity(city("THANJAVUR"))
        vm.onQuery(" fort ")
        vm.onSearch()
        advanceUntilIdle()
        assertEquals(listOf("fort" to city("THANJAVUR")), discover.searches)
        assertTrue(vm.uiState.value.searched)
        vm.onClearSearch()
        assertFalse(vm.uiState.value.searched)
        assertEquals("", vm.uiState.value.query)
    }

    // ---- Place and category pages --------------------------------------------------------------------------------

    @Test
    fun `a place page shows the place, pages on tap, and a missing place is not an error`() = runTest {
        val place = FakeExperienceRepository.place()
        discover.placePages = mapOf(
            null to PlacePage(place, listOf(FakeExperienceRepository.post(id = "p1")), "p1"),
            "p1" to PlacePage(place, listOf(FakeExperienceRepository.post(id = "p2")), null))
        val vm = PostCollectionViewModel(discover)
        vm.show(PostCollection.OfPlace("place-1"))
        advanceUntilIdle()
        assertEquals("Marina Beach", vm.uiState.value.place!!.name)
        vm.onShowMore()
        advanceUntilIdle()
        assertEquals(listOf("p1", "p2"), vm.uiState.value.items.map { it.publicId })
        assertFalse(vm.uiState.value.hasMore)

        val gone = PostCollectionViewModel(discover)
        gone.show(PostCollection.OfPlace("missing"))
        advanceUntilIdle()
        discover.placePages = emptyMap()
        gone.reload()
        advanceUntilIdle()
        assertTrue(gone.uiState.value.notFound)
        assertNull(gone.uiState.value.failure)
    }

    @Test
    fun `a category page asks for its category and city`() = runTest {
        discover.categoryPages = mapOf(null to PostPage(listOf(FakeExperienceRepository.post(id = "g1")), null))
        val vm = PostCollectionViewModel(discover)
        vm.show(PostCollection.OfCategory(Interest.HIDDEN_GEMS, city("VELLORE")))
        advanceUntilIdle()
        assertEquals(Triple(Interest.HIDDEN_GEMS, city("VELLORE"), null), discover.categoryRequests.single())
        assertEquals(listOf("g1"), vm.uiState.value.items.map { it.publicId })
    }
}
