package com.safesphere.app.feature.circlealert.domain

import java.time.Instant

/** What a Safety Circle contact is told. Same names as the backend (circle_alerts.kind). */
enum class CircleMessageKind {
    WALK_NO_RESPONSE, SOS_STARTED, SOS_LOCATION, ALL_CLEAR, SOS_RESOLVED, SOS_CANCELLED;

    val isForWalk: Boolean get() = this == WALK_NO_RESPONSE || this == ALL_CLEAR
}

/** Which record an alert belongs to: a Safe Walk (journey) or an emergency. */
sealed interface AlertTarget {
    val id: Long

    data class Walk(override val id: Long) : AlertTarget
    data class Emergency(override val id: Long) : AlertTarget
}

/** One enabled Safety Circle contact, as cached on the phone for offline SMS. */
data class CircleRecipient(val contactId: Long, val name: String, val phoneNumber: String) {
    /** Phone numbers never go to logs or crash reports. */
    override fun toString(): String = "CircleRecipient(id=$contactId, phone=<redacted>)"
}

enum class AlertChannel { SMS_AUTO, SMS_COMPOSER }

/** Honest per-contact state of one alert. SENDING = handed to the phone, no result yet. */
enum class DeliveryStatus { SENDING, SENT, DELIVERED, FAILED, COMPOSER_OPENED }

/**
 * One attempt to tell one contact something. [clientRef] identifies it on the server (re-sent reports are harmless,
 * status only moves forward). Holds no phone number and no message text.
 */
data class CircleDelivery(
    val clientRef: String,
    val target: AlertTarget,
    val kind: CircleMessageKind,
    val contactId: Long?,
    val contactName: String?,
    val channel: AlertChannel,
    val status: DeliveryStatus,
    val at: Instant
)
