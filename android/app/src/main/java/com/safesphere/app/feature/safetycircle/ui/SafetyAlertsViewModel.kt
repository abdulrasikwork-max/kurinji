package com.safesphere.app.feature.safetycircle.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.safesphere.app.AppContainer
import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.circlealert.domain.AlertSettings
import com.safesphere.app.feature.circlealert.domain.SafetyAlertRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * N6 (D20): the person's one explicit choice whether IRL's server texts their Safety Circle in an SOS, with the exact
 * text shown first (no preselection: nothing is sent until they choose), what happens per contact, and "Test my
 * alerts" (a text to their own number). Everything comes from the server; nothing is stored on the phone.
 *
 * @param settings  null until the first answer
 * @param failed    the last read or change did not reach the server
 * @param busy      a change or a test is on its way (buttons wait)
 * @param testLimit "once a day" was hit
 */
data class SafetyAlertsUi(
    val settings: AlertSettings? = null,
    val failed: Boolean = false,
    val busy: Boolean = false,
    val testLimit: Boolean = false
)

class SafetyAlertsViewModel(private val repository: SafetyAlertRepository) : ViewModel() {

    private val _ui = MutableStateFlow(SafetyAlertsUi())
    val ui: StateFlow<SafetyAlertsUi> = _ui.asStateFlow()

    /** Called whenever the screen becomes visible (contacts may have changed: the per-contact lines follow them). */
    fun load() {
        viewModelScope.launch { apply(repository.settings()) }
    }

    fun choose(on: Boolean) {
        if (_ui.value.busy) return
        _ui.value = _ui.value.copy(busy = true)
        viewModelScope.launch { apply(repository.choose(on)) }
    }

    fun test() {
        if (_ui.value.busy) return
        _ui.value = _ui.value.copy(busy = true, testLimit = false)
        viewModelScope.launch {
            val result = repository.test()
            if (result is ApiResult.Failure && result.error == ApiError.TOO_MANY_ATTEMPTS) {
                _ui.value = _ui.value.copy(busy = false, testLimit = true)
            } else {
                apply(result)
            }
        }
    }

    private fun apply(result: ApiResult<AlertSettings>) {
        _ui.value = when (result) {
            is ApiResult.Success -> _ui.value.copy(settings = result.data, failed = false, busy = false)
            is ApiResult.Failure -> _ui.value.copy(failed = true, busy = false)
        }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { SafetyAlertsViewModel(container.safetyAlertRepository) }
        }
    }
}
