package com.safesphere.app.core.sms

import kotlinx.coroutines.flow.Flow

/** Whether this phone can send an SMS by itself right now. */
enum class SmsAvailability {
    AVAILABLE,

    /** The person has not allowed SEND_SMS (or revoked it). */
    PERMISSION_NEEDED,

    /** No SMS capability (tablet, no telephony): only the SMS app / sharing can be offered, if any. */
    NOT_SUPPORTED
}

/**
 * Honest outcomes. SENT = the phone handed the message to the mobile network; it does NOT mean the contact received
 * it. DELIVERED only when the network's delivery report says so (many networks never send one).
 */
enum class SmsOutcome { SENT, DELIVERED, FAILED }

data class SmsResult(val requestId: String, val outcome: SmsOutcome)

/**
 * The app's only way to send an SMS by itself (Phase 4D). Production: [AndroidSmsSender]. Tests: a fake.
 * Used only when the person switched automatic alerts on. Nothing here logs a number or a message.
 */
interface SmsSender {

    fun availability(): SmsAvailability

    /**
     * Hands one message to the phone's SMS service. Returns false when it could not even be handed over (no
     * permission, no SMS service, platform error): that is a FAILED attempt. Later results arrive on [results].
     */
    fun send(requestId: String, phoneNumber: String, text: String): Boolean

    val results: Flow<SmsResult>
}
