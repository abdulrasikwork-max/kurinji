package com.safesphere.app.feature.auth.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Phase 17B: the one phone verification in progress on this phone, handed from sign-up or sign-in to the code screen.
 * Memory only (the challenge token is a credential): if the app is closed, the person signs in again and the server
 * gives a new token for the same verification (a code already sent keeps working). Cleared once verified.
 */
class PendingVerification {

    private val _challenge = MutableStateFlow<PhoneChallenge?>(null)
    val challenge: StateFlow<PhoneChallenge?> = _challenge.asStateFlow()

    fun begin(challenge: PhoneChallenge) {
        _challenge.value = challenge
    }

    /** A newer answer for the same verification (resend, another number). */
    fun update(challenge: PhoneChallenge) {
        _challenge.value = challenge
    }

    fun clear() {
        _challenge.value = null
    }
}
