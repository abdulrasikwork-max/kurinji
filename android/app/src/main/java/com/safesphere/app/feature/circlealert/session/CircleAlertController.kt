package com.safesphere.app.feature.circlealert.session

import com.safesphere.app.feature.circlealert.domain.AlertTarget
import com.safesphere.app.feature.circlealert.domain.CircleDelivery
import com.safesphere.app.feature.circlealert.domain.CircleMessageKind
import com.safesphere.app.feature.circlealert.domain.CircleRecipient
import com.safesphere.app.feature.safetycircle.domain.SafetyContact
import java.time.Instant
import kotlinx.coroutines.flow.StateFlow

/**
 * The Safety Circle as the phone handles it itself (Phase 4D; N6: the phone never texts anyone by itself any more, the
 * SERVER does when the person chose it, see SafetyAlertRepository).
 *
 * @param recipients    enabled contacts cached on the phone (texting them by hand works offline)
 * @param deliveries    recent hand-sent attempts (the SMS app was opened), newest last
 * @param pendingReports attempts the server has not recorded yet
 */
data class CircleAlertState(
    val loaded: Boolean = false,
    val recipients: List<CircleRecipient> = emptyList(),
    val deliveries: List<CircleDelivery> = emptyList(),
    val pendingReports: Int = 0
) {
    fun deliveriesFor(target: AlertTarget): List<CircleDelivery> = deliveries.filter { it.target == target }

    override fun toString(): String =
        "CircleAlertState(recipients=${recipients.size}, deliveries=${deliveries.size})"
}

/** Production: [CircleAlertCoordinator]. Tests of screens: a fake. */
interface CircleAlertController {
    val state: StateFlow<CircleAlertState>

    /** The Safety Circle list changed (loaded from the server): keep the offline copy current. */
    fun updateRecipients(contacts: List<SafetyContact>)

    /** Hooks from the SOS screen: the time the hand-sent text names. */
    fun onEmergencyActive(emergencyId: Long, startedAt: Instant)
    fun onEmergencyEnded(emergencyId: Long, resolved: Boolean)

    /** Text for the SMS app when the person sends it themselves (the same allow-list as the server's texts). */
    fun composerText(target: AlertTarget, kind: CircleMessageKind): String

    /** The person opened the SMS app for [recipient]: recorded as COMPOSER_OPENED (we cannot know if they sent it). */
    fun onComposerOpened(target: AlertTarget, kind: CircleMessageKind, recipient: CircleRecipient)

    /** Level 4: "Call 112" opened the dialer during this emergency. Recorded on the server (best effort). */
    fun onDialPromptOpened(emergencyId: Long)
}
