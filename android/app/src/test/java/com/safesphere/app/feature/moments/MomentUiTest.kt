package com.safesphere.app.feature.moments

import com.safesphere.app.R
import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.core.network.MEMBERSHIP_NEEDED
import com.safesphere.app.core.retired.OldSocialRetirement
import com.safesphere.app.feature.moments.domain.DailyQuestion
import com.safesphere.app.feature.moments.domain.DayPart
import com.safesphere.app.feature.moments.domain.MomentStatus
import com.safesphere.app.feature.moments.domain.MyMoments
import com.safesphere.app.feature.moments.domain.People
import com.safesphere.app.feature.moments.domain.ProfileMoments
import com.safesphere.app.feature.moments.domain.RoughDay
import com.safesphere.app.feature.moments.domain.RoughTime
import com.safesphere.app.feature.moments.domain.TodayPage
import com.safesphere.app.feature.moments.ui.MomentLabels
import com.safesphere.app.feature.moments.ui.MomentListRow
import com.safesphere.app.feature.moments.ui.MomentViewModel
import com.safesphere.app.feature.moments.ui.ProfileMomentRows
import com.safesphere.app.feature.moments.ui.TodayRow
import com.safesphere.app.feature.moments.ui.TodayUiState
import com.safesphere.app.feature.moments.ui.TodayViewModel
import com.safesphere.app.feature.moments.ui.TodayWall
import com.safesphere.app.feature.social.ui.JournalViewModel
import com.safesphere.app.feature.social.ui.PersonProfileViewModel
import com.safesphere.app.testing.FakeMediaSource
import com.safesphere.app.testing.FakeMomentRepository
import com.safesphere.app.testing.FakeSocialRepository
import com.safesphere.app.testing.MainDispatcherRule
import com.safesphere.app.testing.moment
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Phase N4-A1 on the phone: Today (pages, the end, the two views, a lost cursor, membership, freshness), the wall's
 * rows, the words for a moment, a moment's own page (gone, keep, un-keep, delete), the profile lists, the one-time
 * clean-up of what the old social screens kept, and the source guards (screenshots, no gallery, nothing stored).
 */
class MomentUiTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun page(ids: List<String>, next: String?, question: DailyQuestion? = null) =
        ApiResult.Success(TodayPage(question, ids.map { moment(it) }, next))

    // ---- Today ------------------------------------------------------------------------------------------------------

    @Test
    fun `Today reads its first page, the next one near the end, and then says it has ended`() = runTest {
        val repo = FakeMomentRepository()
        val question = DailyQuestion("0a0a0a0a-0000-4000-8000-00000000000f", "Best chai on campus?")
        repo.pages += page(listOf("a", "b"), next = "b", question = question)
        repo.pages += page(listOf("b", "c"), next = null)
        val vm = TodayViewModel(repo) { 0L }
        vm.start()
        vm.start() // a second start (a rotation) reads nothing again
        advanceUntilIdle()
        assertEquals(listOf(People.ALL to null), repo.todayCalls)
        assertEquals(listOf("a", "b"), vm.uiState.value.moments.map { it.id })
        assertEquals(question, vm.uiState.value.question)
        assertFalse(vm.uiState.value.ended)

        vm.onNearEnd()
        advanceUntilIdle()
        assertEquals(People.ALL to "b", repo.todayCalls.last())
        assertEquals("a moment shown twice is shown once", listOf("a", "b", "c"), vm.uiState.value.moments.map { it.id })
        assertTrue(vm.uiState.value.ended)

        vm.onNearEnd() // the wall has ended: nothing more is asked
        advanceUntilIdle()
        assertEquals(2, repo.todayCalls.size)
    }

    @Test
    fun `switching to My people replaces the wall and reads that view`() = runTest {
        val repo = FakeMomentRepository()
        repo.pages += page(listOf("a"), next = null)
        repo.pages += page(listOf("p"), next = null)
        val vm = TodayViewModel(repo) { 0L }
        vm.start()
        advanceUntilIdle()
        vm.onPeople(People.MINE)
        assertTrue("the old view is not shown under the new name", vm.uiState.value.moments.isEmpty())
        advanceUntilIdle()
        assertEquals(People.MINE to null, repo.todayCalls.last())
        assertEquals(listOf("p"), vm.uiState.value.moments.map { it.id })
        vm.onPeople(People.MINE) // the same view again: nothing happens
        advanceUntilIdle()
        assertEquals(2, repo.todayCalls.size)
    }

    @Test
    fun `a cursor that names a deleted moment starts again from the top, keeping what is shown`() = runTest {
        val repo = FakeMomentRepository()
        repo.pages += page(listOf("a", "b"), next = "b")
        repo.pages += ApiResult.Failure(ApiError.VALIDATION)
        repo.pages += page(listOf("a", "c"), next = null)
        val vm = TodayViewModel(repo) { 0L }
        vm.start()
        advanceUntilIdle()
        vm.onNearEnd()
        advanceUntilIdle()
        assertEquals(listOf(People.ALL to null, People.ALL to "b", People.ALL to null), repo.todayCalls)
        assertEquals(listOf("a", "c"), vm.uiState.value.moments.map { it.id })
        assertNull(vm.uiState.value.failure)
    }

    @Test
    fun `without a membership Today shows Get verified and nothing else`() = runTest {
        val repo = FakeMomentRepository()
        repo.pages += ApiResult.Failure(ApiError.FORBIDDEN, "Members only", MEMBERSHIP_NEEDED)
        val vm = TodayViewModel(repo) { 0L }
        vm.start()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.needsMembership)
        assertNull("not shown as an error", vm.uiState.value.failure)
        assertTrue(TodayWall.rows(vm.uiState.value, emptySet()).isEmpty())
    }

    @Test
    fun `a lost connection keeps what is shown, and coming back reads again only when the wall is old`() = runTest {
        val repo = FakeMomentRepository()
        var now = 0L
        repo.pages += page(listOf("a"), next = null)
        val vm = TodayViewModel(repo) { now }
        vm.start()
        advanceUntilIdle()

        now = TodayViewModel.STALE_MS - 1
        vm.onShown()
        advanceUntilIdle()
        assertEquals(1, repo.todayCalls.size)

        repo.pages += ApiResult.Failure(ApiError.NO_CONNECTION)
        now = TodayViewModel.STALE_MS + 1
        vm.onShown()
        advanceUntilIdle()
        assertEquals(2, repo.todayCalls.size)
        assertEquals(ApiError.NO_CONNECTION, vm.uiState.value.failure!!.error)
        assertEquals("what was read stays on the screen", listOf("a"), vm.uiState.value.moments.map { it.id })
    }

    @Test
    fun `a moment deleted on its page leaves the wall at once`() = runTest {
        val repo = FakeMomentRepository()
        repo.pages += page(listOf("a", "b"), next = null)
        val vm = TodayViewModel(repo) { 0L }
        vm.start()
        advanceUntilIdle()
        vm.onRemoved("a")
        assertEquals(listOf("b"), vm.uiState.value.moments.map { it.id })
    }

    @Test
    fun `the wall is the question, the cards, then exactly one closing row`() {
        val q = DailyQuestion("q", "Q?")
        val cards = listOf(moment("a"), moment("b"))
        assertEquals(listOf(TodayRow.Loading), TodayWall.rows(TodayUiState(loading = true), emptySet()))
        assertEquals(listOf(TodayRow.Question(q), TodayRow.Empty(People.MINE)),
            TodayWall.rows(TodayUiState(people = People.MINE, question = q, loaded = true), emptySet()))
        val ended = TodayWall.rows(TodayUiState(moments = cards, loaded = true, ended = true), setOf("b"))
        assertEquals(listOf(TodayRow.Card(cards[0], false), TodayRow.Card(cards[1], true), TodayRow.End(People.ALL)), ended)
        val more = TodayWall.rows(TodayUiState(moments = cards, loaded = true, loadingMore = true), emptySet())
        assertEquals(TodayRow.Loading, more.last())
        val open = TodayWall.rows(TodayUiState(moments = cards, loaded = true, ended = false), emptySet())
        assertTrue("a wall that goes on has no closing row yet", open.all { it is TodayRow.Card })
    }

    // ---- Words --------------------------------------------------------------------------------------------------

    @Test
    fun `others only ever get a rough time`() {
        val expected = mapOf(
            RoughTime(RoughDay.TODAY, DayPart.MORNING) to R.string.moment_when_today_morning,
            RoughTime(RoughDay.TODAY, DayPart.AFTERNOON) to R.string.moment_when_today_afternoon,
            RoughTime(RoughDay.TODAY, DayPart.EVENING) to R.string.moment_when_today_evening,
            RoughTime(RoughDay.TODAY, DayPart.NIGHT) to R.string.moment_when_today_night,
            RoughTime(RoughDay.TODAY, null) to R.string.moment_when_today,
            RoughTime(RoughDay.YESTERDAY, DayPart.MORNING) to R.string.moment_when_yesterday_morning,
            RoughTime(RoughDay.YESTERDAY, DayPart.AFTERNOON) to R.string.moment_when_yesterday_afternoon,
            RoughTime(RoughDay.YESTERDAY, DayPart.EVENING) to R.string.moment_when_yesterday_evening,
            RoughTime(RoughDay.YESTERDAY, DayPart.NIGHT) to R.string.moment_when_yesterday_night,
            RoughTime(RoughDay.YESTERDAY, null) to R.string.moment_when_yesterday,
            RoughTime(RoughDay.THIS_WEEK, null) to R.string.moment_when_this_week,
            RoughTime(RoughDay.THIS_MONTH, DayPart.MORNING) to R.string.moment_when_this_month,
            RoughTime(RoughDay.EARLIER, null) to R.string.moment_when_earlier)
        for ((time, words) in expected) assertEquals("$time", words, MomentLabels.shared(time))
        val strings = File("src/main/res/values/strings.xml").readText(Charsets.UTF_8)
        for (name in Regex("name=\"(moment_when_[a-z_]+)\"").findAll(strings).map { it.groupValues[1] }) {
            val text = Regex("name=\"$name\">(.*?)</string>").find(strings)!!.groupValues[1]
            assertFalse("$name has no clock time", Regex("\\d").containsMatchIn(text))
        }
    }

    @Test
    fun `a plan's day is the reader's own, across midnight`() {
        val at = Instant.parse("2026-11-17T18:45:00Z")     // 00:15 on the 18th in India
        val now = Instant.parse("2026-11-17T18:00:00Z")    // 23:30 on the 17th in India
        val india = MomentLabels.planTime(at, now, ZoneId.of("Asia/Kolkata"))
        assertEquals(MomentLabels.PlanDay.TOMORROW, india.day)
        assertEquals(LocalTime.of(0, 15), india.time)
        val utc = MomentLabels.planTime(at, now, ZoneOffset.UTC)
        assertEquals(MomentLabels.PlanDay.TODAY, utc.day)
        assertEquals(MomentLabels.PlanDay.OTHER,
            MomentLabels.planTime(Instant.parse("2026-11-19T18:45:00Z"), now, ZoneOffset.UTC).day)
        assertEquals(R.string.moment_plan_tomorrow, MomentLabels.planDay(MomentLabels.PlanDay.TOMORROW))
    }

    // ---- A moment's own page ------------------------------------------------------------------------------------------

    @Test
    fun `a moment that is gone, ended or not for this reader reads the same`() = runTest {
        val repo = FakeMomentRepository()
        val vm = MomentViewModel(repo)
        vm.load("m1")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.gone)
        assertNull(vm.uiState.value.moment)
        assertNull(vm.uiState.value.failure)
    }

    @Test
    fun `keep is offered only on one's own live, visible moment, and a refusal shows the server's words`() = runTest {
        val repo = FakeMomentRepository()
        repo.momentResult = ApiResult.Success(moment("m1", mine = true))
        val vm = MomentViewModel(repo)
        vm.load("m1")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.canKeep)

        repo.keepResult = ApiResult.Failure(ApiError.CONFLICT, "You can keep 6 moments. Un-keep one first.")
        vm.onKeep()
        advanceUntilIdle()
        assertEquals("You can keep 6 moments. Un-keep one first.", vm.uiState.value.failure!!.message)
        assertFalse(vm.uiState.value.moment!!.kept)
        vm.onFailureShown()

        repo.keepResult = null
        vm.onKeep()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.moment!!.kept)
        assertTrue(vm.uiState.value.canUnkeep)
        vm.onUnkeep()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.moment!!.kept)
        assertEquals(listOf("m1"), repo.unkeptIds)

        for (other in listOf(moment("x"), moment("w", mine = true, status = MomentStatus.WAITING),
                moment("e", mine = true, status = MomentStatus.ENDED))) {
            repo.momentResult = ApiResult.Success(other)
            val page = MomentViewModel(repo)
            page.load(other.id)
            advanceUntilIdle()
            assertFalse(other.id, page.uiState.value.canKeep)
        }
    }

    @Test
    fun `deleting closes the page, also when the moment was already gone`() = runTest {
        val repo = FakeMomentRepository()
        repo.momentResult = ApiResult.Success(moment("m1", mine = true))
        val vm = MomentViewModel(repo)
        vm.load("m1")
        advanceUntilIdle()
        repo.deleteResult = ApiResult.Failure(ApiError.NOT_FOUND)
        vm.onDelete()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.removed)
        assertEquals(listOf("m1"), repo.deleted)

        val failing = MomentViewModel(repo)
        failing.load("m1")
        advanceUntilIdle()
        repo.deleteResult = ApiResult.Failure(ApiError.NO_CONNECTION)
        failing.onDelete()
        advanceUntilIdle()
        assertFalse(failing.uiState.value.removed)
        assertEquals(ApiError.NO_CONNECTION, failing.uiState.value.failure!!.error)
    }

    // ---- Profiles -----------------------------------------------------------------------------------------------------

    @Test
    fun `the own profile lists what is on Today and what is kept, with a line when either is empty`() {
        val rows = ProfileMomentRows.own(emptyList(), emptyList())
        assertEquals(listOf(MomentListRow.Section(R.string.you_moments_now), MomentListRow.Note(R.string.you_moments_now_empty),
            MomentListRow.Section(R.string.you_moments_kept), MomentListRow.Note(R.string.you_moments_kept_empty)), rows)
        val live = moment("a", mine = true, kept = true)
        val old = moment("b", mine = true, kept = true, status = MomentStatus.ENDED)
        val both = ProfileMomentRows.own(listOf(live), listOf(live, old))
        assertEquals("a kept live moment is listed once", listOf("a", "b"),
            both.filterIsInstance<MomentListRow.Card>().map { it.moment.id })
        val onlyLive = ProfileMomentRows.own(listOf(live), listOf(live))
        assertEquals(MomentListRow.Note(R.string.you_moments_kept_above), onlyLive.last())
    }

    @Test
    fun `someone else's profile shows only sections that have moments`() {
        assertEquals(listOf(MomentListRow.Note(R.string.profile_moments_empty)),
            ProfileMomentRows.of(emptyList(), emptyList(), emptySet()))
        val rows = ProfileMomentRows.of(emptyList(), listOf(moment("k", collapsed = true)), setOf("k"))
        assertEquals(listOf(MomentListRow.Section(R.string.profile_moments_kept), MomentListRow.Card(moment("k",
            collapsed = true), true)), rows)
    }

    @Test
    fun `You reads the person's own moments, and a person's profile reads theirs`() = runTest {
        val moments = FakeMomentRepository()
        moments.mineResult = ApiResult.Success(MyMoments(listOf(moment("a", mine = true)), emptyList(), 6))
        val you = JournalViewModel(FakeSocialRepository(), FakeMediaSource(), moments)
        you.load()
        advanceUntilIdle()
        assertTrue(you.uiState.value.momentsLoaded)
        assertEquals(listOf("a"), you.uiState.value.current.map { it.id })

        moments.mineResult = ApiResult.Failure(ApiError.FORBIDDEN, null, MEMBERSHIP_NEEDED)
        val outsider = JournalViewModel(FakeSocialRepository(), FakeMediaSource(), moments)
        outsider.load()
        advanceUntilIdle()
        assertFalse("not a member: no moments, and not an error", outsider.uiState.value.momentsLoaded)
        assertNull(outsider.uiState.value.failure)

        moments.profileResult = ApiResult.Success(ProfileMoments(listOf(moment("l")), listOf(moment("k"))))
        val person = PersonProfileViewModel(FakeSocialRepository(), moments, handle = null, profileId = "bala-id")
        advanceUntilIdle()
        assertEquals(listOf("bala-id"), moments.profileReads)
        assertEquals(listOf("l"), person.uiState.value.live.map { it.id })
        assertEquals(listOf("k"), person.uiState.value.kept.map { it.id })
    }

    // ---- What the old screens kept on this phone ---------------------------------------------------------------------

    @Test
    fun `the old places file, the Home city and video work files are deleted once, then the key`() {
        val files = Files.createTempDirectory("files").toFile()
        val cache = Files.createTempDirectory("cache").toFile()
        File(files, "places").mkdirs()
        File(files, "places/places-7.bin").writeText("sealed")
        File(files, "datastore").mkdirs()
        File(files, "datastore/viewer_preferences.preferences_pb").writeText("x")
        File(files, "datastore/session.preferences_pb").writeText("keep me")
        File(cache, "video-upload").mkdirs()
        File(cache, "video-upload/a.mp4").writeText("v")
        File(files, "rise-retired-notice").writeText("1")
        var keyForgotten = 0
        val retirement = OldSocialRetirement(files, cache) { keyForgotten++ }

        assertTrue(retirement.retire())
        assertFalse(File(files, "places").exists())
        assertFalse(File(files, "datastore/viewer_preferences.preferences_pb").exists())
        assertFalse(File(cache, "video-upload").exists())
        assertTrue("the session is never touched", File(files, "datastore/session.preferences_pb").exists())
        assertTrue("other features' files stay", File(files, "rise-retired-notice").exists())
        assertEquals(1, keyForgotten)
        assertTrue("a second start has nothing to do", retirement.retire())
        files.deleteRecursively()
        cache.deleteRecursively()
    }

    // ---- Source guards --------------------------------------------------------------------------------------------------

    private fun source(path: String) = File("src/main/java/com/safesphere/app/$path").readText(Charsets.UTF_8)

    @Test
    fun `screenshots are blocked on Today, a moment and other people's profiles, never on your own You`() {
        assertTrue(source("feature/moments/ui/TodayFragment.kt").contains("secure(true)"))
        assertTrue(source("feature/moments/ui/MomentFragment.kt").contains("secure(true)"))
        val person = source("feature/social/ui/PersonProfileFragment.kt")
        assertTrue(person.contains("secure(viewModel.uiState.value.person?.relationship != Relationship.SELF)"))
        assertTrue(person.contains("secure(false)"))
        assertFalse(source("feature/profile/ui/YouFragment.kt").contains("FLAG_SECURE"))
        assertFalse(source("feature/profile/ui/YouFragment.kt").contains("secure("))
    }

    @Test
    fun `nothing about moments is stored on the phone and no gallery is opened`() {
        val moments = File("src/main/java/com/safesphere/app/feature/moments").walkTopDown().filter { it.extension == "kt" }
            .joinToString("\n") { it.readText(Charsets.UTF_8) }
        for (writer in listOf("DataStore", "SharedPreferences", "FileOutputStream", "openFileOutput", "filesDir",
                "MediaStore", "PickVisualMedia", "ACTION_GET_CONTENT", "ACTION_PICK", "diskCache")) {
            assertFalse("moments use $writer", moments.contains(writer))
        }
        val layouts = listOf("fragment_today.xml", "fragment_moment.xml", "item_moment_card.xml")
            .joinToString("\n") { File("src/main/res/layout/$it").readText(Charsets.UTF_8) }
        for (word in listOf("count", "views", "seen", "distance", "nearby")) {
            assertFalse("a moment layout names $word", Regex("id=\"@\\+id/[a-zA-Z]*${word}", RegexOption.IGNORE_CASE)
                .containsMatchIn(layouts))
        }
    }

    @Test
    fun `the old social screens are gone for good`() {
        val graph = File("src/main/res/navigation/nav_graph.xml").readText(Charsets.UTF_8)
        for (gone in listOf("discoverFragment", "composeFragment", "postFragment", "placePickerFragment",
                "privatePlacesFragment", "postCollectionFragment", "videoPlayerFragment", "myPostsFragment")) {
            assertFalse(gone, graph.contains(gone))
        }
        assertFalse(File("src/main/resources/cities/cities-v1.tsv").exists())
        val all = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        for (gone in listOf("api/posts", "api/places", "api/discover", "\"api/media")) {
            assertFalse("the app still calls $gone", all.contains(gone))
        }
    }
}
