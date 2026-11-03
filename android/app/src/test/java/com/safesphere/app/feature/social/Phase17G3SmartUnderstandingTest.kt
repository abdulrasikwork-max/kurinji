package com.safesphere.app.feature.social

import com.safesphere.app.core.network.ApiCallHandler
import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.social.data.RemoteExploreRepository
import com.safesphere.app.feature.social.data.remote.ExploreApi
import com.safesphere.app.feature.social.data.remote.ExploreRequestDto
import com.safesphere.app.feature.social.data.remote.ExploreResponseDto
import com.safesphere.app.feature.social.data.remote.UnderstandRequestDto
import com.safesphere.app.feature.social.data.remote.UnderstandResponseDto
import com.safesphere.app.feature.social.data.remote.UnderstandingStatusDto
import com.safesphere.app.feature.social.domain.Cities
import com.safesphere.app.feature.social.domain.City
import com.safesphere.app.feature.social.domain.Daypart
import com.safesphere.app.feature.social.domain.ExploreQuery
import com.safesphere.app.feature.social.domain.ExploreRepository
import com.safesphere.app.feature.social.domain.ExploreResult
import com.safesphere.app.feature.social.domain.Interest
import com.safesphere.app.feature.social.domain.SmartUnderstandingChoice
import com.safesphere.app.feature.social.domain.Understanding
import com.safesphere.app.feature.social.domain.ViewerPreferences
import com.safesphere.app.feature.social.ui.ExplorePhase
import com.safesphere.app.feature.social.ui.ExploreViewModel
import com.safesphere.app.feature.social.ui.UnderstoodBy
import com.safesphere.app.feature.auth.domain.AuthSession
import com.safesphere.app.feature.auth.domain.AuthUser
import com.safesphere.app.testing.FakeExploreRepository
import com.safesphere.app.testing.FakeSessionStorage
import com.safesphere.app.testing.MainDispatcherRule
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Phase 17G-3: the OPTIONAL smart understanding on the phone. Off by default; agreed per account; the only call that carries
 * the typed words; any failure or empty answer falls back to the phone's own word list and the search still works; chips set by
 * hand are never overwritten; an answer that arrives late is dropped. Nothing here talks to a real service.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Phase17G3SmartUnderstandingTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val chennai: City = Cities.find("CHENNAI")!!

    private class FakePreferences : ViewerPreferences {
        val account = MutableStateFlow<Long?>(null)
        override val collapseStrongLanguage: Flow<Boolean> = MutableStateFlow(false)
        override suspend fun setCollapseStrongLanguage(on: Boolean) = Unit
        override val smartUnderstandingAccount: Flow<Long?> = account
        override suspend fun setSmartUnderstandingAccount(userId: Long?) {
            account.value = userId
        }
    }

    private var signedInAs: Long? = 7L
    private val preferences = FakePreferences()
    private val choice = SmartUnderstandingChoice(preferences) { signedInAs }

    private fun cafe() = Understanding(listOf(Interest.FOOD_CAFES), listOf("QUIET", "BUDGET_FRIENDLY"), Daypart.EVENING)

    // ---- the agreement ----

    @Test
    fun `it is off until the account agrees, and the agreement belongs to that account only`() = runTest {
        assertFalse(choice.isEnabled())
        choice.set(true)
        assertTrue(choice.isEnabled())
        assertEquals(7L, preferences.account.value)
        signedInAs = 8L
        assertFalse("another account on the same phone has NOT agreed", choice.isEnabled())
        signedInAs = null
        assertFalse(choice.isEnabled())
        signedInAs = 7L
        assertTrue(choice.isEnabled())
        choice.set(false)
        assertFalse(choice.isEnabled())
        assertNull(preferences.account.value)
    }

    @Test
    fun `without a signed-in account nothing is stored`() = runTest {
        signedInAs = null
        choice.set(true)
        assertNull(preferences.account.value)
        assertFalse(choice.isEnabled())
    }

    // ---- the screen's logic ----

    private fun viewModel(repo: ExploreRepository, agreed: Boolean = true): ExploreViewModel {
        if (agreed) preferences.account.value = 7L
        return ExploreViewModel(repo, initialCity = chennai, smart = choice)
    }

    @Test
    fun `agreed and available - the answer replaces the chips and the search sends only those chips`() = runTest {
        val repo = FakeExploreRepository(available = true, understanding = ApiResult.Success(cafe()))
        val vm = viewModel(repo)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.smartAvailable)
        assertTrue(vm.uiState.value.smartOn)

        vm.onText("shaanthamaana idam cheap evening") // the word list finds only part of it
        vm.search(); advanceUntilIdle()

        assertEquals(listOf("shaanthamaana idam cheap evening"), repo.understoodTexts)
        assertEquals(UnderstoodBy.SMART, vm.uiState.value.understoodBy)
        assertEquals(listOf(ExploreQuery(chennai, listOf(Interest.FOOD_CAFES), listOf("QUIET", "BUDGET_FRIENDLY"), Daypart.EVENING)),
            repo.queries)
        assertEquals(ExplorePhase.RESULTS, vm.uiState.value.phase)
        assertTrue(vm.uiState.value.unused.isEmpty())
    }

    @Test
    fun `not agreed - nothing is ever handed to smart understanding`() = runTest {
        val repo = FakeExploreRepository(available = true, understanding = ApiResult.Success(cafe()))
        val vm = viewModel(repo, agreed = false)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.smartAvailable)
        assertFalse(vm.uiState.value.smartOn)
        vm.onText("quiet cafe")
        vm.search(); advanceUntilIdle()
        assertTrue(repo.understoodTexts.isEmpty())
        assertEquals(UnderstoodBy.PHONE, vm.uiState.value.understoodBy)
        assertEquals(1, repo.queries.size)
    }

    @Test
    fun `the server not offering it - the switch never shows and nothing is sent even if this account agreed`() = runTest {
        val repo = FakeExploreRepository(available = false, understanding = ApiResult.Success(cafe()))
        val vm = viewModel(repo)
        advanceUntilIdle()
        vm.onText("quiet cafe")
        assertFalse(vm.uiState.value.smartAvailable)
        assertFalse(vm.uiState.value.smartPending)
        vm.search(); advanceUntilIdle()
        assertTrue(repo.understoodTexts.isEmpty())
        assertEquals(1, repo.queries.size)
    }

    @Test
    fun `any failure falls back to the phone's chips, says so once, and the search still happens`() = runTest {
        for (failure in listOf(ApiError.NO_CONNECTION, ApiError.UNKNOWN, ApiError.SERVER, ApiError.TOO_MANY_ATTEMPTS)) {
            val repo = FakeExploreRepository(available = true, understanding = ApiResult.Failure(failure))
            val vm = viewModel(repo)
            advanceUntilIdle()
            vm.onText("quiet cafe")
            vm.search(); advanceUntilIdle()
            assertEquals(failure.name, UnderstoodBy.FALLBACK, vm.uiState.value.understoodBy)
            assertEquals(listOf("QUIET"), vm.uiState.value.facets)
            assertEquals(failure.name, 1, repo.queries.size)
            assertEquals(ExplorePhase.RESULTS, vm.uiState.value.phase)
            assertNull(vm.uiState.value.failure)
        }
    }

    @Test
    fun `an empty answer counts as not helping - the phone's chips are kept`() = runTest {
        val repo = FakeExploreRepository(available = true, understanding = ApiResult.Success(Understanding()))
        val vm = viewModel(repo)
        advanceUntilIdle()
        vm.onText("quiet cafe")
        vm.search(); advanceUntilIdle()
        assertEquals(UnderstoodBy.FALLBACK, vm.uiState.value.understoodBy)
        assertEquals(listOf("QUIET"), vm.uiState.value.facets)
        assertEquals(1, repo.queries.size)
    }

    @Test
    fun `words the phone cannot read still reach Search when smart understanding is on, and nothing is searched if it cannot help`() = runTest {
        val repo = FakeExploreRepository(available = true, understanding = ApiResult.Failure(ApiError.SERVER))
        val vm = viewModel(repo)
        advanceUntilIdle()
        vm.onText("zzqx wibble")
        assertTrue(vm.uiState.value.query.isEmpty)
        assertTrue("Search is offered so smart understanding can read the words", vm.uiState.value.canSearch)
        vm.search(); advanceUntilIdle()
        assertEquals(listOf("zzqx wibble"), repo.understoodTexts)
        assertTrue("an empty query is never sent", repo.queries.isEmpty())
        assertEquals(ExplorePhase.IDLE, vm.uiState.value.phase)
        assertEquals(UnderstoodBy.FALLBACK, vm.uiState.value.understoodBy)
        assertFalse(vm.uiState.value.canSearch)
    }

    @Test
    fun `without smart understanding words the phone cannot read leave Search off, exactly as in 17G-1`() = runTest {
        val vm = ExploreViewModel(FakeExploreRepository(), initialCity = chennai)
        vm.onText("zzqx wibble")
        assertFalse(vm.uiState.value.canSearch)
    }

    @Test
    fun `chips set by hand are never overwritten, and unchanged words are not sent twice`() = runTest {
        val repo = FakeExploreRepository(available = true, understanding = ApiResult.Success(cafe()))
        val vm = viewModel(repo)
        advanceUntilIdle()
        vm.onText("quiet cafe")
        vm.onToggleFacet("SCENIC") // a hand edit after typing
        vm.search(); advanceUntilIdle()
        assertTrue("the person's own choice is searched as it is", repo.understoodTexts.isEmpty())
        assertEquals(listOf("QUIET", "SCENIC"), repo.queries.single().facets)

        val repo2 = FakeExploreRepository(available = true, understanding = ApiResult.Success(cafe()))
        val vm2 = viewModel(repo2)
        advanceUntilIdle()
        vm2.onText("quiet cafe")
        vm2.search(); advanceUntilIdle()
        vm2.search(); advanceUntilIdle()
        assertEquals("asked once for these words", 1, repo2.understoodTexts.size)
        assertEquals(2, repo2.queries.size)
        vm2.onText("quiet cafe now")
        vm2.search(); advanceUntilIdle()
        assertEquals("new words are asked again", 2, repo2.understoodTexts.size)
    }

    @Test
    fun `an agreement withdrawn or belonging to another account is re-checked at the moment of sending`() = runTest {
        val repo = FakeExploreRepository(available = true, understanding = ApiResult.Success(cafe()))
        val vm = viewModel(repo)
        advanceUntilIdle()
        vm.onText("quiet cafe")
        signedInAs = 8L // another account signed in after the screen was built
        vm.search(); advanceUntilIdle()
        assertTrue("no word is sent for an account that did not agree", repo.understoodTexts.isEmpty())
        assertEquals(1, repo.queries.size)
    }

    @Test
    fun `switching it off or on is remembered for this account and changes the next search`() = runTest {
        val repo = FakeExploreRepository(available = true, understanding = ApiResult.Success(cafe()))
        val vm = viewModel(repo, agreed = false)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.smartOn)
        vm.onSmartConfirmed(); advanceUntilIdle()
        assertTrue(vm.uiState.value.smartOn)
        vm.onSmartOff(); advanceUntilIdle()
        assertFalse(vm.uiState.value.smartOn)
        assertNull(preferences.account.value)
    }

    @Test
    fun `an answer that arrives after the words changed is dropped`() = runTest {
        val gate = CompletableDeferred<ApiResult<Understanding>>()
        val repo = object : ExploreRepository {
            val queries = mutableListOf<ExploreQuery>()
            override suspend fun search(query: ExploreQuery): ApiResult<ExploreResult> {
                queries += query
                return ApiResult.Success(ExploreResult(chennai, 1, emptyList()))
            }
            override suspend fun understand(text: String) = gate.await()
            override suspend fun understandingAvailable() = true
        }
        val vm = viewModel(repo)
        advanceUntilIdle()
        vm.onText("quiet cafe")
        vm.search(); advanceUntilIdle()
        assertEquals(ExplorePhase.LOADING, vm.uiState.value.phase)
        vm.onText("lively music")
        gate.complete(ApiResult.Success(cafe()))
        advanceUntilIdle()
        assertTrue("the late answer made no search", repo.queries.isEmpty())
        assertEquals("lively music", vm.uiState.value.text)
        assertEquals(listOf("LIVELY"), vm.uiState.value.facets)
        assertEquals(UnderstoodBy.PHONE, vm.uiState.value.understoodBy)
        assertEquals(ExplorePhase.IDLE, vm.uiState.value.phase)
    }

    @Test
    fun `a signed-out session stops the search and tells the person`() = runTest {
        val repo = FakeExploreRepository(available = true, understanding = ApiResult.Failure(ApiError.UNAUTHORIZED))
        val vm = viewModel(repo)
        advanceUntilIdle()
        vm.onText("quiet cafe")
        vm.search(); advanceUntilIdle()
        assertTrue(vm.uiState.value.sessionExpired)
        assertTrue(repo.queries.isEmpty())
    }

    @Test
    fun `the old behaviour is unchanged when no smart choice is given`() = runTest {
        val repo = FakeExploreRepository(available = true, understanding = ApiResult.Success(cafe()))
        val vm = ExploreViewModel(repo, initialCity = chennai)
        advanceUntilIdle()
        assertFalse("without a choice the server is not even asked", vm.uiState.value.smartAvailable)
        vm.onText("quiet cafe")
        vm.search(); advanceUntilIdle()
        assertTrue(repo.understoodTexts.isEmpty())
    }

    // ---- the data layer ----

    @Test
    fun `the answer is read fail-closed against the closed lists`() {
        val read = UnderstandResponseDto(
            categories = listOf("NOPE", "FOOD_CAFES", "FOOD_CAFES", "HIDDEN_GEMS", "CITY_LIFE"),
            facets = listOf("safe_place", "SCENIC", "QUIET", "LIVELY", "OPEN_LATE", "GOOD_ALONE"),
            daypart = "MIDNIGHT"
        ).toDomain()
        assertEquals(listOf(Interest.FOOD_CAFES, Interest.HIDDEN_GEMS), read.categories)
        assertEquals(4, read.facets.size)
        assertTrue(read.facets.all { com.safesphere.app.feature.social.domain.ExploreFacets.isValid(it) })
        assertNull(read.daypart)
        assertTrue(UnderstandResponseDto().toDomain().isEmpty)
        assertEquals(Daypart.NIGHT, UnderstandResponseDto(daypart = "NIGHT").toDomain().daypart)
    }

    @Test
    fun `the request never prints the typed words`() {
        assertEquals("UnderstandRequestDto(text=<16 chars>)", UnderstandRequestDto("my private words").toString())
    }

    private class RecordingApi : ExploreApi {
        val understood = mutableListOf<String>()
        var statusCalls = 0
        var available: Boolean? = true
        var fail = false
        override suspend fun search(body: ExploreRequestDto): ExploreResponseDto = ExploreResponseDto()
        override suspend fun understand(body: UnderstandRequestDto): UnderstandResponseDto {
            understood += body.text
            if (fail) throw java.io.IOException("offline")
            return UnderstandResponseDto(listOf("FOOD_CAFES"), listOf("QUIET"), "EVENING")
        }
        override suspend fun understandingStatus(): UnderstandingStatusDto {
            statusCalls++
            if (fail) throw java.io.IOException("offline")
            return UnderstandingStatusDto(available)
        }
    }

    private fun remote(api: ExploreApi): RemoteExploreRepository {
        val storage = FakeSessionStorage(AuthSession("jwt", AuthUser(7, "Asha", "asha@example.com")))
        return RemoteExploreRepository(api, ApiCallHandler(storage))
    }

    @Test
    fun `text that must not be sent never reaches the API`() = runTest {
        val api = RecordingApi()
        val repo = remote(api)
        assertEquals(ApiResult.Failure(ApiError.UNKNOWN), repo.understand(""))
        assertEquals(ApiResult.Failure(ApiError.UNKNOWN), repo.understand("   "))
        assertEquals(ApiResult.Failure(ApiError.UNKNOWN), repo.understand("x".repeat(201)))
        assertTrue(api.understood.isEmpty())
        val ok = repo.understand("  quiet cafe  ")
        assertEquals(listOf("quiet cafe"), api.understood)
        assertEquals(Understanding(listOf(Interest.FOOD_CAFES), listOf("QUIET"), Daypart.EVENING), (ok as ApiResult.Success).data)
        assertTrue(repo.understand("x".repeat(200)) is ApiResult.Success)
    }

    @Test
    fun `availability is true only on a clear yes, and false on anything else`() = runTest {
        val api = RecordingApi()
        val repo = remote(api)
        assertTrue(repo.understandingAvailable())
        api.available = false
        assertFalse(repo.understandingAvailable())
        api.available = null
        assertFalse(repo.understandingAvailable())
        api.fail = true
        assertFalse("offline is not available", repo.understandingAvailable())
        assertTrue(repo.understand("quiet") is ApiResult.Failure)
    }

    // ---- guards on the source and the words ----

    private fun source(vararg parts: String) = File("src/main/java/com/safesphere/app/feature/social/" + parts.joinToString("/")).readText(Charsets.UTF_8)

    @Test
    fun `only the Explore API and its repository know the request that carries the words`() {
        val offenders = File("src/main/java").walkTopDown().filter { it.extension == "kt" }
            .filter { it.readText(Charsets.UTF_8).contains("UnderstandRequestDto") }.map { it.name }.sorted().toList()
        assertEquals(listOf("ExploreApi.kt", "RemoteExploreRepository.kt"), offenders)
    }

    @Test
    fun `the search request still has exactly four fields and no words`() {
        assertEquals(listOf("city", "categories", "facets", "daypart"),
            ExploreRequestDto::class.java.declaredFields.map { it.name }.filter { it != "Companion" })
    }

    @Test
    fun `the only thing kept on the phone is one account id, never any words`() {
        val store = source("data", "DataStoreViewerPreferences.kt")
        assertTrue(store.contains("longPreferencesKey(\"smart_understanding_account\")"))
        assertFalse(store.contains("stringPreferencesKey"))
        val viewModel = source("ui", "ExploreViewModel.kt")
        assertFalse(viewModel.contains("DataStore"))
        assertFalse(viewModel.contains("SharedPreferences"))
        assertFalse(source("domain", "SmartUnderstandingChoice.kt").contains("text"))
    }

    @Test
    fun `the question names what happens, offers a plain way out, and the app does not name the provider`() {
        val strings = File("src/main/res/values/strings.xml").readText(Charsets.UTF_8)
        fun text(name: String) = Regex("<string name=\"$name\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
            .find(strings)!!.groupValues[1]
        val title = text("explore_smart_title")
        assertTrue(title.endsWith("(Preview)"))
        val message = text("explore_smart_dialog_message")
        assertTrue(message.contains("sent to IRL"))
        assertTrue(message.contains("external AI service"))
        assertTrue(message.contains("no places, no location and no account details"))
        assertTrue(message.contains("IRL does not save them"))
        assertTrue(message.contains("turn it off at any time"))
        assertTrue(text("explore_smart_summary").startsWith("Off unless you turn it on"))
        assertTrue(text("explore_helper").contains("stay on this phone"))
        assertFalse("the 'stay on this phone' line is replaced while it is on", text("explore_helper_smart").contains("stay on this phone"))
        listOf("explore_smart_title", "explore_smart_summary", "explore_smart_dialog_message", "explore_helper_smart",
            "explore_understood_smart", "explore_understood_standard").forEach {
            assertFalse(it, Regex("anthropic|claude|groq|openai", RegexOption.IGNORE_CASE).containsMatchIn(text(it)))
        }
        assertEquals("Standard understanding was used.", text("explore_understood_standard"))
    }
}
