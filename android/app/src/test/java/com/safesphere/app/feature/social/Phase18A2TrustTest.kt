package com.safesphere.app.feature.social

import com.safesphere.app.core.location.GeoHash
import com.safesphere.app.core.location.LocationAccess
import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.social.data.remote.PostDto
import com.safesphere.app.feature.social.data.remote.PostRequestDto
import com.safesphere.app.feature.social.data.remote.WentRequestDto
import com.safesphere.app.feature.social.domain.Audience
import com.safesphere.app.feature.social.domain.CommentPolicy
import com.safesphere.app.feature.social.domain.PlacePage
import com.safesphere.app.feature.social.domain.PostDraft
import com.safesphere.app.feature.social.domain.PostType
import com.safesphere.app.feature.social.domain.Precision
import com.safesphere.app.feature.social.domain.Visit
import com.safesphere.app.feature.social.ui.ExperienceCards
import com.safesphere.app.feature.social.ui.PostCollection
import com.safesphere.app.feature.social.ui.PostCollectionViewModel
import com.safesphere.app.feature.social.ui.PostViewModel
import com.safesphere.app.feature.social.ui.PresenceCell
import com.safesphere.app.testing.FakeDiscoverRepository
import com.safesphere.app.testing.FakeExperienceRepository
import com.safesphere.app.testing.FakeLocationProvider
import com.safesphere.app.testing.MainDispatcherRule
import java.io.File
import java.time.YearMonth
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Phase 18A-2 on the phone. Presence: after "Been here" the phone MAY send its cell, and only when it found by itself that it is
 * at the place; it never asks for a permission, never uses a rough position, never makes the person wait and never shows a result.
 * Disclosure: "I was paid, or got this free" travels with an Experience only and is shown as words on the post.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Phase18A2TrustTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val discover = FakeDiscoverRepository()
    private val experiences = FakeExperienceRepository()
    private val place = FakeExperienceRepository.place()
    private val location = FakeLocationProvider()

    private val bounds = GeoHash.bounds(place.cell)
    private val height = bounds.latMax - bounds.latMin
    private val width = bounds.lngMax - bounds.lngMin
    private val atPlace = bounds.centre
    private val hereCell = GeoHash.encode(atPlace.latitude, atPlace.longitude, GeoHash.BLOCK)

    private fun placeVm(): PostCollectionViewModel {
        discover.placePages = mapOf(null to PlacePage(place, listOf(FakeExperienceRepository.post(id = "p1")), null, null, null))
        return PostCollectionViewModel(discover, location).also { it.show(PostCollection.OfPlace("place-1")) }
    }

    // ---- "near the place" is decided on the phone ----

    @Test
    fun `near means the place's own cell or one of the eight around it, and nothing further`() {
        assertEquals(place.cell, hereCell)
        assertTrue(PresenceCell.near(place.cell, atPlace.latitude, atPlace.longitude))
        for (dLat in -1..1) for (dLng in -1..1) {
            assertTrue("neighbour $dLat,$dLng",
                PresenceCell.near(place.cell, atPlace.latitude + dLat * height, atPlace.longitude + dLng * width))
        }
        assertFalse(PresenceCell.near(place.cell, atPlace.latitude + 2 * height, atPlace.longitude))
        assertFalse(PresenceCell.near(place.cell, atPlace.latitude, atPlace.longitude - 2 * width))
        assertFalse(PresenceCell.near(place.cell, atPlace.latitude + 1.0, atPlace.longitude + 1.0))
    }

    @Test
    fun `a missing or malformed place cell is never near, so nothing would be sent`() {
        for (bad in listOf(null, "", "tf2c1", "tf2c1xzz", "TF2C1XZ", "aaaaaaa", "12.97,77")) {
            assertFalse("$bad", PresenceCell.near(bad, atPlace.latitude, atPlace.longitude))
        }
    }

    // ---- the place page ----

    @Test
    fun `at the place - Been here is saved at once, then sent once more with the phone's cell`() = runTest {
        val vm = placeVm()
        advanceUntilIdle()
        vm.onWent()
        runCurrent()
        assertEquals("saved before any position exists", listOf("went:place-1"), discover.visitCalls)
        assertTrue("the sheet does not wait for it", vm.uiState.value.visitSheet)
        assertFalse(vm.uiState.value.visitBusy)
        location.emitFix(FakeLocationProvider.fix(atPlace.latitude, atPlace.longitude, accuracy = 20f))
        advanceUntilIdle()
        assertEquals(listOf("went:place-1", "wentAt:place-1:$hereCell"), discover.visitCalls)
    }

    @Test
    fun `in a cell beside the place the phone sends its own cell, not the place's`() = runTest {
        val vm = placeVm()
        advanceUntilIdle()
        vm.onWent()
        runCurrent()
        location.emitFix(FakeLocationProvider.fix(atPlace.latitude + height, atPlace.longitude, accuracy = 20f))
        advanceUntilIdle()
        val beside = GeoHash.encode(atPlace.latitude + height, atPlace.longitude, GeoHash.BLOCK)
        assertTrue(beside != place.cell)
        assertEquals(listOf("went:place-1", "wentAt:place-1:$beside"), discover.visitCalls)
    }

    @Test
    fun `anywhere else nothing about the person's position is sent`() = runTest {
        val vm = placeVm()
        advanceUntilIdle()
        vm.onWent()
        runCurrent()
        location.emitFix(FakeLocationProvider.fix(atPlace.latitude + 0.5, atPlace.longitude + 0.5, accuracy = 10f)) // home
        advanceUntilIdle()
        assertEquals(listOf("went:place-1"), discover.visitCalls)
    }

    @Test
    fun `a rough position is never used, even at the place`() = runTest {
        val vm = placeVm()
        advanceUntilIdle()
        vm.onWent()
        runCurrent()
        location.emitFix(FakeLocationProvider.fix(atPlace.latitude, atPlace.longitude, accuracy = 400f))
        location.emitFix(FakeLocationProvider.fix(atPlace.latitude, atPlace.longitude, accuracy = null))
        advanceUntilIdle() // the whole window passes
        assertEquals(listOf("went:place-1"), discover.visitCalls)
    }

    @Test
    fun `without precise location already allowed, or with location off, the phone never looks and never asks`() = runTest {
        for (setUp in listOf<(FakeLocationProvider) -> Unit>({ it.access = LocationAccess.NONE },
            { it.access = LocationAccess.APPROXIMATE }, { it.locationEnabled = false })) {
            val provider = FakeLocationProvider().also(setUp)
            val repository = FakeDiscoverRepository()
            repository.placePages = mapOf(null to PlacePage(place, emptyList(), null, null, null))
            val vm = PostCollectionViewModel(repository, provider)
            vm.show(PostCollection.OfPlace("place-1"))
            advanceUntilIdle()
            vm.onWent()
            advanceUntilIdle()
            assertEquals(listOf("went:place-1"), repository.visitCalls)
            assertEquals("no position was requested", 0, provider.subscriptions)
        }
        val source = File("src/main/java/com/safesphere/app/feature/social/ui/PresenceCell.kt").readText(Charsets.UTF_8)
        for (forbidden in listOf("LocationAsk", "registerForActivityResult", "requestPermission", "ActivityResultContracts",
            "DataStore", "SharedPreferences", "openFileOutput", "Log.")) {
            assertFalse("PresenceCell must not use $forbidden", source.contains(forbidden))
        }
    }

    @Test
    fun `nothing arriving in the window means nothing is sent`() = runTest {
        val vm = placeVm()
        advanceUntilIdle()
        vm.onWent()
        advanceUntilIdle()
        assertEquals(listOf("went:place-1"), discover.visitCalls)
        assertEquals(1, location.subscriptions)
    }

    @Test
    fun `taking the visit back or leaving the screen stops the search - a late position sends nothing`() = runTest {
        val vm = placeVm()
        advanceUntilIdle()
        vm.onWent()
        runCurrent()
        vm.onNotWent()
        runCurrent()
        location.emitFix(FakeLocationProvider.fix(atPlace.latitude, atPlace.longitude, accuracy = 10f))
        advanceUntilIdle()
        assertEquals(listOf("went:place-1", "notWent:place-1"), discover.visitCalls)

        discover.visitCalls.clear()
        vm.onWent()
        runCurrent()
        vm.onScreenHidden()
        location.emitFix(FakeLocationProvider.fix(atPlace.latitude, atPlace.longitude, accuracy = 10f))
        advanceUntilIdle()
        assertEquals(listOf("went:place-1"), discover.visitCalls)
    }

    @Test
    fun `the second call is silent - its failure shows nothing and changes nothing`() = runTest {
        val vm = placeVm()
        advanceUntilIdle()
        discover.visitResult = ApiResult.Success(Visit(4))
        vm.onWent()
        runCurrent()
        discover.visitResult = ApiResult.Failure(ApiError.NO_CONNECTION)
        location.emitFix(FakeLocationProvider.fix(atPlace.latitude, atPlace.longitude, accuracy = 10f))
        advanceUntilIdle()
        assertEquals(2, discover.visitCalls.size)
        assertNull(vm.uiState.value.failure)
        assertFalse(vm.uiState.value.sessionExpired)
        assertEquals(Visit(4), vm.uiState.value.visit)
    }

    @Test
    fun `a screen built without a location provider never looks`() = runTest {
        discover.placePages = mapOf(null to PlacePage(place, emptyList(), null, null, null))
        val vm = PostCollectionViewModel(discover)
        vm.show(PostCollection.OfPlace("place-1"))
        advanceUntilIdle()
        vm.onWent()
        advanceUntilIdle()
        assertEquals(listOf("went:place-1"), discover.visitCalls)
    }

    // ---- the post page ----

    private fun postVm(): PostViewModel {
        experiences.postResult = ApiResult.Success(FakeExperienceRepository.post().copy(precision = Precision.EXACT, placeId = "place-1"))
        experiences.placeById = mapOf("place-1" to place)
        return PostViewModel(experiences, discover, "post-1", locationProvider = location)
    }

    @Test
    fun `the post page asks for the place's cell only after a good position, then sends its cell when at the place`() = runTest {
        val vm = postVm()
        advanceUntilIdle()
        assertTrue(vm.onWent())
        runCurrent()
        assertEquals(listOf("went:place-1"), discover.visitCalls)
        assertTrue("no extra request before a position exists", experiences.placeReads.isEmpty())
        location.emitFix(FakeLocationProvider.fix(atPlace.latitude, atPlace.longitude, accuracy = 30f))
        advanceUntilIdle()
        assertEquals(listOf("place-1"), experiences.placeReads)
        assertEquals(listOf("went:place-1", "wentAt:place-1:$hereCell"), discover.visitCalls)
    }

    @Test
    fun `on the post page a place that cannot be read, or a position elsewhere, sends nothing`() = runTest {
        val vm = postVm()
        experiences.placeById = emptyMap() // the place read fails
        advanceUntilIdle()
        vm.onWent()
        runCurrent()
        location.emitFix(FakeLocationProvider.fix(atPlace.latitude, atPlace.longitude, accuracy = 30f))
        advanceUntilIdle()
        assertEquals(listOf("went:place-1"), discover.visitCalls)

        discover.visitCalls.clear()
        experiences.placeById = mapOf("place-1" to place)
        vm.onWent()
        runCurrent()
        location.emitFix(FakeLocationProvider.fix(atPlace.latitude - 0.3, atPlace.longitude, accuracy = 30f))
        advanceUntilIdle()
        assertEquals(listOf("went:place-1"), discover.visitCalls)
    }

    // ---- what is sent ----

    @Test
    fun `the presence request carries one cell and nothing else`() {
        val fields = WentRequestDto::class.java.declaredFields.filterNot { it.isSynthetic }.map { it.name }
        assertEquals(listOf("cell"), fields)
        val api = File("src/main/java/com/safesphere/app/feature/social/data/remote/DiscoverApi.kt").readText(Charsets.UTF_8)
        assertTrue(api.contains("data class WentRequestDto(val cell: String)"))
        assertFalse(Regex("WentRequestDto\\([^)]*(latitude|longitude|accuracy)").containsMatchIn(api))
    }

    // ---- disclosure ----

    private fun draft(type: PostType, disclosed: Boolean) = PostDraft(type, "Body", "", null, null, Precision.NONE, null,
        YearMonth.of(2026, 11), null, Audience.EVERYONE, CommentPolicy.EVERYONE, emptyList(), disclosed = disclosed)

    @Test
    fun `disclosed is sent only when switched on, and never with a question`() {
        assertEquals(true, PostRequestDto.from(draft(PostType.EXPERIENCE, true)).disclosed)
        assertNull("off is not sent at all", PostRequestDto.from(draft(PostType.EXPERIENCE, false)).disclosed)
        assertNull(PostRequestDto.from(draft(PostType.ASK, true)).disclosed)
    }

    @Test
    fun `a post is disclosed only when the server says so, and the card and its spoken words carry the label`() {
        fun dto(disclosed: Boolean?) = PostDto(publicId = "p", type = "EXPERIENCE", body = "Went early.", disclosed = disclosed)
        assertTrue(dto(true).toDomain()!!.disclosed)
        assertFalse(dto(false).toDomain()!!.disclosed)
        assertFalse("an older server", dto(null).toDomain()!!.disclosed)
        assertTrue(ExperienceCards.of(dto(true).toDomain()!!, emptyList()).disclosed)
        assertFalse(ExperienceCards.of(dto(null).toDomain()!!, emptyList()).disclosed)

        val adapter = File("src/main/java/com/safesphere/app/feature/social/ui/ExperienceCardAdapter.kt").readText(Charsets.UTF_8)
        assertTrue(adapter.contains("b.disclosedLine.isVisible = card.disclosed"))
        assertTrue("TalkBack hears it too", adapter.contains("if (card.disclosed) context.getString(R.string.post_disclosed_label)"))
        val post = File("src/main/java/com/safesphere/app/feature/social/ui/PostFragment.kt").readText(Charsets.UTF_8)
        assertTrue(post.contains("binding.disclosedLine.isVisible = post.disclosed"))
    }

    @Test
    fun `the words are plain - what readers see, what happens to the rating, and that location is only checked if already allowed`() {
        val xml = File("src/main/res/values/strings.xml").readText(Charsets.UTF_8)
        val s = Regex("<string name=\"([a-z0-9_]+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL).findAll(xml)
            .associate { it.groupValues[1] to it.groupValues[2] }
        assertEquals("I was paid, or got this free", s["compose_disclosed_switch"])
        assertEquals("Paid or gifted visit", s["post_disclosed_label"])
        val note = s["compose_disclosed_note"]!!
        assertTrue(note.contains("Paid or gifted visit"))
        assertTrue(note.contains("will not count towards its average"))
        assertTrue(note.contains("cannot be switched off"))
        val presence = s["went_presence_note"]!!
        assertTrue(presence.contains("If location is already allowed"))
        assertTrue(presence.contains("Only if you are"))
        assertTrue(presence.contains("Nobody else sees it"))
        assertTrue(s["about_body"]!!.contains("IRL never asks for location for this, keeps only a yes or no"))
        // no promise of proof, no badge words, no claim about the place
        for (text in listOf(note, presence, s["post_disclosed_label"]!!)) {
            assertFalse(Regex("verified|confirmed|trusted|proof|safe\\b|sponsored by|advert", RegexOption.IGNORE_CASE).containsMatchIn(text))
        }
        // the label is words in a neutral colour: never amber (moderation) or red (emergency)
        for (layout in listOf("item_experience_card.xml", "fragment_post.xml")) {
            val block = File("src/main/res/layout/$layout").readText(Charsets.UTF_8).substringAfter("@+id/disclosedLine").substringBefore("/>")
            assertTrue(block.contains("@string/post_disclosed_label"))
            assertTrue(block.contains("?attr/colorOnSurfaceVariant"))
            assertFalse(Regex("amber|error|red|emergency", RegexOption.IGNORE_CASE).containsMatchIn(block))
        }
        val compose = File("src/main/res/layout/fragment_compose.xml").readText(Charsets.UTF_8)
        assertTrue(compose.contains("@+id/disclosedSwitch") && compose.contains("@string/compose_disclosed_note"))
        assertTrue(File("src/main/res/layout/sheet_went.xml").readText(Charsets.UTF_8).contains("@string/went_presence_note"))
    }
}
