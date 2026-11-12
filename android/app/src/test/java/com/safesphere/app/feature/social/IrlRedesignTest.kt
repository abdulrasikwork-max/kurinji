package com.safesphere.app.feature.social

import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.social.data.PlaceMemory
import com.safesphere.app.feature.social.domain.Audience
import com.safesphere.app.feature.social.domain.Cities
import com.safesphere.app.feature.social.domain.CommentKind
import com.safesphere.app.feature.social.domain.DiscoverItem
import com.safesphere.app.feature.social.domain.DiscoverPage
import com.safesphere.app.feature.social.domain.FeedFreshness
import com.safesphere.app.feature.social.domain.MediaKind
import com.safesphere.app.feature.social.domain.PostType
import com.safesphere.app.feature.social.domain.Precision
import com.safesphere.app.feature.social.ui.ComposeViewModel
import com.safesphere.app.feature.social.ui.DiscoverViewModel
import com.safesphere.app.feature.social.ui.PostViewModel
import com.safesphere.app.navigation.NavigationRules
import com.safesphere.app.R
import com.safesphere.app.testing.FakeDiscoverRepository
import com.safesphere.app.testing.FakeExperienceRepository
import com.safesphere.app.testing.FakeLocationProvider
import com.safesphere.app.testing.FakeMediaSource
import com.safesphere.app.testing.FakePlacesStore
import com.safesphere.app.testing.FakeSocialRepository
import com.safesphere.app.testing.MainDispatcherRule
import com.safesphere.app.testing.TestPick
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * IRL redesign (2026-11-11, docs/IRL_REDESIGN.md): the feed reads again, a failed mark does not break it, the composer
 * needs no place decision and guards its draft, a comment is just a comment, and the bar is Home · Explore · Share · You.
 */
class IrlRedesignTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val experiences = FakeExperienceRepository()
    private val discover = FakeDiscoverRepository()
    private val social = FakeSocialRepository()
    private val memory = PlaceMemory(FakePlacesStore(), { 7L }, Dispatchers.Unconfined)
    private val clock = Clock.fixed(Instant.parse("2026-11-11T10:00:00Z"), ZoneOffset.UTC)

    private fun item(id: String) = DiscoverItem(FakeExperienceRepository.post(id = id), emptyList())

    // ---- R1: the feed reads again ------------------------------------------------------------------------------

    @Test
    fun `a refresh keeps what is on the screen until the new posts arrive, then replaces them`() = runTest {
        discover.connectionPages = mapOf(null to DiscoverPage(listOf(item("old")), null))
        val vm = DiscoverViewModel(discover, social, experiences, FakeLocationProvider())
        vm.start()
        advanceUntilIdle()
        discover.connectionPages = mapOf(null to DiscoverPage(listOf(item("new"), item("old")), null))
        vm.refresh()
        assertEquals("still shown while reading", listOf("old"), vm.uiState.value.connections.map { it.post.publicId })
        advanceUntilIdle()
        assertEquals(listOf("new", "old"), vm.uiState.value.connections.map { it.post.publicId })
        assertEquals("the first page both times", listOf<String?>(null, null), discover.connectionCursors)
    }

    @Test
    fun `coming back reads again only after the person's posts changed or the feed got old`() = runTest {
        val freshness = FeedFreshness()
        var now = 1_000L
        val vm = DiscoverViewModel(discover, social, experiences, FakeLocationProvider(), freshness) { now }
        vm.onShown()
        assertTrue("nothing before the first load", discover.connectionCursors.isEmpty())
        vm.start()
        advanceUntilIdle()
        vm.onShown()
        advanceUntilIdle()
        assertEquals("fresh: no second read", 1, discover.connectionCursors.size)
        freshness.changed() // a post was shared or deleted
        vm.onShown()
        advanceUntilIdle()
        assertEquals(2, discover.connectionCursors.size)
        vm.onShown()
        advanceUntilIdle()
        assertEquals("once per change", 2, discover.connectionCursors.size)
        now += DiscoverViewModel.STALE_MS + 1
        vm.onShown()
        advanceUntilIdle()
        assertEquals("old feed", 3, discover.connectionCursors.size)
    }

    @Test
    fun `a mark that did not go through is said once and never becomes a feed failure`() = runTest {
        val post = FakeExperienceRepository.post(id = "s1")
        discover.connectionPages = mapOf(null to DiscoverPage(listOf(DiscoverItem(post, emptyList())), null))
        val vm = DiscoverViewModel(discover, social, experiences, FakeLocationProvider())
        vm.start()
        advanceUntilIdle()
        discover.markResult = ApiResult.Failure(ApiError.NO_CONNECTION)
        vm.onReact(post)
        advanceUntilIdle()
        assertFalse("put back", vm.uiState.value.connections.single().post.youReacted)
        assertEquals(ApiError.NO_CONNECTION, vm.uiState.value.markFailure!!.error)
        assertNull("the feed itself did not fail", vm.uiState.value.failure)
        vm.onMarkFailureShown()
        assertNull(vm.uiState.value.markFailure)
    }

    // ---- R2: the short composer --------------------------------------------------------------------------------

    @Test
    fun `a post needs no place decision - it starts in the profile's city, or with no place`() = runTest {
        val chennai = Cities.find("CHENNAI")!!
        val vm = ComposeViewModel(experiences, memory, FakeMediaSource(), clock, FeedFreshness()) { chennai }
        advanceUntilIdle()
        assertEquals(Precision.CITY, vm.uiState.value.precision)
        assertEquals(chennai, vm.uiState.value.city)
        vm.onBody("Good chai.")
        vm.onAudience(Audience.EVERYONE)
        vm.onShare()
        advanceUntilIdle()
        val draft = experiences.drafts.single()
        assertEquals(Precision.CITY, draft.precision)
        assertEquals(chennai, draft.city)

        val unknown = ComposeViewModel(experiences, memory, FakeMediaSource(), clock)
        advanceUntilIdle()
        assertEquals(Precision.NONE, unknown.uiState.value.precision)
        assertTrue("nothing about the place stops sharing", unknown.uiState.value.issues.none { it.name.contains("PLACE") ||
            it.name.contains("PRECISION") || it.name.contains("CITY") })
    }

    @Test
    fun `the starting city never replaces a place the person chose`() = runTest {
        val vm = ComposeViewModel(experiences, memory, FakeMediaSource(), clock, FeedFreshness()) { Cities.find("CHENNAI") }
        vm.onNoPlace() // before the profile answered
        advanceUntilIdle()
        assertEquals(Precision.NONE, vm.uiState.value.precision)
        assertNull(vm.uiState.value.city)
    }

    @Test
    fun `a draft with words or photos is guarded, discarding removes its uploads, sharing tells the feed`() = runTest {
        val freshness = FeedFreshness()
        val vm = ComposeViewModel(experiences, memory, FakeMediaSource(), clock, freshness)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.hasDraft)
        vm.onMediaPicked(listOf(TestPick(MediaKind.PHOTO)))
        advanceUntilIdle()
        assertTrue(vm.uiState.value.hasDraft)
        val uploaded = vm.uiState.value.media.single().mediaId!!
        vm.onDiscard()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.media.isEmpty())
        assertEquals(listOf(uploaded), experiences.deletedUploads)

        vm.onBody("Went early.")
        assertTrue(vm.uiState.value.hasDraft)
        vm.onAudience(Audience.ONLY_ME)
        vm.onShare()
        advanceUntilIdle()
        assertEquals(1, freshness.version)
    }

    // ---- R1: a comment is just a comment -----------------------------------------------------------------------

    @Test
    fun `a comment gets its kind from its words, never from a chooser`() = runTest {
        experiences.postResult = ApiResult.Success(FakeExperienceRepository.post(type = PostType.EXPERIENCE))
        val vm = PostViewModel(experiences, discover, "post-1")
        advanceUntilIdle()
        vm.onText("Looks great!")
        vm.onSend()
        advanceUntilIdle()
        assertEquals(CommentKind.BEEN_HERE, experiences.sentPerspectives.last().first)
        vm.onText("Is it open on Sundays?")
        vm.onSend()
        advanceUntilIdle()
        assertEquals(CommentKind.QUESTION, experiences.sentPerspectives.last().first)

        experiences.postResult = ApiResult.Success(FakeExperienceRepository.post(type = PostType.ASK))
        val ask = PostViewModel(experiences, discover, "post-1")
        advanceUntilIdle()
        ask.onText("Take the 21G bus.")
        ask.onSend()
        advanceUntilIdle()
        assertEquals(CommentKind.ANSWER, experiences.sentPerspectives.last().first)
    }

    @Test
    fun `the post screen has no kind chooser and prints no kind but Answer`() {
        val layout = File("src/main/res/layout/fragment_post.xml").readText(Charsets.UTF_8)
        assertFalse(layout.contains("kindGroup"))
        val screen = File("src/main/java/com/safesphere/app/feature/social/ui/PostFragment.kt").readText(Charsets.UTF_8)
        assertFalse(screen.contains("onKind"))
        assertTrue(screen.contains("p.kind == CommentKind.ANSWER"))
    }

    // ---- R3, R4: the bar and the You tab -----------------------------------------------------------------------

    @Test
    fun `Explore is a tab, Saved is a reading screen under You, and re-tapping a tab reaches its screen`() {
        assertTrue(R.id.exploreFragment in NavigationRules.tabDestinations)
        assertFalse(R.id.cocoonFragment in NavigationRules.tabDestinations)
        assertTrue(R.id.cocoonFragment in NavigationRules.bottomBarDestinations)
        assertFalse("Settings and more is a task screen", R.id.moreFragment in NavigationRules.bottomBarDestinations)
        val rules = File("src/main/java/com/safesphere/app/navigation/NavigationRules.kt").readText(Charsets.UTF_8)
        assertTrue(rules.contains("setOnItemReselectedListener { onReselect() }"))
        val home = File("src/main/res/layout/fragment_discover.xml").readText(Charsets.UTF_8)
        assertFalse("one way into Explore: its tab", home.contains("exploreButton"))
    }

    @Test
    fun `You is a profile - the safety tools and settings are one row each, and nothing was dropped`() {
        val you = File("src/main/res/layout/fragment_you.xml").readText(Charsets.UTF_8)
        for (gone in listOf("tileSos", "logoutButton", "strongLanguageSwitch", "accountList")) {
            assertFalse("$gone belongs to Settings and more", you.contains(gone))
        }
        val youCode = File("src/main/java/com/safesphere/app/feature/profile/ui/YouFragment.kt").readText(Charsets.UTF_8)
        for (door in listOf("R.id.myPostsFragment", "R.id.cocoonFragment", "R.id.connectionsFragment", "R.id.safetyFragment",
                "R.id.moreFragment")) assertTrue(door, youCode.contains(door))
        val more = File("src/main/java/com/safesphere/app/feature/profile/ui/MoreFragment.kt").readText(Charsets.UTF_8)
        for (row in listOf("R.id.privatePlacesFragment", "R.id.guidelinesFragment", "R.id.myReportsFragment",
                "R.id.blockedMutedFragment", "R.id.moderationFragment", "R.id.accountFragment", "R.id.privacyPolicyFragment",
                "R.id.settingsFragment", "R.id.aboutFragment", "onLogoutConfirmed", "setCollapseStrongLanguage")) {
            assertTrue(row, more.contains(row))
        }
    }

    @Test
    fun `the composer shows photos first and keeps the optional parts closed`() {
        val layout = File("src/main/res/layout/fragment_compose.xml").readText(Charsets.UTF_8)
        assertTrue(layout.indexOf("addPhotosButton") < layout.indexOf("bodyInput"))
        assertTrue(layout.indexOf("bodyInput") < layout.indexOf("audienceGroup"))
        val details = layout.substringAfter("android:id=\"@+id/detailsBlock\"")
        assertTrue("closed until asked for", details.substringBefore(">").contains("android:visibility=\"gone\""))
        for (optional in listOf("tipInput", "monthSpinner", "daypartGroup", "facetGroup", "categoryGroup", "commentGroup")) {
            assertTrue("$optional is a detail", details.contains(optional))
        }
        val screen = File("src/main/java/com/safesphere/app/feature/social/ui/ComposeFragment.kt").readText(Charsets.UTF_8)
        assertTrue(screen.contains("confirmLeave.isEnabled = state.hasDraft"))
    }

    // ---- R6: the Home city is remembered per account, one empty message, a private note is not "shared" ----------

    private class Prefs : com.safesphere.app.feature.social.domain.ViewerPreferences {
        val city = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
        override val collapseStrongLanguage = kotlinx.coroutines.flow.MutableStateFlow(false)
        override suspend fun setCollapseStrongLanguage(on: Boolean) = Unit
        override val smartUnderstandingAccount = kotlinx.coroutines.flow.MutableStateFlow<Long?>(null)
        override suspend fun setSmartUnderstandingAccount(userId: Long?) = Unit
        override val homeCity = city
        override suspend fun setHomeCity(value: String?) {
            city.value = value
        }
    }

    @Test
    fun `the city chosen on Home is remembered for that account only, and an unknown code is ignored`() = runTest {
        val prefs = Prefs()
        var me: Long? = 7L
        val choice = com.safesphere.app.feature.social.domain.HomeCityChoice(prefs) { me }
        assertNull(choice.get())
        choice.set(Cities.find("MADURAI")!!)
        assertEquals("7:MADURAI", prefs.city.value)
        assertEquals("MADURAI", choice.get()?.code)
        me = 8L
        assertNull("another account on this phone starts without it", choice.get())
        me = null
        assertNull(choice.get())
        choice.set(Cities.find("CHENNAI")!!)
        assertEquals("signed out: nothing is written", "7:MADURAI", prefs.city.value)
        me = 7L
        prefs.city.value = "7:NOWHERE"
        assertNull(choice.get())
        prefs.city.value = "MADURAI"
        assertNull("no account in front", choice.get())
    }

    @Test
    fun `Home opens in the remembered city without asking, and a new choice replaces it`() = runTest {
        val prefs = Prefs()
        val choice = com.safesphere.app.feature.social.domain.HomeCityChoice(prefs) { 7L }
        social.ownProfileResult = ApiResult.Success(null) // the profile names no catalog city
        val first = DiscoverViewModel(discover, social, experiences, FakeLocationProvider(), homeCity = choice)
        first.start()
        advanceUntilIdle()
        assertTrue("nothing remembered yet: Home asks", first.uiState.value.needsCity)
        first.onCity(Cities.find("MADURAI")!!)
        advanceUntilIdle()
        assertEquals("7:MADURAI", prefs.city.value)

        val next = DiscoverViewModel(discover, social, experiences, FakeLocationProvider(), homeCity = choice)
        next.start()
        advanceUntilIdle()
        assertEquals("MADURAI", next.uiState.value.city?.code)
        assertFalse(next.uiState.value.needsCity)
        next.onCity(Cities.find("CHENNAI")!!)
        advanceUntilIdle()
        assertEquals("7:CHENNAI", prefs.city.value)
    }

    @Test
    fun `a note for yourself is kept, not shared, and only the own city code is ever stored`() {
        val screen = File("src/main/java/com/safesphere/app/feature/social/ui/ComposeFragment.kt").readText(Charsets.UTF_8)
        assertTrue(screen.contains("if (it == Audience.ONLY_ME) getString(R.string.social_share_only_me)"))
        val strings = File("src/main/res/values/strings.xml").readText(Charsets.UTF_8)
        assertFalse(strings.substringAfter("name=\"social_share_only_me\">").substringBefore("<").contains("Share"))
        val choice = File("src/main/java/com/safesphere/app/feature/social/domain/HomeCityChoice.kt").readText(Charsets.UTF_8)
        assertTrue("a code from the bundled list, never free text", choice.contains("Cities.listCity(code)"))
        val viewModel = File("src/main/java/com/safesphere/app/feature/social/ui/DiscoverViewModel.kt").readText(Charsets.UTF_8)
        assertFalse(viewModel.contains("DataStore"))
    }

    // ---- R7: found with a second account on the A23 (2026-11-12) ---------------------------------------------------

    @Test
    fun `a commenter's photo survives reading the comment, and a made-up key does not`() {
        val key = "c6818521-39f2-45b1-9aba-a237b8cf8093"
        fun comment(photo: String?) = com.safesphere.app.feature.social.data.remote.CommentDto(publicId = "c", kind = "QUESTION",
            author = com.safesphere.app.feature.social.data.remote.PersonRefDto("a", "h", "A", photo), body = "b").toDomain()
        assertEquals(key, comment(key)!!.author!!.photo)
        assertNull(comment("../../etc")!!.author!!.photo)
        assertNull(comment(null)!!.author!!.photo)
    }

    @Test
    fun `a mark or a comment made on the post page makes Home read again`() = runTest {
        val freshness = FeedFreshness()
        val vm = PostViewModel(experiences, discover, "post-1", freshness)
        advanceUntilIdle()
        val before = freshness.version
        vm.onReact()
        advanceUntilIdle()
        assertTrue("the butterfly", freshness.version > before)
        val afterMark = freshness.version
        vm.onText("Is the parking open that early?")
        vm.onSend()
        advanceUntilIdle()
        assertTrue("the comment", freshness.version > afterMark)
    }

    @Test
    fun `a question shows no I went or Going there, and the bar leaves when the keyboard opens`() {
        val post = File("src/main/java/com/safesphere/app/feature/social/ui/PostFragment.kt").readText(Charsets.UTF_8)
        assertTrue(post.contains("binding.goingRow.isVisible = post.type == com.safesphere.app.feature.social.domain.PostType.EXPERIENCE"))
        val activity = File("src/main/java/com/safesphere/app/MainActivity.kt").readText(Charsets.UTF_8)
        assertEquals("both places that show the bar ask the keyboard", 2,
            activity.split("binding.bottomNavigation.isVisible = barWanted && !keyboardOpen").size - 1)
    }

    // ---- R14: "I went" only where a visit can be marked (the owner's tap on a city-level post, 2026-11-12) ----------

    @Test
    fun `the button says I went only on an exact place, and Share yours where it opens the composer`() {
        val post = File("src/main/java/com/safesphere/app/feature/social/ui/PostFragment.kt").readText(Charsets.UTF_8)
        assertTrue(post.contains("val marksVisit = post.precision == Precision.EXACT && post.placeId != null"))
        assertTrue(post.contains("if (marksVisit) R.string.went_label else R.string.feed_share_yours"))
        assertTrue(post.contains("if (marksVisit) R.string.went_description else R.string.went_share_description"))
        // Saved's button always opens the composer, so it never says "I went".
        val saved = File("src/main/res/layout/item_saved.xml").readText(Charsets.UTF_8)
        assertFalse(saved.contains("@string/went_label"))
        assertTrue(saved.contains("@string/feed_share_yours"))
        val strings = File("src/main/res/values/strings.xml").readText(Charsets.UTF_8)
        assertTrue(strings.contains("<string name=\"went_share_description\">Share yours: write your own experience. " +
            "This post names no exact place, so there is no visit to mark or rate.</string>"))
    }
}
