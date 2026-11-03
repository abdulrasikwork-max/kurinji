package com.safesphere.app.feature.social.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.safesphere.app.AppContainer
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.core.network.isSessionExpired
import com.safesphere.app.feature.social.domain.Cities
import com.safesphere.app.feature.social.domain.City
import com.safesphere.app.feature.social.domain.Daypart
import com.safesphere.app.feature.social.domain.ExploreFacets
import com.safesphere.app.feature.social.domain.ExploreQuery
import com.safesphere.app.feature.social.domain.ExploreQueryParser
import com.safesphere.app.feature.social.domain.ExploreRepository
import com.safesphere.app.feature.social.domain.ExploreResult
import com.safesphere.app.feature.social.domain.Interest
import com.safesphere.app.feature.social.domain.SmartUnderstandingChoice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where Explore is: nothing searched yet, a search running, an answer, or a failure. */
enum class ExplorePhase { IDLE, LOADING, RESULTS, FAILED }

/**
 * Who turned the typed words into the chips: the phone's word list (the default), the optional smart understanding, or the
 * phone's word list after smart understanding could not help (said once, never as an error).
 */
enum class UnderstoodBy { PHONE, SMART, FALLBACK }

/**
 * Phase 17G-1: what the Explore screen shows. [text] is what the person typed (it stays on the phone unless they agreed to
 * smart understanding, 17G-3); the chips ([categories], [facets], [daypart], [city]) are what is searched and are always
 * visible and editable; [unused] are the words the phone did not use. [result] is held in memory only: nothing about other
 * people is stored (Q3).
 */
data class ExploreUiState(
    val text: String = "",
    val city: City? = null,
    val categories: List<Interest> = emptyList(),
    val facets: List<String> = emptyList(),
    val daypart: Daypart? = null,
    val unused: List<String> = emptyList(),
    val phase: ExplorePhase = ExplorePhase.IDLE,
    val result: ExploreResult? = null,
    val failure: ApiResult.Failure? = null,
    val sessionExpired: Boolean = false,
    /** 17G-3: this server offers smart understanding (the switch is shown only then). */
    val smartAvailable: Boolean = false,
    /** 17G-3: THIS account agreed to it (off by default). */
    val smartOn: Boolean = false,
    /** 17G-3: smart understanding was tried for these words, or the chips were set by hand: not asked again until they change. */
    val smartSettled: Boolean = false,
    val understoodBy: UnderstoodBy = UnderstoodBy.PHONE
) {
    val query: ExploreQuery get() = ExploreQuery(city, categories, facets, daypart)

    /** Smart understanding will be asked for the typed words when Search is pressed. */
    val smartPending: Boolean get() = smartOn && smartAvailable && text.isNotBlank() && !smartSettled

    private val cityOk: Boolean get() = city != null && !city.isOther

    /**
     * Something is asked for (or smart understanding is about to read the typed words), a catalog city is chosen, and no
     * search is running.
     */
    val canSearch: Boolean get() = phase != ExplorePhase.LOADING && (query.canSearch || (smartPending && cityOk))

    val atCategoryLimit: Boolean get() = categories.size >= ExploreQuery.MAX_CATEGORIES
    val atFacetLimit: Boolean get() = facets.size >= ExploreFacets.MAX_PER_QUERY
}

/**
 * Phase 17G-1: Explore. Typing re-reads the words with the on-phone word list ([ExploreQueryParser]) and replaces the chips;
 * the chips can then be adjusted by hand. The search sends ONLY the chips (a structured query): the words never reach the
 * search. Changing anything clears the old answer (it no longer matches what is asked).
 *
 * Phase 17G-3 (optional): when THIS account agreed and the server offers it, pressing Search first asks smart understanding
 * to read the typed words and replaces the chips with its answer. Any failure, an empty answer or a missing agreement leaves
 * the phone's own chips and the search goes on; the words are only ever sent by that one call.
 */
class ExploreViewModel(
    private val repository: ExploreRepository,
    private val parser: ExploreQueryParser = ExploreQueryParser(),
    initialCity: City? = null,
    /** 17G-3: the optional smart understanding; null (tests, or never offered) = the phone's word list only. */
    private val smart: SmartUnderstandingChoice? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(ExploreUiState(city = initialCity ?: Cities.all.firstOrNull()))
    val uiState: StateFlow<ExploreUiState> = _uiState.asStateFlow()

    /** Bumped by every change of the words, the chips or a search, so an answer that arrives late is dropped, never applied. */
    private var generation = 0

    init {
        if (smart != null) {
            viewModelScope.launch {
                smart.enabled.collect { on -> _uiState.update { it.copy(smartOn = on, smartSettled = false) } }
            }
            viewModelScope.launch {
                val available = repository.understandingAvailable()
                _uiState.update { it.copy(smartAvailable = available) }
            }
        }
    }

    fun onText(text: String) {
        generation++
        val understood = parser.parse(text, _uiState.value.city)
        val q = understood.query
        _uiState.update {
            it.copy(text = text, city = q.city ?: it.city, categories = q.categories, facets = q.facets,
                daypart = q.daypart, unused = understood.unused, phase = ExplorePhase.IDLE, result = null, failure = null,
                smartSettled = false, understoodBy = UnderstoodBy.PHONE)
        }
    }

    fun onCity(city: City) = changed { it.copy(city = city) }

    fun onToggleCategory(category: Interest) = changed { s ->
        when {
            category in s.categories -> s.copy(categories = s.categories - category)
            s.atCategoryLimit -> s
            else -> s.copy(categories = s.categories + category)
        }
    }

    fun onToggleFacet(code: String) = changed { s ->
        when {
            !ExploreFacets.isValid(code) -> s
            code in s.facets -> s.copy(facets = s.facets - code)
            s.atFacetLimit -> s
            else -> s.copy(facets = s.facets + code)
        }
    }

    /** Choosing the time of day that is already chosen clears it. */
    fun onDaypart(daypart: Daypart) = changed { it.copy(daypart = if (it.daypart == daypart) null else daypart) }

    fun onClear() = changed { it.copy(text = "", categories = emptyList(), facets = emptyList(), daypart = null, unused = emptyList()) }

    fun search() {
        val state = _uiState.value
        if (!state.canSearch) return
        val mine = ++generation
        _uiState.update { it.copy(phase = ExplorePhase.LOADING, failure = null, result = null) }
        viewModelScope.launch {
            if (state.smartPending) {
                understandFirst(state.text, mine)
                if (mine != generation) return@launch
            }
            val query = _uiState.value.query
            if (!query.canSearch) {
                // Smart understanding could not help and the phone's word list found nothing either: nothing to search.
                _uiState.update { it.copy(phase = ExplorePhase.IDLE) }
                return@launch
            }
            when (val result = repository.search(query)) {
                is ApiResult.Success -> if (mine == generation) {
                    _uiState.update { it.copy(phase = ExplorePhase.RESULTS, result = result.data) }
                }
                is ApiResult.Failure -> if (mine == generation) {
                    _uiState.update {
                        it.copy(phase = ExplorePhase.FAILED, failure = result, sessionExpired = result.isSessionExpired())
                    }
                }
            }
        }
    }

    /**
     * 17G-3: asks the optional smart understanding for the typed words and, if it answers, replaces the chips with its answer.
     * ANY failure, an empty answer or a missing agreement leaves the phone's own chips as they are and says so once
     * ([UnderstoodBy.FALLBACK]); the search then goes on. Only a signed-out session stops it (the person is told).
     */
    private suspend fun understandFirst(text: String, mine: Int) {
        if (smart == null || !smart.isEnabled()) {
            // The agreement was withdrawn (or belongs to another account) since the screen last looked: send nothing.
            if (mine == generation) _uiState.update { it.copy(smartOn = false, smartSettled = true) }
            return
        }
        val result = repository.understand(text)
        if (mine != generation) return
        _uiState.update { state ->
            when {
                result is ApiResult.Failure && result.isSessionExpired() ->
                    state.copy(phase = ExplorePhase.IDLE, sessionExpired = true, smartSettled = true)
                result is ApiResult.Success && !result.data.isEmpty -> state.copy(
                    categories = result.data.categories, facets = result.data.facets, daypart = result.data.daypart,
                    unused = emptyList(), smartSettled = true, understoodBy = UnderstoodBy.SMART)
                else -> state.copy(smartSettled = true, understoodBy = UnderstoodBy.FALLBACK)
            }
        }
        if (_uiState.value.sessionExpired) generation++   // do not go on to the search
    }

    /** 17G-3: the person confirmed the question: this account now sends its typed words to smart understanding. */
    fun onSmartConfirmed() {
        viewModelScope.launch { smart?.set(true) }
    }

    /** 17G-3: switched off: nothing more is sent; the choice is forgotten. */
    fun onSmartOff() {
        viewModelScope.launch { smart?.set(false) }
    }

    fun onSessionExpiredHandled() = _uiState.update { it.copy(sessionExpired = false) }

    private fun changed(block: (ExploreUiState) -> ExploreUiState) {
        generation++
        _uiState.update {
            // Chips set by hand are the person's own choice: smart understanding does not overwrite them.
            block(it).copy(phase = ExplorePhase.IDLE, result = null, failure = null, smartSettled = true,
                understoodBy = UnderstoodBy.PHONE)
        }
    }

    companion object {
        /** Optional start city: the code Discover is showing. */
        const val ARG_CITY = "city"

        fun factory(container: AppContainer, initialCity: City?) = viewModelFactory {
            initializer {
                ExploreViewModel(container.exploreRepository, initialCity = initialCity, smart = container.smartUnderstanding)
            }
        }
    }
}
