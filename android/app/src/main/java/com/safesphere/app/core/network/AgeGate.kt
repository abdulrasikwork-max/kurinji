package com.safesphere.app.core.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Phase 17A: the ONE age state of the signed-in account on this phone. An account created before V20 has not declared
 * an adult date of birth; the server then refuses every social call with 409 code AGE_NOT_CONFIRMED. Before 17A each
 * social screen showed that refusal on its own and only You → Edit profile could fix it.
 *
 * Fed by [ApiCallHandler] (any refusal with the code), by the check at session start and by the age screen. The app
 * opens the age screen at most once per sign-in by itself ([claimPrompt]); after that the person reaches it from You.
 * Safety features never ask for it.
 */
class AgeGate {

    private val _required = MutableStateFlow(false)

    /** True while the server says this account still has to confirm its age. */
    val required: StateFlow<Boolean> = _required.asStateFlow()

    private var prompted = false

    fun onRefused() {
        _required.value = true
    }

    fun onConfirmed() {
        _required.value = false
    }

    /** True exactly once while the age is required: the caller then opens the age screen. */
    @Synchronized
    fun claimPrompt(): Boolean {
        if (!_required.value || prompted) return false
        prompted = true
        return true
    }

    /** Sign-out or another account: forget everything. */
    @Synchronized
    fun reset() {
        prompted = false
        _required.value = false
    }

    companion object {
        /** The server's code (backend `AgeNotConfirmedException.CODE`). */
        const val CODE = "AGE_NOT_CONFIRMED"
    }
}
