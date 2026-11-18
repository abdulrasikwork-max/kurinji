package com.safesphere.app.feature.safetycircle.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.safesphere.app.AppContainer
import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.safetycircle.data.CircleSos
import com.safesphere.app.feature.safetycircle.data.CircleSosStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface CircleSosState {
    data object Loading : CircleSosState
    data class Shown(val sos: CircleSos, val stale: Boolean = false) : CircleSosState
    /** Not (or no longer) available to this person: 24 h after the end, or never theirs. */
    data object Gone : CircleSosState
    data object Failed : CircleSosState
}

/**
 * N7 (D36): an SOS of someone whose Safety Circle the person joined: the facts of the text, read while the screen is
 * visible (every [REFRESH_MS] while the SOS is on, to see it end). No history, no live tracking.
 */
class CircleSosViewModel(private val read: suspend (Long) -> ApiResult<CircleSos>) : ViewModel() {

    private val _state = MutableStateFlow<CircleSosState>(CircleSosState.Loading)
    val state: StateFlow<CircleSosState> = _state.asStateFlow()

    private var watching: kotlinx.coroutines.Job? = null

    fun watch(emergencyId: Long) {
        watching?.cancel()
        watching = viewModelScope.launch {
            while (true) {
                when (val r = read(emergencyId)) {
                    is ApiResult.Success -> _state.value = CircleSosState.Shown(r.data)
                    is ApiResult.Failure -> _state.value = when {
                        r.error == ApiError.NOT_FOUND || r.error == ApiError.FORBIDDEN -> CircleSosState.Gone
                        _state.value is CircleSosState.Shown -> (_state.value as CircleSosState.Shown).copy(stale = true)
                        else -> CircleSosState.Failed
                    }
                }
                val shown = _state.value as? CircleSosState.Shown
                if (shown == null || shown.sos.status != CircleSosStatus.ACTIVE) break
                delay(REFRESH_MS)
            }
        }
    }

    fun stop() {
        watching?.cancel()
        watching = null
    }

    companion object {
        const val REFRESH_MS = 15_000L

        fun factory(container: AppContainer): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                CircleSosViewModel(container.circleInAppRepository::alert) as T
        }
    }
}
