package com.safesphere.app.feature.social.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.safesphere.app.AppContainer
import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.core.network.isSessionExpired
import com.safesphere.app.feature.social.domain.ButterflyState
import com.safesphere.app.feature.social.domain.CocoonItem
import com.safesphere.app.feature.social.domain.DiscoverRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CocoonUiState(
    val tab: ButterflyState = ButterflyState.WANT,
    val isLoading: Boolean = false,
    val items: List<CocoonItem> = emptyList(),
    /** More exist; they load only on "Show more". */
    val hasMore: Boolean = false,
    val loaded: Boolean = false,
    val isBusy: Boolean = false,
    /** One-shot: the item just marked Been, for the invitation (add a perspective / share your own experience). */
    val beenInvitation: CocoonItem? = null,
    val failure: ApiResult.Failure? = null,
    val sessionExpired: Boolean = false
)

/**
 * Phase 13C: the Cocoon (design §3.4): what the person wants to experience, and what they have been to. Their own list;
 * the server leaves out posts they can no longer see. Marking an item Been moves it to the Been list and invites a
 * perspective or an Experience of their own.
 */
class CocoonViewModel(private val repository: DiscoverRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(CocoonUiState())
    val uiState: StateFlow<CocoonUiState> = _uiState.asStateFlow()

    private var nextCursor: String? = null

    fun onTab(tab: ButterflyState) {
        if (_uiState.value.tab == tab && _uiState.value.loaded) return
        _uiState.update { it.copy(tab = tab) }
        reload()
    }

    fun reload() {
        nextCursor = null
        _uiState.update { it.copy(items = emptyList(), hasMore = false, loaded = false) }
        load()
    }

    fun onShowMore() {
        if (_uiState.value.hasMore) load()
    }

    fun onMarkBeen(item: CocoonItem) = change(item, ButterflyState.BEEN)

    fun onWantAgain(item: CocoonItem) = change(item, ButterflyState.WANT)

    fun onRemove(item: CocoonItem) {
        if (_uiState.value.isBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, failure = null) }
            when (val result = repository.removeButterfly(item.post.publicId)) {
                is ApiResult.Success -> drop(item)
                // Already gone: the list simply catches up.
                is ApiResult.Failure -> if (result.error == ApiError.NOT_FOUND) drop(item) else fail(result)
            }
        }
    }

    fun onBeenInvitationHandled() = _uiState.update { it.copy(beenInvitation = null) }
    fun onSessionExpiredHandled() = _uiState.update { it.copy(sessionExpired = false) }

    private fun change(item: CocoonItem, state: ButterflyState) {
        if (_uiState.value.isBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, failure = null) }
            when (val result = repository.setButterfly(item.post.publicId, state)) {
                is ApiResult.Success -> {
                    drop(item) // it now belongs to the other list
                    if (result.data == ButterflyState.BEEN) {
                        _uiState.update { it.copy(beenInvitation = item.copy(state = ButterflyState.BEEN)) }
                    }
                }
                // The post can no longer be seen (or is gone): it leaves the list.
                is ApiResult.Failure -> if (result.error == ApiError.NOT_FOUND) drop(item) else fail(result)
            }
        }
    }

    private fun drop(item: CocoonItem) = _uiState.update {
        it.copy(isBusy = false, items = it.items.filterNot { i -> i.post.publicId == item.post.publicId })
    }

    private fun load() {
        if (_uiState.value.isLoading) return
        val tab = _uiState.value.tab
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, failure = null) }
            when (val result = repository.cocoon(tab, nextCursor)) {
                is ApiResult.Success -> {
                    if (_uiState.value.tab != tab) return@launch
                    nextCursor = result.data.nextCursor
                    _uiState.update {
                        it.copy(isLoading = false, loaded = true, items = it.items + result.data.items,
                            hasMore = result.data.nextCursor != null)
                    }
                }
                is ApiResult.Failure -> fail(result)
            }
        }
    }

    private fun fail(failure: ApiResult.Failure) = _uiState.update {
        it.copy(isLoading = false, isBusy = false, failure = failure, sessionExpired = failure.isSessionExpired())
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { CocoonViewModel(container.discoverRepository) }
        }
    }
}
