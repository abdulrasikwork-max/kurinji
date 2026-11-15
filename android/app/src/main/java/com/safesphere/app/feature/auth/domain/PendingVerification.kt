package com.safesphere.app.feature.auth.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Phase 17B / N2: the one sign-in in progress on this phone, handed from screen to screen: the code on its way
 * ([challenge], from the number screen to the code screen), then, for a number without an account, the sign-up
 * [ticket] (from the code screen to the name-and-age screen). Memory only (both are credentials): if the app is closed,
 * the person enters the number again; a code already sent keeps working (the server gives a new token for it).
 */
class PendingVerification {

    private val _challenge = MutableStateFlow<PhoneChallenge?>(null)
    val challenge: StateFlow<PhoneChallenge?> = _challenge.asStateFlow()

    private val _ticket = MutableStateFlow<SignUpTicket?>(null)
    val ticket: StateFlow<SignUpTicket?> = _ticket.asStateFlow()

    fun begin(challenge: PhoneChallenge) {
        _ticket.value = null
        _challenge.value = challenge
    }

    /** A newer answer for the same sign-in (resend, another number). */
    fun update(challenge: PhoneChallenge) {
        _challenge.value = challenge
    }

    /** N2: the code was right and the number has no account: the code is used up, the ticket replaces it. */
    fun signUpWith(ticket: SignUpTicket) {
        _challenge.value = null
        _ticket.value = ticket
    }

    fun clear() {
        _challenge.value = null
        _ticket.value = null
    }
}
