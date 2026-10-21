package com.safesphere.app.feature.safetycircle.ui

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.safesphere.app.AppContainer
import com.safesphere.app.R
import com.safesphere.app.core.location.GeoPoint
import com.safesphere.app.core.sms.SmsAvailability
import com.safesphere.app.feature.circlealert.domain.CircleMessageKind
import com.safesphere.app.feature.circlealert.domain.CircleMessages
import com.safesphere.app.feature.circlealert.session.CircleAlertController
import com.safesphere.app.feature.circlealert.session.CircleAlertState
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** What the "Automatic SMS alerts" card shows. */
data class AutoSmsUi(
    val enabled: Boolean = false,
    @StringRes val status: Int = R.string.auto_sms_status_off,
    val recipientCount: Int = 0,
    val preview: String = ""
)

/** What to do when the person turns the switch on. */
enum class AutoSmsEnableAction { ENABLE, ASK_PERMISSION, NOT_SUPPORTED }

/** Pure mapping of the alert state to the card's honest status line. Unit-tested. */
@StringRes
fun autoSmsStatusFor(state: CircleAlertState): Int = when {
    state.availability == SmsAvailability.NOT_SUPPORTED -> R.string.auto_sms_status_not_supported
    !state.autoSms -> R.string.auto_sms_status_off
    state.availability == SmsAvailability.PERMISSION_NEEDED -> R.string.auto_sms_status_permission
    state.recipients.isEmpty() -> R.string.auto_sms_status_no_contacts
    else -> R.string.auto_sms_status_on
}

fun enableActionFor(availability: SmsAvailability): AutoSmsEnableAction = when (availability) {
    SmsAvailability.AVAILABLE -> AutoSmsEnableAction.ENABLE
    SmsAvailability.PERMISSION_NEEDED -> AutoSmsEnableAction.ASK_PERMISSION
    SmsAvailability.NOT_SUPPORTED -> AutoSmsEnableAction.NOT_SUPPORTED
}

/** The opt-in card on the Safety Circle screen (Phase 4D). All sending lives in [CircleAlertController]. */
class AutoSmsViewModel(
    private val controller: CircleAlertController,
    personName: Flow<String?>
) : ViewModel() {

    val ui: StateFlow<AutoSmsUi> = combine(controller.state, personName) { state, name ->
        AutoSmsUi(
            enabled = state.autoSms,
            status = autoSmsStatusFor(state),
            recipientCount = state.recipients.size,
            // An example of the real message format, with an example place, so the person knows what is shared.
            preview = CircleMessages.compose(
                CircleMessageKind.SOS_STARTED, name.orEmpty(), Instant.now(), ZoneId.systemDefault(),
                EXAMPLE_PLACE, Instant.now(), automatic = true
            )
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AutoSmsUi())

    /** The switch was turned on: enable now, or the screen first asks for SEND_SMS (in context, with a reason). */
    fun onSwitchOn(): AutoSmsEnableAction {
        controller.refreshAvailability()
        val action = enableActionFor(controller.state.value.availability)
        if (action == AutoSmsEnableAction.ENABLE) controller.setAutoSms(true)
        return action
    }

    fun onSwitchOff() = controller.setAutoSms(false)

    fun onPermissionResult(granted: Boolean) {
        controller.refreshAvailability()
        if (granted) controller.setAutoSms(true)
    }

    fun onScreenVisible() = controller.refreshAvailability()

    companion object {
        private val EXAMPLE_PLACE = GeoPoint(12.97160, 77.59460)

        fun factory(container: AppContainer) = viewModelFactory {
            initializer { AutoSmsViewModel(container.circleAlerts, container.authRepository.currentUser.map { it?.name }) }
        }
    }
}
