package com.safesphere.app.feature.emergency.ui

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.safesphere.app.AppContainer
import com.safesphere.app.R
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.core.network.isSessionExpired
import com.safesphere.app.feature.circlealert.domain.AlertChannel
import com.safesphere.app.feature.circlealert.domain.CircleAlertRepository
import com.safesphere.app.feature.circlealert.domain.CircleMessageKind
import com.safesphere.app.feature.circlealert.domain.ContextAlert
import com.safesphere.app.feature.circlealert.domain.ContextEscalation
import com.safesphere.app.feature.circlealert.domain.DeliveryStatus
import com.safesphere.app.feature.circlealert.domain.EmergencyContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface EmergencyDetailUiState {
    data object Loading : EmergencyDetailUiState
    data class Loaded(val context: EmergencyContext) : EmergencyDetailUiState
    data class Failed(val failure: ApiResult.Failure) : EmergencyDetailUiState
}

/** One emergency's Emergency Context Packet (OWNER projection), loaded on demand from the server. */
class EmergencyDetailViewModel(
    private val repository: CircleAlertRepository,
    private val emergencyId: Long
) : ViewModel() {

    private val _uiState = MutableStateFlow<EmergencyDetailUiState>(EmergencyDetailUiState.Loading)
    val uiState: StateFlow<EmergencyDetailUiState> = _uiState.asStateFlow()

    private val _sessionExpired = MutableStateFlow(false)
    val sessionExpired: StateFlow<Boolean> = _sessionExpired.asStateFlow()

    init {
        load()
    }

    fun load() {
        _uiState.value = EmergencyDetailUiState.Loading
        viewModelScope.launch {
            _uiState.value = when (val result = repository.ownerContext(emergencyId)) {
                is ApiResult.Success -> EmergencyDetailUiState.Loaded(result.data)
                is ApiResult.Failure -> {
                    if (result.isSessionExpired()) _sessionExpired.value = true
                    EmergencyDetailUiState.Failed(result)
                }
            }
        }
    }

    fun onSessionExpiredHandled() {
        _sessionExpired.value = false
    }

    companion object {
        fun factory(container: AppContainer, emergencyId: Long) = viewModelFactory {
            initializer { EmergencyDetailViewModel(container.circleAlertRepository, emergencyId) }
        }
    }
}

// ---- pure text mapping (unit-tested) ------------------------------------------------------------

@StringRes
fun escalationReasonLabel(reason: String): Int = when (reason) {
    "CHECKED_IN" -> R.string.reason_checked_in
    "CHECK_IN_MISSED" -> R.string.reason_check_in_missed
    "NO_RESPONSE" -> R.string.reason_no_response
    "SOS_CONFIRMED" -> R.string.reason_sos_confirmed
    "ROUTE_DEVIATION" -> R.string.reason_route_deviation
    "UNEXPECTED_STOP" -> R.string.reason_unexpected_stop
    "DIAL_PROMPT_OPENED" -> R.string.reason_dial_prompt
    else -> R.string.reason_other
}

@StringRes
fun alertKindLabel(kind: CircleMessageKind?): Int = when (kind) {
    CircleMessageKind.WALK_NO_RESPONSE -> R.string.alert_kind_walk_no_response
    CircleMessageKind.SOS_STARTED -> R.string.alert_kind_sos_started
    CircleMessageKind.SOS_LOCATION -> R.string.alert_kind_sos_location
    CircleMessageKind.ALL_CLEAR -> R.string.alert_kind_all_clear
    CircleMessageKind.SOS_RESOLVED -> R.string.alert_kind_sos_resolved
    CircleMessageKind.SOS_CANCELLED -> R.string.alert_kind_sos_cancelled
    null -> R.string.reason_other
}

/** Honest status words: "sent" never means received; "SMS app opened" never means sent. */
@StringRes
fun alertStatusLabel(status: DeliveryStatus?, channel: AlertChannel?): Int = when (status) {
    DeliveryStatus.DELIVERED -> R.string.alert_status_delivered
    DeliveryStatus.SENT, DeliveryStatus.SENDING ->
        if (channel == AlertChannel.SMS_AUTO) R.string.alert_status_sent_auto else R.string.alert_status_sent
    DeliveryStatus.FAILED -> R.string.alert_status_failed
    DeliveryStatus.COMPOSER_OPENED -> R.string.alert_status_composer
    null -> R.string.reason_other
}

/** Sorted, readable timeline entries (escalations and alerts mixed in time order). */
sealed interface DetailEntry {
    val at: java.time.Instant

    data class Escalation(val item: ContextEscalation) : DetailEntry {
        override val at get() = item.at
    }

    data class Alert(val item: ContextAlert) : DetailEntry {
        override val at get() = item.at
    }
}

fun detailEntriesFor(context: EmergencyContext): List<DetailEntry> =
    (context.escalations.map { DetailEntry.Escalation(it) } + context.alerts.map { DetailEntry.Alert(it) }).sortedBy { it.at }
