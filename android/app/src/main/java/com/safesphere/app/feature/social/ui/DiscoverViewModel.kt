package com.safesphere.app.feature.social.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.safesphere.app.AppContainer
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.core.network.isSessionExpired
import com.safesphere.app.feature.social.domain.City
import com.safesphere.app.feature.social.domain.CityMatch
import com.safesphere.app.feature.social.domain.DiscoverItem
import com.safesphere.app.feature.social.domain.DiscoverRepository
import com.safesphere.app.feature.social.domain.Edition
import com.safesphere.app.feature.social.domain.Place
import com.safesphere.app.feature.social.domain.SocialRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class DiscoverMode { EDITION, CONNECTIONS }

data class DiscoverUiState(
    val mode: DiscoverMode = DiscoverMode.EDITION,
    /** The Edition's city; null until the profile was read and while the person has not chosen one. */
    val city: City? = null,
    /** The profile named no launch city: the person picks one (kept on this screen only). */
    val needsCity: Boolean = false,
    val edition: Edition? = null,
    val editionLoading: Boolean = false,
    val connections: List<DiscoverItem> = emptyList(),
    val connectionsHasMore: Boolean = false,
    val connectionsLoaded: Boolean = false,
    val connectionsLoading: Boolean = false,
    val query: String = "",
    val places: List<Place> = emptyList(),
    val searching: Boolean = false,
    val searched: Boolean = false,
    val failure: ApiResult.Failure? = null,
    val sessionExpired: Boolean = false
)

/**
 * Phase 13C: Discover (design §3.7). The weekly Edition for a city (finite: it ends with "Go make your own"), From your
 * connections (newest first, more only on "Show more", then it ends), and search by place name. Categories are matched
 * on the screen against the closed list. Nothing is stored on the phone, not even the chosen city.
 */
class DiscoverViewModel(
    private val repository: DiscoverRepository,
    private val social: SocialRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DiscoverUiState())
    val uiState: StateFlow<DiscoverUiState> = _uiState.asStateFlow()

    private var started = false
    private var connectionsCursor: String? = null

    /** Reads the profile's city once; a launch city is chosen for the Edition, anything else asks the person. */
    fun start() {
        if (started) return
        started = true
        viewModelScope.launch {
            val city = when (val profile = social.ownProfile()) {
                is ApiResult.Success -> CityMatch.of(profile.data?.city)
                is ApiResult.Failure -> {
                    fail(profile)
                    null
                }
            }
            if (city == null) _uiState.update { it.copy(needsCity = true) } else onCity(city)
        }
    }

    fun onCity(city: City) {
        if (city == City.OTHER) return
        _uiState.update { it.copy(city = city, needsCity = false, edition = null) }
        loadEdition()
    }

    fun onMode(mode: DiscoverMode) {
        _uiState.update { it.copy(mode = mode) }
        if (mode == DiscoverMode.CONNECTIONS && !_uiState.value.connectionsLoaded) reloadConnections()
    }

    fun reload() {
        if (_uiState.value.city != null) loadEdition()
        if (_uiState.value.connectionsLoaded) reloadConnections()
    }

    fun onShowMoreConnections() {
        if (_uiState.value.connectionsHasMore) loadConnections()
    }

    fun onQuery(text: String) = _uiState.update { it.copy(query = text) }

    /** Searches place names (2 characters or more); categories are matched on the screen. */
    fun onSearch() {
        val query = _uiState.value.query.trim()
        if (query.length < SEARCH_MIN || _uiState.value.searching) return
        viewModelScope.launch {
            _uiState.update { it.copy(searching = true, failure = null) }
            when (val result = repository.searchPlaces(query, _uiState.value.city)) {
                is ApiResult.Success -> _uiState.update { it.copy(searching = false, searched = true, places = result.data) }
                is ApiResult.Failure -> {
                    _uiState.update { it.copy(searching = false) }
                    fail(result)
                }
            }
        }
    }

    fun onClearSearch() = _uiState.update { it.copy(query = "", places = emptyList(), searched = false) }

    fun onSessionExpiredHandled() = _uiState.update { it.copy(sessionExpired = false) }

    private fun loadEdition() {
        val city = _uiState.value.city ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(editionLoading = true, failure = null) }
            when (val result = repository.edition(city)) {
                is ApiResult.Success -> if (_uiState.value.city == city) {
                    _uiState.update { it.copy(editionLoading = false, edition = result.data) }
                }
                is ApiResult.Failure -> {
                    _uiState.update { it.copy(editionLoading = false) }
                    fail(result)
                }
            }
        }
    }

    private fun reloadConnections() {
        connectionsCursor = null
        _uiState.update { it.copy(connections = emptyList(), connectionsHasMore = false, connectionsLoaded = false) }
        loadConnections()
    }

    private fun loadConnections() {
        if (_uiState.value.connectionsLoading) return
        viewModelScope.launch {
            _uiState.update { it.copy(connectionsLoading = true, failure = null) }
            when (val result = repository.fromConnections(connectionsCursor)) {
                is ApiResult.Success -> {
                    connectionsCursor = result.data.nextCursor
                    _uiState.update {
                        it.copy(connectionsLoading = false, connectionsLoaded = true,
                            connections = it.connections + result.data.items,
                            connectionsHasMore = result.data.nextCursor != null)
                    }
                }
                is ApiResult.Failure -> {
                    _uiState.update { it.copy(connectionsLoading = false) }
                    fail(result)
                }
            }
        }
    }

    private fun fail(failure: ApiResult.Failure) =
        _uiState.update { it.copy(failure = failure, sessionExpired = failure.isSessionExpired()) }

    companion object {
        const val SEARCH_MIN = 2

        fun factory(container: AppContainer) = viewModelFactory {
            initializer { DiscoverViewModel(container.discoverRepository, container.socialRepository) }
        }
    }
}
