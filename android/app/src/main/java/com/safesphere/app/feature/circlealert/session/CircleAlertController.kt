package com.safesphere.app.feature.circlealert.session

import com.safesphere.app.core.sms.SmsAvailability
import com.safesphere.app.feature.circlealert.domain.AlertTarget
import com.safesphere.app.feature.circlealert.domain.CircleDelivery
import com.safesphere.app.feature.circlealert.domain.CircleMessageKind
import com.safesphere.app.feature.circlealert.domain.CircleRecipient
import com.safesphere.app.feature.safetycircle.domain.SafetyContact
import java.time.Instant
import kotlinx.coroutines.flow.StateFlow

/**
 * The Safety Circle communication as screens see it (Phase 4D).
 *
 * @param autoSms       the person's opt-in for automatic SMS (off by default)
 * @param availability  whether this phone can send an SMS by itself right now
 * @param recipients    enabled contacts cached on the phone (works offline)
 * @param deliveries    recent attempts, newest last
 * @param pendingReports attempts the server has not recorded yet
 */
data class CircleAlertState(
    val loaded: Boolean = false,
    val autoSms: Boolean = false,
    val availability: SmsAvailability = SmsAvailability.NOT_SUPPORTED,
    val recipients: List<CircleRecipient> = emptyList(),
    val deliveries: List<CircleDelivery> = emptyList(),
    val pendingReports: Int = 0
) {
    /** Automatic alerts will really be sent: opted in, SMS possible, and someone to send to. */
    val autoActive: Boolean
        get() = autoSms && availability == SmsAvailability.AVAILABLE && recipients.isNotEmpty()

    fun deliveriesFor(target: AlertTarget): List<CircleDelivery> = deliveries.filter { it.target == target }

    override fun toString(): String =
        "CircleAlertState(auto=$autoSms, availability=$availability, recipients=${recipients.size}, deliveries=${deliveries.size})"
}

/** Production: [CircleAlertCoordinator]. Tests of screens: a fake. */
interface CircleAlertController {
    val state: StateFlow<CircleAlertState>

    /** The switch on the Safety Circle screen (the screen asks for SEND_SMS first). */
    fun setAutoSms(enabled: Boolean)

    /** Re-checks SEND_SMS / SMS support (after a permission dialog or Settings). */
    fun refreshAvailability()

    /** The Safety Circle list changed (loaded from the server): keep the offline copy current. */
    fun updateRecipients(contacts: List<SafetyContact>)

    /** Hooks from the SOS screen (only it knows how an emergency ended). */
    fun onEmergencyActive(emergencyId: Long, startedAt: Instant)
    fun onEmergencyEnded(emergencyId: Long, resolved: Boolean)

    /** Text for the SMS app when the person sends it themselves (not automatic, same allow-list). */
    fun composerText(target: AlertTarget, kind: CircleMessageKind): String

    /** The person opened the SMS app for [recipient]: recorded as COMPOSER_OPENED (we cannot know if they sent it). */
    fun onComposerOpened(target: AlertTarget, kind: CircleMessageKind, recipient: CircleRecipient)

    /** Level 4: "Call 112" opened the dialer during this emergency. Recorded on the server (best effort). */
    fun onDialPromptOpened(emergencyId: Long)
}
