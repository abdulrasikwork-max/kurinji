package com.safesphere.app.feature.social.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.safesphere.app.AppContainer
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.core.network.isSessionExpired
import com.safesphere.app.feature.social.domain.DiscoverRepository
import com.safesphere.app.feature.social.domain.SavedItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SavedUiState(
    val isLoading: Boolean = false,
    val items: List<SavedItem> = emptyList(),
    /** More exist; they load only on "Show more" (the list ends, U3). */
    val hasMore: Boolean = false,
    val loaded: Boolean = false,
    /** Removals in flight (post ids), so a double tap sends nothing twice. */
    val removing: Set<String> = emptySet(),
    val failure: ApiResult.Failure? = null,
    val sessionExpired: Boolean = false
) {
    val isEmpty: Boolean get() = loaded && items.isEmpty() && !isLoading
}

/**
 * Phase 17E (D-3): Saved, the person's private bookmarks (the 13C Cocoon items moved here; "Been" is gone: "I went"
 * opens the composer instead). The server leaves out posts they can no longer see. Removing is shown at once and put
 * back if the server says no (removing is idempotent on the server).
 */
class SavedViewModel(private val repository: DiscoverRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(SavedUiState())
    val uiState: StateFlow<SavedUiState> = _uiState.asStateFlow()

    private var nextCursor: String? = null

    fun reload() {
        nextCursor = null
        _uiState.update { it.copy(items = emptyList(), hasMore = false, loaded = false, failure = null) }
        load()
    }

    fun onShowMore() {
        if (_uiState.value.hasMore) load()
    }

    fun onRemove(item: SavedItem) {
        val id = item.post.publicId
        if (id in _uiState.value.removing) return
        val index = _uiState.value.items.indexOfFirst { it.post.publicId == id }
        if (index < 0) return
        _uiState.update { it.copy(items = it.items.filterNot { i -> i.post.publicId == id }, removing = it.removing + id) }
        viewModelScope.launch {
            when (val result = repository.unsave(id)) {
                is ApiResult.Success -> _uiState.update { it.copy(removing = it.removing - id) }
                is ApiResult.Failure -> {
                    // Put it back where it was; the server still has it.
                    _uiState.update {
                        val items = it.items.toMutableList().apply { add(index.coerceAtMost(size), item) }
                        it.copy(items = items, removing = it.removing - id)
                    }
                    fail(result)
                }
            }
        }
    }

    fun onSessionExpiredHandled() = _uiState.update { it.copy(sessionExpired = false) }

    private fun load() {
        if (_uiState.value.isLoading) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, failure = null) }
            when (val result = repository.saved(nextCursor)) {
                is ApiResult.Success -> {
                    nextCursor = result.data.nextCursor
                    _uiState.update {
                        val known = it.items.map { i -> i.post.publicId }.toSet()
                        it.copy(isLoading = false, loaded = true,
                            items = it.items + result.data.items.filter { i -> i.post.publicId !in known },
                            hasMore = result.data.nextCursor != null)
                    }
                }
                is ApiResult.Failure -> fail(result)
            }
        }
    }

    private fun fail(failure: ApiResult.Failure) = _uiState.update {
        it.copy(isLoading = false, failure = failure, sessionExpired = failure.isSessionExpired())
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { SavedViewModel(container.discoverRepository) }
        }
    }
}
