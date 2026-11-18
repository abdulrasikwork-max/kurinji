package com.safesphere.app.feature.settings.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.safesphere.app.AppContainer
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.core.push.PushSwitches
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the Notifications screen shows. [switches] null = not loaded (yet, or it failed: [problem]). */
data class NotificationSettingsState(
    val configured: Boolean,
    val switches: PushSwitches? = null,
    val staff: Boolean = false,
    val showContent: Boolean = false,
    val quietAtNight: Boolean = true,
    val saving: Boolean = false,
    val problem: Problem? = null
) {
    enum class Problem { LOAD, SAVE }
}

/**
 * N7 (D31): the server's switches (off = IRL does not send) and the two choices kept on this phone. A failed save puts
 * the switch back (the screen never shows a choice the server did not take).
 */
class NotificationSettingsViewModel(
    private val load: suspend () -> ApiResult<PushSwitches>,
    private val save: suspend (PushSwitches) -> ApiResult<PushSwitches>,
    private val isStaff: suspend () -> Boolean,
    private val local: LocalChoices,
    configured: Boolean
) : ViewModel() {

    interface LocalChoices {
        suspend fun showContent(): Boolean
        suspend fun quietAtNight(): Boolean
        suspend fun setShowContent(value: Boolean)
        suspend fun setQuietAtNight(value: Boolean)
    }

    private val _state = MutableStateFlow(NotificationSettingsState(configured = configured))
    val state: StateFlow<NotificationSettingsState> = _state.asStateFlow()

    fun start() {
        viewModelScope.launch {
            _state.update { it.copy(showContent = local.showContent(), quietAtNight = local.quietAtNight()) }
            if (!_state.value.configured) return@launch
            val staff = runCatching { isStaff() }.getOrDefault(false)
            when (val result = load()) {
                is ApiResult.Success -> _state.update { it.copy(switches = result.data, staff = staff, problem = null) }
                is ApiResult.Failure -> _state.update { it.copy(problem = NotificationSettingsState.Problem.LOAD) }
            }
        }
    }

    fun change(transform: (PushSwitches) -> PushSwitches) {
        val before = _state.value.switches ?: return
        if (_state.value.saving) return
        val wanted = transform(before)
        if (wanted == before) return
        _state.update { it.copy(switches = wanted, saving = true, problem = null) }
        viewModelScope.launch {
            when (val result = save(wanted)) {
                is ApiResult.Success -> _state.update { it.copy(switches = result.data, saving = false) }
                is ApiResult.Failure -> _state.update {
                    it.copy(switches = before, saving = false, problem = NotificationSettingsState.Problem.SAVE)
                }
            }
        }
    }

    fun setShowContent(value: Boolean) {
        _state.update { it.copy(showContent = value) }
        viewModelScope.launch { local.setShowContent(value) }
    }

    fun setQuietAtNight(value: Boolean) {
        _state.update { it.copy(quietAtNight = value) }
        viewModelScope.launch { local.setQuietAtNight(value) }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val prefs = container.pushPreferences
                return NotificationSettingsViewModel(
                    load = { container.pushRepository.switches() },
                    save = { container.pushRepository.change(it) },
                    isStaff = {
                        (container.moderationRepository.access() as? ApiResult.Success)?.data?.canModerate == true
                    },
                    local = object : LocalChoices {
                        override suspend fun showContent() = prefs.showContent.first()
                        override suspend fun quietAtNight() = prefs.quietAtNight.first()
                        override suspend fun setShowContent(value: Boolean) = prefs.setShowContent(value)
                        override suspend fun setQuietAtNight(value: Boolean) = prefs.setQuietAtNight(value)
                    },
                    configured = container.pushConfigured
                ) as T
            }
        }
    }
}
