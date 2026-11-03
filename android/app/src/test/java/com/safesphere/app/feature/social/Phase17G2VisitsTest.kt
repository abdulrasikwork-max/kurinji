package com.safesphere.app.feature.social

import com.safesphere.app.core.network.ApiCallHandler
import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.auth.domain.AuthSession
import com.safesphere.app.feature.auth.domain.AuthUser
import com.safesphere.app.feature.social.data.RemoteDiscoverRepository
import com.safesphere.app.feature.social.data.remote.DiscoverApi
import com.safesphere.app.feature.social.data.remote.EditionDto
import com.safesphere.app.feature.social.data.remote.DiscoverPageDto
import com.safesphere.app.feature.social.data.remote.MarkResponseDto
import com.safesphere.app.feature.social.data.remote.PlaceDto
import com.safesphere.app.feature.social.data.remote.PlacePageDto
import com.safesphere.app.feature.social.data.remote.PostPageDto
import com.safesphere.app.feature.social.data.remote.RatingRequestDto
import com.safesphere.app.feature.social.data.remote.SavedPageDto
import com.safesphere.app.feature.social.data.remote.VisitDto
import com.safesphere.app.feature.social.domain.PlacePage
import com.safesphere.app.feature.social.domain.PlaceRating
import com.safesphere.app.feature.social.domain.Precision
import com.safesphere.app.feature.social.domain.Visit
import com.safesphere.app.feature.social.ui.PostCollection
import com.safesphere.app.feature.social.ui.PostCollectionViewModel
import com.safesphere.app.feature.social.ui.PostViewModel
import com.safesphere.app.testing.FakeDiscoverRepository
import com.safesphere.app.testing.FakeExperienceRepository
import com.safesphere.app.testing.FakeSessionStorage
import com.safesphere.app.testing.MainDispatcherRule
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Phase 17G-2 (U5): "I went" and the guarded rating on the phone. The rating is only ever the server's TEXT and fails closed; only the
 * person's OWN visit exists on the phone; a rating outside 1 to 5 is never sent; the place page and the post page run the same flow;
 * nothing is stored; nothing is ordered by a rating.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Phase17G2VisitsTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val discover = FakeDiscoverRepository()
    private val experiences = FakeExperienceRepository()
    private val place = FakeExperienceRepository.place()

    // ---- the data layer reads fail-closed ----

    @Test
    fun `a public rating is only a digit, a dot and a digit - anything else means no rating`() {
        for (good in listOf("4.3", "5.0", "1.0", "3.9")) assertEquals(good, PlaceRating.clean(good))
        for (bad in listOf(null, "", "4", "4,3", "4.35", "0.9", "6.0", "four", "4.3 ", " 4.3", "4.3/5", "NaN", "10.0", "-4.3")) {
            assertNull(bad.toString(), PlaceRating.clean(bad))
        }
    }

    @Test
    fun `a visit outside 1 to 5 is not believed`() {
        assertEquals(Visit(null), VisitDto(null).toDomain())
        for (bad in listOf(0, 6, -1, 99)) assertEquals(Visit(null), VisitDto(bad).toDomain())
        for (good in 1..5) assertEquals(Visit(good), VisitDto(good).toDomain())
    }

    @Test
    fun `the place page carries the rating text and only the callers own visit`() {
        val dto = PlacePageDto(place = PlaceDto(publicId = "place-1", name = "Marina Beach", category = "NATURE_OUTDOORS",
            cell = "tf2c1xz", areaCell = "tf2c1x"), items = emptyList(), nextCursor = null, rating = "4.3", yourVisit = VisitDto(2))
        val page = dto.toDomain()!!
        assertEquals("4.3", page.rating)
        assertEquals(Visit(2), page.yourVisit)
        val unreadable = dto.copy(rating = "excellent", yourVisit = null).toDomain()!!
        assertNull(unreadable.rating)
        assertNull(unreadable.yourVisit)
        assertNull(dto.copy(rating = null).toDomain()!!.rating)
    }

    private class RecordingApi : DiscoverApi {
        val calls = mutableListOf<String>()
        override suspend fun react(postId: String) = MarkResponseDto()
        override suspend fun unreact(postId: String) = Unit
        override suspend fun save(postId: String) = MarkResponseDto()
        override suspend fun unsave(postId: String) = Unit
        override suspend fun saved(cursor: String?): SavedPageDto = throw UnsupportedOperationException()
        override suspend fun edition(city: String): EditionDto = throw UnsupportedOperationException()
        override suspend fun fromConnections(cursor: String?): DiscoverPageDto = throw UnsupportedOperationException()
        override suspend fun categoryPage(category: String, city: String?, cursor: String?): PostPageDto = throw UnsupportedOperationException()
        override suspend fun placePage(placeId: String, cursor: String?): PlacePageDto = throw UnsupportedOperationException()
        override suspend fun searchPlaces(query: String, city: String?): List<PlaceDto> = throw UnsupportedOperationException()
        override suspend fun went(placeId: String): VisitDto { calls += "went:$placeId"; return VisitDto(null) }
        override suspend fun notWent(placeId: String) { calls += "notWent:$placeId" }
        override suspend fun rate(placeId: String, body: RatingRequestDto): VisitDto { calls += "rate:$placeId:${body.rating}"; return VisitDto(body.rating) }
        override suspend fun clearRating(placeId: String): VisitDto { calls += "clear:$placeId"; return VisitDto(null) }
    }

    @Test
    fun `a rating outside 1 to 5 never reaches the network`() = runTest {
        val api = RecordingApi()
        val repository = RemoteDiscoverRepository(api, ApiCallHandler(FakeSessionStorage(AuthSession("jwt", AuthUser(1, "A", "a@example.com")))))
        for (bad in listOf(0, 6, -3, 100)) assertEquals(ApiResult.Failure(ApiError.VALIDATION), repository.rate("place-1", bad))
        assertTrue(api.calls.isEmpty())
        assertEquals(ApiResult.Success(Visit(4)), repository.rate("place-1", 4))
        assertEquals(ApiResult.Success(Visit(null)), repository.went("place-1"))
        assertEquals(ApiResult.Success(Visit(null)), repository.clearRating("place-1"))
        assertEquals(ApiResult.Success(Unit), repository.notWent("place-1"))
        assertEquals(listOf("rate:place-1:4", "went:place-1", "clear:place-1", "notWent:place-1"), api.calls)
    }

    // ---- the place page ----

    private fun placePageWith(rating: String?, visit: Visit?) {
        discover.placePages = mapOf(null to PlacePage(place, listOf(FakeExperienceRepository.post(id = "p1")), null, rating, visit))
    }

    @Test
    fun `the place page shows the public text and the own visit, and orders nothing by them`() = runTest {
        discover.placePages = mapOf(null to PlacePage(place,
            listOf(FakeExperienceRepository.post(id = "p-new"), FakeExperienceRepository.post(id = "p-old")), null, "4.3", Visit(5)))
        val vm = PostCollectionViewModel(discover)
        vm.show(PostCollection.OfPlace("place-1"))
        advanceUntilIdle()
        assertEquals("4.3", vm.uiState.value.rating)
        assertEquals(Visit(5), vm.uiState.value.visit)
        assertEquals("the server's order is kept", listOf("p-new", "p-old"), vm.uiState.value.items.map { it.publicId })
    }

    @Test
    fun `I went marks the visit, then asks how it was, and a second tap while it runs does nothing`() = runTest {
        placePageWith(null, null)
        val vm = PostCollectionViewModel(discover)
        vm.show(PostCollection.OfPlace("place-1"))
        advanceUntilIdle()
        assertNull(vm.uiState.value.visit)
        vm.onWent()
        advanceUntilIdle()
        assertEquals(listOf("went:place-1"), discover.visitCalls)
        assertEquals(Visit(null), vm.uiState.value.visit)
        assertTrue("the sheet is asked for", vm.uiState.value.visitSheet)
        vm.onVisitSheetHandled()
        assertFalse(vm.uiState.value.visitSheet)
        assertFalse(vm.uiState.value.visitBusy)
    }

    @Test
    fun `rating goes through the sheet, an invalid rating is not sent, and it can be taken back`() = runTest {
        placePageWith(null, Visit(null))
        val vm = PostCollectionViewModel(discover)
        vm.show(PostCollection.OfPlace("place-1"))
        advanceUntilIdle()
        for (bad in listOf(0, 6, -1)) vm.onRate(bad)
        advanceUntilIdle()
        assertTrue(discover.visitCalls.isEmpty())
        vm.onRate(4)
        advanceUntilIdle()
        assertEquals(Visit(4), vm.uiState.value.visit)
        vm.onRate(2)
        advanceUntilIdle()
        assertEquals(Visit(2), vm.uiState.value.visit)
        vm.onClearRating()
        advanceUntilIdle()
        assertEquals(Visit(null), vm.uiState.value.visit)
        assertEquals(listOf("rate:place-1:4", "rate:place-1:2", "clearRating:place-1"), discover.visitCalls)
        assertFalse("rating never opens the sheet by itself", vm.uiState.value.visitSheet)
    }

    @Test
    fun `taking I went back removes the visit`() = runTest {
        placePageWith(null, Visit(3))
        val vm = PostCollectionViewModel(discover)
        vm.show(PostCollection.OfPlace("place-1"))
        advanceUntilIdle()
        vm.onNotWent()
        advanceUntilIdle()
        assertNull(vm.uiState.value.visit)
        assertEquals(listOf("notWent:place-1"), discover.visitCalls)
    }

    @Test
    fun `the sheet can be opened again only for a place already marked`() = runTest {
        placePageWith(null, null)
        val vm = PostCollectionViewModel(discover)
        vm.show(PostCollection.OfPlace("place-1"))
        advanceUntilIdle()
        vm.onOpenVisitSheet()
        assertFalse(vm.uiState.value.visitSheet)
        placePageWith("4.1", Visit(null))
        vm.reload()
        advanceUntilIdle()
        vm.onOpenVisitSheet()
        assertTrue(vm.uiState.value.visitSheet)
    }

    @Test
    fun `a refusal is shown as a failure and changes nothing about the visit`() = runTest {
        placePageWith(null, null)
        val vm = PostCollectionViewModel(discover)
        vm.show(PostCollection.OfPlace("place-1"))
        advanceUntilIdle()
        discover.visitResult = ApiResult.Failure(ApiError.NOT_FOUND)
        vm.onWent()
        advanceUntilIdle()
        assertNull(vm.uiState.value.visit)
        assertFalse(vm.uiState.value.visitSheet)
        assertEquals(ApiError.NOT_FOUND, vm.uiState.value.failure!!.error)
        discover.visitResult = ApiResult.Failure(ApiError.UNAUTHORIZED)
        vm.onWent()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.sessionExpired)
    }

    // ---- the post page ----

    private fun postVm(precision: Precision, placeId: String?): PostViewModel {
        experiences.postResult = ApiResult.Success(FakeExperienceRepository.post().copy(precision = precision, placeId = placeId))
        return PostViewModel(experiences, discover, "post-1")
    }

    @Test
    fun `on a post naming an exact place, I went marks the visit and asks how it was`() = runTest {
        val vm = postVm(Precision.EXACT, "place-1")
        advanceUntilIdle()
        assertTrue(vm.onWent())
        advanceUntilIdle()
        assertEquals(listOf("went:place-1"), discover.visitCalls)
        assertTrue(vm.uiState.value.visitSheet)
        vm.onRate(5)
        advanceUntilIdle()
        assertEquals(Visit(5), vm.uiState.value.visit)
        vm.onNotWent()
        advanceUntilIdle()
        assertNull(vm.uiState.value.visit)
    }

    @Test
    fun `on a post with no exact place there is nothing to mark, so I went goes to the composer as before`() = runTest {
        for ((precision, id) in listOf(Precision.AREA to "place-1", Precision.CITY to null, Precision.NONE to null,
            Precision.EXACT to null)) {
            val vm = postVm(precision, id)
            advanceUntilIdle()
            assertFalse("$precision", vm.onWent())
            vm.onRate(3)
            advanceUntilIdle()
        }
        assertTrue(discover.visitCalls.isEmpty())
    }

    // ---- source and wording guards ----

    private fun source(path: String) = File("src/main/java/com/safesphere/app/feature/social/$path").readText(Charsets.UTF_8)

    private fun strings(): Map<String, String> {
        val xml = File("src/main/res/values/strings.xml").readText(Charsets.UTF_8)
        return Regex("<string name=\"([a-z0-9_]+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL).findAll(xml)
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    @Test
    fun `nothing about visits or ratings is stored on the phone or sent anywhere but its own endpoints`() {
        for (file in listOf("ui/PlaceVisits.kt", "ui/WentSheet.kt", "domain/DiscoverModels.kt")) {
            val text = source(file)
            for (forbidden in listOf("DataStore", "SharedPreferences", "EncryptedFile", "openFileOutput", "Room")) {
                assertFalse("$file must not use $forbidden", text.contains(forbidden))
            }
        }
        // only the person's own visit exists: no list of visits, no other person's rating
        val models = source("domain/DiscoverModels.kt")
        assertTrue(models.contains("data class Visit(val rating: Int?)"))
        assertFalse(models.contains("List<Visit>"))
    }

    @Test
    fun `no list is ever ordered by a rating`() {
        val offenders = File("src/main/java/com/safesphere/app/feature/social").walkTopDown().filter { it.extension == "kt" }
            .filter { file ->
                file.readLines(Charsets.UTF_8).any { line ->
                    Regex("sorted|sortedBy|sortedWith|sortBy|Comparator|maxBy|minBy|compareBy", RegexOption.IGNORE_CASE).containsMatchIn(line) &&
                        Regex("rating|visit", RegexOption.IGNORE_CASE).containsMatchIn(line)
                }
            }.map { it.name }.toList()
        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun `the words say the rating is private, optional, and from people who went - with no count`() {
        val s = strings()
        assertTrue(s["went_sheet_note"]!!.contains("Optional"))
        assertTrue(s["went_sheet_note"]!!.contains("private to you"))
        assertTrue(s["went_sheet_note"]!!.contains("once enough people who went have rated it"))
        for (name in listOf("place_rating_line", "place_rating_description")) {
            val text = s[name]!!
            assertTrue(name, text.contains("people who went"))
            assertTrue("$name: the text only, no number of people", text.contains("%1\$s") && !text.contains("%d") && !text.contains("%1\$d"))
        }
        // the numbers shown are the person's OWN rating
        for (name in listOf("went_rate_description", "went_rate_chosen", "went_status_rated", "place_went_rated")) {
            assertTrue(name, s[name]!!.contains("%1\$s") && s[name]!!.contains("out of 5") && !s[name]!!.contains("%d") && !s[name]!!.contains("%1\$d"))
        }
        assertTrue(s["went_rate_chosen"]!!.endsWith("your rating"))   // the chosen number says so in words, not colour alone
        assertEquals("I went here", s["place_share_yours"])
        for ((name, text) in s.filterKeys { it.startsWith("went_") || it.startsWith("place_went") || it.startsWith("place_rating") }) {
            assertFalse("$name: no ranking or engagement word", Regex("\\b(best|top|popular|trending|rank|ranked|hot)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text))
        }
    }

    @Test
    fun `the numbers are big buttons and the sheet is a drawn star with words`() {
        val layout = File("src/main/res/layout/item_rating_choice.xml").readText(Charsets.UTF_8)
        assertTrue(layout.contains("android:minHeight=\"@dimen/min_touch_target\""))
        val place = File("src/main/res/layout/fragment_post_collection.xml").readText(Charsets.UTF_8)
        assertTrue(place.contains("@drawable/ic_star"))
        assertFalse("no emoji star: a drawn icon and words", File("src/main/res/values/strings.xml").readText(Charsets.UTF_8).contains("⭐"))
    }
}
