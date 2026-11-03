package com.safesphere.app.feature.social

import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.social.data.remote.ExploreHitDto
import com.safesphere.app.feature.social.data.remote.ExplorePlaceDto
import com.safesphere.app.feature.social.data.remote.ExploreRequestDto
import com.safesphere.app.feature.social.data.remote.ExploreResponseDto
import com.safesphere.app.feature.social.data.remote.PostDto
import com.safesphere.app.feature.social.data.remote.PostRequestDto
import com.safesphere.app.feature.social.domain.Audience
import com.safesphere.app.feature.social.domain.Cities
import com.safesphere.app.feature.social.domain.City
import com.safesphere.app.feature.social.domain.CommentPolicy
import com.safesphere.app.feature.social.domain.Daypart
import com.safesphere.app.feature.social.domain.ExploreFacets
import com.safesphere.app.feature.social.domain.ExploreHit
import com.safesphere.app.feature.social.domain.ExploreLexicon
import com.safesphere.app.feature.social.domain.ExploreQuery
import com.safesphere.app.feature.social.domain.ExploreQueryParser
import com.safesphere.app.feature.social.domain.ExploreResult
import com.safesphere.app.feature.social.domain.Interest
import com.safesphere.app.feature.social.domain.PostDraft
import com.safesphere.app.feature.social.domain.PostType
import com.safesphere.app.feature.social.domain.Precision
import com.safesphere.app.feature.social.domain.TermKind
import com.safesphere.app.feature.social.ui.ExplorePhase
import com.safesphere.app.feature.social.ui.ExploreViewModel
import com.safesphere.app.testing.FakeExploreRepository
import com.safesphere.app.testing.MainDispatcherRule
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Phase 17G-1: Explore on the phone. The word list and the parser understand a typed request ON THE PHONE (English, Tamil,
 * Tanglish) and only ever produce a structured query of closed lists; the DTOs fail closed; the ViewModel sends only that
 * query; and the code keeps location, storage, network and any AI service out of the understanding.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Phase17GExploreTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val chennai: City = Cities.find("CHENNAI")!!
    private val parser = ExploreQueryParser()

    private fun understand(text: String) = parser.parse(text, chennai)

    // ---- the tag list ----

    @Test
    fun `the app has the thirteen tags and cleans a list of codes`() {
        assertEquals(13, ExploreFacets.all.size)
        assertTrue(ExploreFacets.isValid("QUIET"))
        assertFalse(ExploreFacets.isValid("SAFE_FOR_WOMEN"))
        assertEquals(listOf("QUIET", "SCENIC"), ExploreFacets.cleaned(listOf("SCENIC", "NOPE", "QUIET", "QUIET")))
        assertEquals(emptyList<String>(), ExploreFacets.cleaned(null))
    }

    @Test
    fun `the app's tag file is byte-identical to the server's`() {
        val app = File("src/main/resources/explore/explore-facets-v1.tsv").readBytes()
        val server = File("../../backend/src/main/resources/explore/explore-facets-v1.tsv").readBytes()
        assertTrue("explore-facets-v1.tsv differs between the app and the backend", app.contentEquals(server))
    }

    // ---- the word list ----

    @Test
    fun `the word list file has no problem - no unknown code, no word with two meanings, no repeat`() {
        val parsed = ExploreLexicon.parse(File("src/main/resources/explore/explore-lexicon-v1.tsv").readText(Charsets.UTF_8))
        assertEquals(emptyList<String>(), parsed.problems)
        assertTrue("a real word list", parsed.terms.size >= 400)
        assertTrue(parsed.terms.values.any { it.kind == TermKind.NEGATION })
        assertTrue(parsed.terms.values.any { it.kind == TermKind.STOP })
    }

    @Test
    fun `the word list has English, Tamil script and Tanglish for tags, categories and times of day`() {
        val terms = ExploreLexicon.terms
        assertEquals(TermKind.FACET, terms["quiet"]?.kind)
        assertEquals(TermKind.FACET, terms["amaithiyana"]?.kind)       // Tanglish (from the tag file)
        assertEquals(TermKind.FACET, terms["அமைதியான"]?.kind)             // Tamil script (from the tag file)
        assertEquals("FOOD_CAFES", terms["kaapi"]?.code)
        assertEquals("FOOD_CAFES", terms["காபி"]?.code)
        assertEquals("EVENING", terms["maalai"]?.code)
        assertEquals("EVENING", terms["மாலை"]?.code)
        assertEquals("NIGHT", terms["night"]?.code)
        assertEquals("OPEN_LATE", terms["late night"]?.code)
    }

    @Test
    fun `every tag name in English, Tamil and Tanglish means its own tag`() {
        for (facet in ExploreFacets.all) {
            for (name in listOf(facet.english, facet.tamil, facet.tanglish)) {
                val term = ExploreLexicon.terms[ExploreLexicon.normalize(name)]
                assertNotNull("${facet.code}: \"$name\" is not in the word list", term)
                assertEquals(facet.code, term!!.code)
            }
        }
    }

    @Test
    fun `no word in the list is about safety, lighting, women or danger`() {
        val forbidden = Regex("\\b(safe|safety|unsafe|danger|dangerous|women|woman|ladies|lit|lighting|harass\\w*)\\b")
        ExploreLexicon.terms.filter { it.value.kind != TermKind.STOP }.forEach { (phrase, term) ->
            assertFalse("\"$phrase\" (${term.code}) must not be about safety", forbidden.containsMatchIn(phrase))
        }
    }

    @Test
    fun `normalising keeps letters, digits and the signs of Tamil words, and drops the rest`() {
        assertEquals("quiet cafe", ExploreLexicon.normalize("  Quiet, CAFE!! "))
        assertEquals("அமைதியான காபி", ExploreLexicon.normalize("அமைதியான, காபி!"))
        assertEquals("24 7 open", ExploreLexicon.normalize("24/7 open"))
        assertEquals("", ExploreLexicon.normalize("?!  —"))
    }

    // ---- understanding a typed request ----

    @Test
    fun `English - a quiet cafe to study in the evening`() {
        val u = understand("a quiet cafe to study in the evening")
        assertEquals(listOf(Interest.FOOD_CAFES, Interest.STUDY_WORK), u.query.categories)
        assertEquals(listOf("QUIET"), u.query.facets)
        assertEquals(Daypart.EVENING, u.query.daypart)
        assertEquals(chennai, u.query.city)
        assertEquals(emptyList<String>(), u.unused)
        assertEquals(3, u.query.requested) // the two categories are ONE requirement, plus the tag, plus the time of day
    }

    @Test
    fun `a request counts the categories as ONE thing, the tags and the time of day one each`() {
        val u = understand("quiet cafe to study in the evening")
        assertEquals(3, u.query.requested) // categories (1) + QUIET (1) + EVENING (1)
        assertTrue(u.query.canSearch)
    }

    @Test
    fun `English with a city - cheap vegetarian food in Trichy`() {
        val u = understand("cheap vegetarian food in trichy")
        assertEquals(Cities.find("TIRUCHIRAPPALLI"), u.query.city)
        assertEquals(listOf(Interest.FOOD_CAFES), u.query.categories)
        assertEquals(listOf("BUDGET_FRIENDLY", "VEGETARIAN_OPTIONS"), u.query.facets)
        assertEquals(emptyList<String>(), u.unused)
    }

    @Test
    fun `Tamil script`() {
        val u = understand("அமைதியான காபி மாலை")
        assertEquals(listOf(Interest.FOOD_CAFES), u.query.categories)
        assertEquals(listOf("QUIET"), u.query.facets)
        assertEquals(Daypart.EVENING, u.query.daypart)
        assertEquals(emptyList<String>(), u.unused)
    }

    @Test
    fun `Tanglish`() {
        val u = understand("amaithiyana kaapi maalai")
        assertEquals(listOf(Interest.FOOD_CAFES), u.query.categories)
        assertEquals(listOf("QUIET"), u.query.facets)
        assertEquals(Daypart.EVENING, u.query.daypart)
    }

    @Test
    fun `English mixed with Tanglish and Tamil in one sentence`() {
        val u = understand("kovil near beach evening அமைதியான")
        assertEquals(listOf(Interest.CULTURE_HERITAGE, Interest.NATURE_OUTDOORS), u.query.categories)
        assertEquals(listOf("QUIET"), u.query.facets)
        assertEquals(Daypart.EVENING, u.query.daypart)
    }

    @Test
    fun `the longest phrase wins - late night is a tag, night alone is a time of day`() {
        assertEquals(listOf("OPEN_LATE"), understand("late night snacks").query.facets)
        assertNull(understand("late night snacks").query.daypart)
        assertEquals(Daypart.NIGHT, understand("music at night").query.daypart)
        assertTrue(understand("music at night").query.facets.isEmpty())
    }

    @Test
    fun `words the list does not know are shown as unused, never guessed`() {
        val u = understand("quiet cafe xyzzy plugh")
        assertEquals(listOf("xyzzy", "plugh"), u.unused)
        assertEquals(listOf("QUIET"), u.query.facets)
        // A ranking word is not understood either, and the person is told.
        assertEquals(listOf("best"), understand("best cafes").unused)
    }

    @Test
    fun `negation is not understood - the next word is NOT applied and is shown as unused`() {
        val u = understand("cafe not quiet")
        assertEquals(listOf(Interest.FOOD_CAFES), u.query.categories)
        assertTrue(u.query.facets.isEmpty())
        assertEquals(listOf("not quiet"), u.unused)
        assertEquals(listOf("not quiet"), understand("cafe without quiet").unused)
    }

    @Test
    fun `the limits hold - two categories, four tags, one time of day, one city`() {
        val categories = understand("food nature temple")
        assertEquals(listOf(Interest.FOOD_CAFES, Interest.NATURE_OUTDOORS), categories.query.categories)
        assertEquals(listOf("temple"), categories.unused)
        val tags = understand("quiet lively cheap friends alone family")
        assertEquals(listOf("QUIET", "LIVELY", "BUDGET_FRIENDLY", "GOOD_WITH_FRIENDS"), tags.query.facets)
        assertEquals(listOf("alone", "family"), tags.unused)
        val times = understand("morning evening")
        assertEquals(Daypart.MORNING, times.query.daypart)
        assertEquals(listOf("evening"), times.unused)
        val cities = understand("cafe madurai coimbatore")
        assertEquals(Cities.find("MADURAI"), cities.query.city)
        assertEquals(listOf("coimbatore"), cities.unused)
    }

    @Test
    fun `nothing typed means nothing asked, the default city stays, and a city alone is not a request`() {
        for (text in listOf("", "   ", "?!", "in the near")) {
            val u = understand(text)
            assertTrue(u.query.isEmpty)
            assertEquals(chennai, u.query.city)
            assertFalse(u.query.canSearch)
        }
        assertFalse(understand("madurai").query.canSearch)
        assertEquals(understand("quiet cafe evening"), understand("quiet cafe evening")) // the same words, the same answer
    }

    @Test
    fun `a query can be sent only with a catalog city, something asked and within the limits`() {
        assertFalse(ExploreQuery(chennai).canSearch)
        assertTrue(ExploreQuery(chennai, facets = listOf("QUIET")).canSearch)
        assertFalse(ExploreQuery(null, facets = listOf("QUIET")).canSearch)
        assertFalse(ExploreQuery(Cities.other, facets = listOf("QUIET")).canSearch)
        assertFalse(ExploreQuery(chennai, categories = Interest.entries.take(3)).canSearch)
        assertFalse(ExploreQuery(chennai, facets = ExploreFacets.all.take(5).map { it.code }).canSearch)
    }

    // ---- the DTOs fail closed ----

    @Test
    fun `the request carries only closed-list codes - there is nowhere to put the typed words`() {
        val dto = ExploreRequestDto.from(ExploreQuery(chennai, listOf(Interest.FOOD_CAFES), listOf("QUIET"), Daypart.EVENING))
        assertEquals("CHENNAI", dto.city)
        assertEquals(listOf("FOOD_CAFES"), dto.categories)
        assertEquals(listOf("QUIET"), dto.facets)
        assertEquals("EVENING", dto.daypart)
        assertEquals(setOf("city", "categories", "facets", "daypart"),
            ExploreRequestDto::class.java.declaredFields.map { it.name }.filterNot { it == "Companion" }.toSet())
    }

    @Test
    fun `an answer is read carefully - unreadable hits and unknown tags are dropped, numbers stay sane, at most twenty`() {
        fun hit(id: String?, name: String? = "A place", matched: Int? = 1, requested: Int? = 2, facets: List<String>? = listOf("QUIET")) =
            ExploreHitDto(ExplorePlaceDto(id, name, "FOOD_CAFES", "CHENNAI"), true, facets, false, requested, matched)
        val result = ExploreResponseDto("CHENNAI", 2, listOf(
            hit("p1", facets = listOf("QUIET", "NOT_A_TAG", "SCENIC")),
            hit(null), hit("p3", name = null), hit("p4", requested = null),
            hit("p5", matched = 9),   // more matches than asked: clamped to what was asked
            hit("p6", matched = 0),   // matched nothing: never listed
            hit("p7", matched = -3)
        )).toDomain()
        assertEquals(listOf("p1", "p5"), result.hits.map { it.placeId })
        assertEquals(listOf("QUIET", "SCENIC"), result.hits[0].matchedFacets)
        assertEquals(2, result.hits[1].matched)
        assertEquals(chennai, result.city)
        val many = ExploreResponseDto("CHENNAI", 1, (1..30).map { hit("p$it", requested = 1) }).toDomain()
        assertEquals(20, many.hits.size)
        assertEquals(0, ExploreResponseDto(null, null, null).toDomain().hits.size)
    }

    @Test
    fun `a post carries its tags, older answers have none, unknown tags are dropped, an Ask sends none`() {
        val read = PostDto(publicId = "p", type = "EXPERIENCE", facets = listOf("SCENIC", "NOPE", "QUIET")).toDomain()!!
        assertEquals(listOf("QUIET", "SCENIC"), read.facets)
        assertEquals(emptyList<String>(), PostDto(publicId = "p", type = "EXPERIENCE").toDomain()!!.facets)

        fun draft(type: PostType, facets: List<String>) = PostDraft(
            type = type, body = "b".repeat(30), tip = "", category = null, place = null, precision = Precision.NONE,
            city = null, month = null, daypart = null, audience = Audience.EVERYONE, commentPolicy = CommentPolicy.EVERYONE,
            mediaIds = emptyList(), facets = facets)
        assertEquals(listOf("QUIET", "SCENIC"),
            PostRequestDto.from(draft(PostType.EXPERIENCE, listOf("SCENIC", "QUIET", "NOPE"))).facets)
        assertNull(PostRequestDto.from(draft(PostType.EXPERIENCE, emptyList())).facets)
        assertNull(PostRequestDto.from(draft(PostType.ASK, listOf("QUIET"))).facets)
        assertEquals(4, PostRequestDto.from(draft(PostType.EXPERIENCE, ExploreFacets.all.take(6).map { it.code })).facets!!.size)
    }

    // ---- the ViewModel ----

    private fun hit(id: String) = ExploreHit(id, "Place $id", Interest.FOOD_CAFES, chennai, true, listOf("QUIET"), false, 2, 2)

    @Test
    fun `typing fills the chips, Search sends ONLY the structured query, and the answer is shown`() = runTest {
        val repo = FakeExploreRepository(ApiResult.Success(ExploreResult(chennai, 3, listOf(hit("a"), hit("b")))))
        val vm = ExploreViewModel(repo, initialCity = chennai)
        assertEquals(chennai, vm.uiState.value.city)
        assertFalse(vm.uiState.value.canSearch)

        vm.onText("quiet cafe to study in the evening")
        val typed = vm.uiState.value
        assertEquals(listOf(Interest.FOOD_CAFES, Interest.STUDY_WORK), typed.categories)
        assertEquals(listOf("QUIET"), typed.facets)
        assertEquals(Daypart.EVENING, typed.daypart)
        assertTrue(typed.canSearch)
        assertTrue(repo.queries.isEmpty()) // typing never searches by itself

        vm.search()
        advanceUntilIdle()
        assertEquals(listOf(ExploreQuery(chennai, listOf(Interest.FOOD_CAFES, Interest.STUDY_WORK), listOf("QUIET"), Daypart.EVENING)),
            repo.queries)
        assertEquals(ExplorePhase.RESULTS, vm.uiState.value.phase)
        assertEquals(listOf("a", "b"), vm.uiState.value.result!!.hits.map { it.placeId })
    }

    @Test
    fun `Search does nothing when nothing is asked, and nothing is sent`() = runTest {
        val repo = FakeExploreRepository()
        val vm = ExploreViewModel(repo, initialCity = chennai)
        vm.search()
        advanceUntilIdle()
        assertTrue(repo.queries.isEmpty())
        assertEquals(ExplorePhase.IDLE, vm.uiState.value.phase)
    }

    @Test
    fun `changing a chip, the city or the words clears the old answer`() = runTest {
        val repo = FakeExploreRepository(ApiResult.Success(ExploreResult(chennai, 1, listOf(hit("a")))))
        val vm = ExploreViewModel(repo, initialCity = chennai)
        vm.onToggleFacet("QUIET")
        vm.search(); advanceUntilIdle()
        assertNotNull(vm.uiState.value.result)
        vm.onToggleFacet("SCENIC")
        assertNull(vm.uiState.value.result)
        vm.search(); advanceUntilIdle()
        vm.onCity(Cities.find("MADURAI")!!)
        assertNull(vm.uiState.value.result)
        assertEquals(ExplorePhase.IDLE, vm.uiState.value.phase)
        vm.search(); advanceUntilIdle()
        vm.onText("lively")
        assertNull(vm.uiState.value.result)
    }

    @Test
    fun `the chips respect the limits and a time of day can be turned off`() = runTest {
        val vm = ExploreViewModel(FakeExploreRepository(), initialCity = chennai)
        Interest.entries.take(3).forEach(vm::onToggleCategory)
        assertEquals(Interest.entries.take(2), vm.uiState.value.categories)
        assertTrue(vm.uiState.value.atCategoryLimit)
        ExploreFacets.all.take(6).forEach { vm.onToggleFacet(it.code) }
        assertEquals(ExploreFacets.all.take(4).map { it.code }, vm.uiState.value.facets)
        assertTrue(vm.uiState.value.atFacetLimit)
        vm.onToggleFacet("NOT_A_TAG")
        assertEquals(4, vm.uiState.value.facets.size)
        vm.onToggleFacet(ExploreFacets.all.first().code) // taking one off frees a place
        assertEquals(3, vm.uiState.value.facets.size)
        vm.onDaypart(Daypart.NIGHT); assertEquals(Daypart.NIGHT, vm.uiState.value.daypart)
        vm.onDaypart(Daypart.NIGHT); assertNull(vm.uiState.value.daypart)
        vm.onClear()
        assertTrue(vm.uiState.value.query.isEmpty)
        assertEquals("", vm.uiState.value.text)
    }

    @Test
    fun `a failed search keeps what was asked and shows the failure, nothing is retried by itself`() = runTest {
        val repo = FakeExploreRepository(ApiResult.Failure(ApiError.NO_CONNECTION))
        val vm = ExploreViewModel(repo, initialCity = chennai)
        vm.onText("quiet cafe")
        vm.search(); advanceUntilIdle()
        assertEquals(ExplorePhase.FAILED, vm.uiState.value.phase)
        assertEquals(ApiError.NO_CONNECTION, vm.uiState.value.failure!!.error)
        assertEquals(listOf("QUIET"), vm.uiState.value.facets)
        assertEquals(1, repo.queries.size)
        // Search again with a better connection.
        repo.answer = ApiResult.Success(ExploreResult(chennai, 2, emptyList()))
        vm.search(); advanceUntilIdle()
        assertEquals(ExplorePhase.RESULTS, vm.uiState.value.phase)
        assertTrue(vm.uiState.value.result!!.hits.isEmpty())
    }

    // ---- the code stays small and local ----

    private val understandingCode = listOf(
        "feature/social/domain/ExploreFacets.kt", "feature/social/domain/ExploreModels.kt",
        "feature/social/domain/ExploreLexicon.kt", "feature/social/domain/ExploreQueryParser.kt"
    )

    @Test
    fun `the understanding is pure Kotlin - no Android, no network, no location, no storage`() {
        for (path in understandingCode) {
            val text = File("src/main/java/com/safesphere/app/$path").readText(Charsets.UTF_8)
            for (forbidden in listOf("import android.", "retrofit", "okhttp", "LocationProvider", "DataStore", "SharedPreferences",
                "java.io.File", "FileOutputStream", "HttpURLConnection")) {
                assertFalse("$path must not use $forbidden", text.contains(forbidden))
            }
        }
    }

    @Test
    fun `the Explore screens read no location, store nothing, and call no AI service`() {
        val screens = listOf("ExploreFragment.kt", "ExploreViewModel.kt", "ExploreHitAdapter.kt", "MultiChips.kt")
        for (name in screens) {
            val text = File("src/main/java/com/safesphere/app/feature/social/ui/$name").readText(Charsets.UTF_8)
            for (forbidden in listOf("LocationAsk", "LocationProvider", "oneFix", "preciseFix", "DataStore", "SharedPreferences",
                "EncryptedFile", "openFileOutput")) {
                assertFalse("$name must not use $forbidden", text.contains(forbidden))
            }
        }
        val api = File("src/main/java/com/safesphere/app/feature/social/data/remote/ExploreApi.kt").readText(Charsets.UTF_8)
        assertEquals("one endpoint: the structured search", 1, Regex("@POST|@GET|@PUT|@DELETE").findAll(api).count())
        assertTrue(api.contains("@POST(\"api/explore/search\")"))
    }

    @Test
    fun `the match sentence counts only what the person asked for - never people, posts or reactions - and has a singular`() {
        val xml = File("src/main/res/values/strings.xml").readText(Charsets.UTF_8)
        val block = Regex("<plurals name=\"explore_match_of\">(.*?)</plurals>", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1)
        assertNotNull("explore_match_of plurals", block)
        val items = Regex("<item quantity=\"(\\w+)\">(.*?)</item>").findAll(block!!).associate { it.groupValues[1] to it.groupValues[2] }
        assertEquals(setOf("one", "other"), items.keys)
        for ((quantity, text) in items) {
            assertTrue("$quantity says what was asked for", text.contains("you asked for"))
            assertFalse("$quantity must not count people or posts",
                Regex("people|person|posts?|reactions?|experiences?|likes?").containsMatchIn(text.lowercase()))
        }
        assertTrue(items.getValue("one").contains("thing you") && items.getValue("other").contains("things you"))
    }

    @Test
    fun `no part of the app names an AI service or holds a key for one`() {
        val offenders = File("src/main/java").walkTopDown().filter { it.extension == "kt" }
            // ("CLAUDE.md" in a comment names the project's notes file, not a service.)
            .filter { Regex("anthropic|claude(?!\\.md)|api\\.openai|x-api-key|ANTHROPIC_API_KEY", RegexOption.IGNORE_CASE).containsMatchIn(it.readText(Charsets.UTF_8)) }
            .map { it.name }.toList()
        assertEquals(emptyList<String>(), offenders)
    }
}
