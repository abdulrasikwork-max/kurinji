package com.safesphere.app.feature.social

import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.social.data.remote.ButterflyTallyDto
import com.safesphere.app.feature.social.data.remote.CocoonItemDto
import com.safesphere.app.feature.social.data.remote.DiscoverItemDto
import com.safesphere.app.feature.social.data.remote.EditionDto
import com.safesphere.app.feature.social.data.remote.EditionSectionDto
import com.safesphere.app.feature.social.data.remote.PlaceDto
import com.safesphere.app.feature.social.data.remote.PostDto
import com.safesphere.app.feature.social.domain.ButterflyState
import com.safesphere.app.feature.social.domain.ButterflyTally
import com.safesphere.app.feature.social.domain.City
import com.safesphere.app.feature.social.domain.CityMatch
import com.safesphere.app.feature.social.domain.CocoonPage
import com.safesphere.app.feature.social.domain.DiscoverItem
import com.safesphere.app.feature.social.domain.DiscoverPage
import com.safesphere.app.feature.social.domain.DiscoverReason
import com.safesphere.app.feature.social.domain.EditionSectionKind
import com.safesphere.app.feature.social.domain.Interest
import com.safesphere.app.feature.social.domain.PlacePage
import com.safesphere.app.feature.social.domain.PostPage
import com.safesphere.app.feature.social.domain.PostType
import com.safesphere.app.feature.social.domain.TimeBand
import com.safesphere.app.feature.social.ui.CocoonViewModel
import com.safesphere.app.feature.social.ui.DiscoverFeed
import com.safesphere.app.feature.social.ui.DiscoverUiState
import com.safesphere.app.feature.social.ui.FeedItem
import com.safesphere.app.feature.social.ui.HeaderKind
import com.safesphere.app.feature.social.ui.NearNotice
import com.safesphere.app.feature.social.ui.DiscoverViewModel
import com.safesphere.app.feature.social.ui.PostCollection
import com.safesphere.app.feature.social.ui.PostCollectionViewModel
import com.safesphere.app.feature.social.ui.PostViewModel
import com.safesphere.app.testing.FakeDiscoverRepository
import com.safesphere.app.testing.FakeDiscoverRepository.Companion.cocoonItem
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

/** Phase 13C: the butterfly, the Cocoon, Discover and place/category pages, against in-memory fakes; DTOs fail closed. */
class DiscoverTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val experiences = FakeExperienceRepository()
    private val discover = FakeDiscoverRepository()
    private val social = FakeSocialRepository()
    private val location = FakeLocationProvider()

    private fun discoverVm() = DiscoverViewModel(discover, social, experiences, location)

    // ---- CityMatch ----------------------------------------------------------------------------------------------

    @Test
    fun `profile city text names a launch city or none`() {
        assertEquals(City.CHENNAI, CityMatch.of("Chennai"))
        assertEquals(City.CHENNAI, CityMatch.of("  madras "))
        assertEquals(City.TIRUCHIRAPPALLI, CityMatch.of("Trichy"))
        assertEquals(City.BENGALURU, CityMatch.of("Bangalore"))
        assertEquals(City.PUDUCHERRY, CityMatch.of("Pondicherry"))
        assertEquals(City.THOOTHUKUDI, CityMatch.of("Tuticorin"))
        assertNull(CityMatch.of("Other"))
        assertNull(CityMatch.of("Mumbai"))
        assertNull(CityMatch.of(""))
        assertNull(CityMatch.of(null))
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
        assertNull(PostDto(publicId = "p", type = "EXPERIENCE", yourButterfly = "MAYBE").toDomain()!!.yourButterfly)
        assertNull(CocoonItemDto(PostDto(publicId = "p", type = "EXPERIENCE"), "MAYBE", "THIS_WEEK").toDomain())
        assertEquals(TimeBand.EARLIER,
            CocoonItemDto(PostDto(publicId = "p", type = "EXPERIENCE"), "WANT", "SOON").toDomain()!!.savedPeriod)
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
        assertNull(PlaceDto("pl", "Fort", "FOOD_CAFES", "tf2c1xz", "tf2c1x", "OTHER").toDomain()!!.city)
        assertEquals(City.MADURAI, PlaceDto("pl", "Fort", "FOOD_CAFES", "tf2c1xz", "tf2c1x", "MADURAI").toDomain()!!.city)
    }

    // ---- Post view: the butterfly -----------------------------------------------------------------------------

    @Test
    fun `butterfly saves, marks been with an invitation, and removes`() = runTest {
        val vm = PostViewModel(experiences, discover, "post-1")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.canButterfly)
        vm.onButterfly()
        advanceUntilIdle()
        assertEquals(ButterflyState.WANT, vm.uiState.value.post!!.yourButterfly)
        vm.onBeen()
        advanceUntilIdle()
        assertEquals(ButterflyState.BEEN, vm.uiState.value.post!!.yourButterfly)
        assertTrue(vm.uiState.value.beenInvitation)
        vm.onBeenInvitationHandled()
        vm.onButterfly()
        advanceUntilIdle()
        assertNull(vm.uiState.value.post!!.yourButterfly)
        assertEquals(listOf("post-1" to ButterflyState.WANT, "post-1" to ButterflyState.BEEN, "post-1" to null),
            discover.butterflyCalls)
    }

    @Test
    fun `own posts and Asks have no butterfly`() = runTest {
        experiences.postResult = ApiResult.Success(FakeExperienceRepository.post(own = true))
        val own = PostViewModel(experiences, discover, "post-1")
        advanceUntilIdle()
        assertFalse(own.uiState.value.canButterfly)
        own.onButterfly()
        experiences.postResult = ApiResult.Success(FakeExperienceRepository.post(type = PostType.ASK))
        val ask = PostViewModel(experiences, discover, "post-1")
        advanceUntilIdle()
        assertFalse(ask.uiState.value.canButterfly)
        ask.onBeen()
        advanceUntilIdle()
        assertTrue(discover.butterflyCalls.isEmpty())
    }

    @Test
    fun `a post that became invisible shows as not found`() = runTest {
        val vm = PostViewModel(experiences, discover, "post-1")
        advanceUntilIdle()
        discover.butterflyResult = ApiResult.Failure(ApiError.NOT_FOUND)
        vm.onButterfly()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.notFound)
        assertNull(vm.uiState.value.post)
    }

    // ---- Cocoon -----------------------------------------------------------------------------------------------

    @Test
    fun `cocoon lists, pages on tap, moves and removes`() = runTest {
        discover.cocoonPages = mapOf(
            (ButterflyState.WANT to null) to CocoonPage(listOf(cocoonItem("a"), cocoonItem("b")), "b"),
            (ButterflyState.WANT to "b") to CocoonPage(listOf(cocoonItem("c")), null))
        val vm = CocoonViewModel(discover)
        vm.reload()
        advanceUntilIdle()
        assertEquals(listOf("a", "b"), vm.uiState.value.items.map { it.post.publicId })
        assertTrue(vm.uiState.value.hasMore)
        vm.onShowMore()
        advanceUntilIdle()
        assertEquals(listOf("a", "b", "c"), vm.uiState.value.items.map { it.post.publicId })
        assertFalse(vm.uiState.value.hasMore)

        vm.onMarkBeen(vm.uiState.value.items[0])
        advanceUntilIdle()
        assertEquals(listOf("b", "c"), vm.uiState.value.items.map { it.post.publicId })
        assertEquals("a", vm.uiState.value.beenInvitation!!.post.publicId)
        vm.onBeenInvitationHandled()
        assertNull(vm.uiState.value.beenInvitation)

        discover.removeResult = ApiResult.Failure(ApiError.NOT_FOUND) // already gone elsewhere: the list catches up
        vm.onRemove(vm.uiState.value.items[0])
        advanceUntilIdle()
        assertEquals(listOf("c"), vm.uiState.value.items.map { it.post.publicId })
        assertNull(vm.uiState.value.failure)

        vm.onTab(ButterflyState.BEEN)
        advanceUntilIdle()
        assertEquals(ButterflyState.BEEN to null, discover.cocoonRequests.last())
        assertTrue(vm.uiState.value.loaded && vm.uiState.value.items.isEmpty())
    }

    @Test
    fun `want again moves an item back without an invitation`() = runTest {
        discover.cocoonPages = mapOf((ButterflyState.BEEN to null) to CocoonPage(listOf(cocoonItem("x", ButterflyState.BEEN)), null))
        val vm = CocoonViewModel(discover)
        vm.onTab(ButterflyState.BEEN)
        advanceUntilIdle()
        vm.onWantAgain(vm.uiState.value.items[0])
        advanceUntilIdle()
        assertTrue(vm.uiState.value.items.isEmpty())
        assertNull(vm.uiState.value.beenInvitation)
        assertEquals("x" to ButterflyState.WANT, discover.butterflyCalls.last())
    }

    // ---- Discover ---------------------------------------------------------------------------------------------

    @Test
    fun `the edition city comes from the profile when it names a launch city`() = runTest {
        social.ownProfileResult = ApiResult.Success(FakeSocialRepository.ME.copy(city = "Madras"))
        val vm = discoverVm()
        vm.start()
        advanceUntilIdle()
        assertEquals(City.CHENNAI, vm.uiState.value.city)
        assertFalse(vm.uiState.value.needsCity)
        assertEquals(listOf(City.CHENNAI), discover.editionCities)
    }

    @Test
    fun `without a launch city in the profile, the person chooses one`() = runTest {
        social.ownProfileResult = ApiResult.Success(FakeSocialRepository.ME.copy(city = "Mumbai"))
        val vm = discoverVm()
        vm.start()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.needsCity)
        assertTrue(discover.editionCities.isEmpty())
        vm.onCity(City.OTHER)
        assertTrue("OTHER has no edition", discover.editionCities.isEmpty())
        vm.onCity(City.MADURAI)
        advanceUntilIdle()
        assertEquals(listOf(City.MADURAI), discover.editionCities)
        assertEquals(City.MADURAI, vm.uiState.value.edition!!.city)
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
    fun `saving from the feed marks the same post everywhere, and a second tap takes it out`() = runTest {
        val post = FakeExperienceRepository.post(id = "s1")
        discover.connectionPages = mapOf(null to DiscoverPage(listOf(DiscoverItem(post, emptyList())), null))
        discover.butterflyResult = ApiResult.Success(ButterflyState.WANT)
        val vm = discoverVm()
        vm.start()
        advanceUntilIdle()
        vm.onSave(post)
        vm.onSave(post) // a double tap sends nothing twice
        advanceUntilIdle()
        assertEquals(listOf("s1" to ButterflyState.WANT), discover.butterflyCalls)
        val saved = vm.uiState.value.connections.single().post
        assertEquals(ButterflyState.WANT, saved.yourButterfly)
        assertTrue(vm.uiState.value.savedNow)
        vm.onSavedShown()
        vm.onSave(saved)
        advanceUntilIdle()
        assertEquals("s1" to null, discover.butterflyCalls.last())
        assertNull(vm.uiState.value.connections.single().post.yourButterfly)
        assertFalse(vm.uiState.value.savedNow)
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
        val edition = Edition(City.CHENNAI, null, Interest.HIDDEN_GEMS, listOf(
            EditionSection(EditionSectionKind.CITY, (1..4).map { item("c$it") }),
            EditionSection(EditionSectionKind.ASKS, listOf(item("a1", PostType.ASK), item("a2", PostType.ASK))),
            EditionSection(EditionSectionKind.THEME, listOf(item("c1"), item("t1"))),
            EditionSection(EditionSectionKind.TALKING, listOf(item("c2")))))
        val state = DiscoverUiState(city = City.CHENNAI, edition = edition,
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
        val state = DiscoverUiState(city = City.CHENNAI, edition = Edition(City.CHENNAI, null, null, emptyList()),
            connectionsLoaded = true)
        val feed = DiscoverFeed.build(state)
        assertTrue(FeedItem.ConnectPrompt in feed)
        assertEquals(FeedItem.Empty, feed.last())

        // Emulator finding: a connection's posts already shown in the city list must not turn into "find people".
        val shown = DiscoverItem(FakeExperienceRepository.post(id = "b1"), listOf(DiscoverReason.IN_THIS_CITY))
        val connected = DiscoverUiState(city = City.CHENNAI, connectionsLoaded = true, connections = listOf(shown),
            edition = Edition(City.CHENNAI, null, null, listOf(EditionSection(EditionSectionKind.CITY, listOf(shown)))))
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
        vm.onCity(City.THANJAVUR)
        vm.onQuery(" fort ")
        vm.onSearch()
        advanceUntilIdle()
        assertEquals(listOf("fort" to City.THANJAVUR), discover.searches)
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
        vm.show(PostCollection.OfCategory(Interest.HIDDEN_GEMS, City.VELLORE))
        advanceUntilIdle()
        assertEquals(Triple(Interest.HIDDEN_GEMS, City.VELLORE, null), discover.categoryRequests.single())
        assertEquals(listOf("g1"), vm.uiState.value.items.map { it.publicId })
    }
}
