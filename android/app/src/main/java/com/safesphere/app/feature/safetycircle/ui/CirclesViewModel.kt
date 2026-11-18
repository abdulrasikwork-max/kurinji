package com.safesphere.app.feature.safetycircle.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.safesphere.app.AppContainer
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.safetycircle.data.CircleRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CirclesState(
    val loading: Boolean = true,
    val requests: List<CircleRequest> = emptyList(),
    val circles: List<CircleRequest> = emptyList(),
    val busy: Boolean = false,
    val problem: Problem? = null
) {
    enum class Problem { LOAD, ACTION }
}

/**
 * N7 (D36): requests to join someone's Safety Circle in the app (Accept / Not now / Don't ask again) and the circles
 * the person is in (Leave). The owner is told nothing about a decline. Nothing is stored on the phone.
 */
class CirclesViewModel(
    private val requests: suspend () -> ApiResult<List<CircleRequest>>,
    private val circlesIn: suspend () -> ApiResult<List<CircleRequest>>,
    private val accept: suspend (String) -> ApiResult<Unit>,
    private val decline: suspend (String, Boolean) -> ApiResult<Unit>,
    private val leave: suspend (String) -> ApiResult<Unit>,
    private val onAccepted: () -> Unit
) : ViewModel() {

    private val _state = MutableStateFlow(CirclesState())
    val state: StateFlow<CirclesState> = _state.asStateFlow()

    fun load() {
        viewModelScope.launch {
            val r = requests()
            val c = circlesIn()
            if (r is ApiResult.Success && c is ApiResult.Success) {
                _state.update { it.copy(loading = false, requests = r.data, circles = c.data, problem = null) }
            } else {
                _state.update { it.copy(loading = false, problem = CirclesState.Problem.LOAD) }
            }
        }
    }

    fun onAccept(id: String) = act({ accept(id) }) { onAccepted() }

    fun onNotNow(id: String) = act({ decline(id, false) })

    fun onDontAskAgain(id: String) = act({ decline(id, true) })

    fun onLeave(id: String) = act({ leave(id) })

    private fun act(call: suspend () -> ApiResult<Unit>, after: () -> Unit = {}) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, problem = null) }
        viewModelScope.launch {
            when (call()) {
                is ApiResult.Success -> {
                    after()
                    _state.update { it.copy(busy = false) }
                    load()
                }
                is ApiResult.Failure -> _state.update { it.copy(busy = false, problem = CirclesState.Problem.ACTION) }
            }
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val repo = container.circleInAppRepository
                return CirclesViewModel(repo::requests, repo::circlesIn, repo::accept, repo::decline, repo::leave,
                    onAccepted = { container.notificationAsk.offer() }) as T
            }
        }
    }
}
