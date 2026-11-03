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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where Explore is: nothing searched yet, a search running, an answer, or a failure. */
enum class ExplorePhase { IDLE, LOADING, RESULTS, FAILED }

/**
 * Phase 17G-1: what the Explore screen shows. [text] is what the person typed (it stays on the phone); the chips
 * ([categories], [facets], [daypart], [city]) are what is searched and are always visible and editable; [unused] are the
 * words the phone did not use. [result] is held in memory only: nothing about other people is stored (Q3).
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
    val sessionExpired: Boolean = false
) {
    val query: ExploreQuery get() = ExploreQuery(city, categories, facets, daypart)

    /** Something is asked for, a catalog city is chosen, and no search is running. */
    val canSearch: Boolean get() = query.canSearch && phase != ExplorePhase.LOADING

    val atCategoryLimit: Boolean get() = categories.size >= ExploreQuery.MAX_CATEGORIES
    val atFacetLimit: Boolean get() = facets.size >= ExploreFacets.MAX_PER_QUERY
}

/**
 * Phase 17G-1: Explore. Typing re-reads the words with the on-phone word list ([ExploreQueryParser]) and replaces the chips;
 * the chips can then be adjusted by hand. The search sends ONLY the chips (a structured query): the words never leave the
 * phone. Changing anything clears the old answer (it no longer matches what is asked).
 */
class ExploreViewModel(
    private val repository: ExploreRepository,
    private val parser: ExploreQueryParser = ExploreQueryParser(),
    initialCity: City? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(ExploreUiState(city = initialCity ?: Cities.all.firstOrNull()))
    val uiState: StateFlow<ExploreUiState> = _uiState.asStateFlow()

    fun onText(text: String) {
        val understood = parser.parse(text, _uiState.value.city)
        val q = understood.query
        _uiState.update {
            it.copy(text = text, city = q.city ?: it.city, categories = q.categories, facets = q.facets,
                daypart = q.daypart, unused = understood.unused, phase = ExplorePhase.IDLE, result = null, failure = null)
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
        _uiState.update { it.copy(phase = ExplorePhase.LOADING, failure = null, result = null) }
        viewModelScope.launch {
            when (val result = repository.search(state.query)) {
                is ApiResult.Success -> _uiState.update { it.copy(phase = ExplorePhase.RESULTS, result = result.data) }
                is ApiResult.Failure -> _uiState.update {
                    it.copy(phase = ExplorePhase.FAILED, failure = result, sessionExpired = result.isSessionExpired())
                }
            }
        }
    }

    fun onSessionExpiredHandled() = _uiState.update { it.copy(sessionExpired = false) }

    private fun changed(block: (ExploreUiState) -> ExploreUiState) =
        _uiState.update { block(it).copy(phase = ExplorePhase.IDLE, result = null, failure = null) }

    companion object {
        /** Optional start city: the code Discover is showing. */
        const val ARG_CITY = "city"

        fun factory(container: AppContainer, initialCity: City?) = viewModelFactory {
            initializer { ExploreViewModel(container.exploreRepository, initialCity = initialCity) }
        }
    }
}
