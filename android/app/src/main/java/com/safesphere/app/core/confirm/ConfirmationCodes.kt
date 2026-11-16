package com.safesphere.app.core.confirm

import com.safesphere.app.core.network.ApiResult

/**
 * Phase N2 (decision N-7): sensitive actions are confirmed with a fresh code texted to the signed-in person's own
 * number (it replaced the account password): deleting the account and cancelling that, signing out the other phones,
 * ending an SOS that another phone started. Kept in `core` so the safety screens and the account screen share one
 * contract without depending on each other.
 *
 * Honest limit (viva): the code goes to the same SIM as sign-in, so it protects against a stolen session or a phone
 * left signed in elsewhere, not against someone holding the SIM.
 */
interface ConfirmationCodes {
    /** POST /api/users/me/confirmation: texts a code; the answer is what the code dialog shows. */
    suspend fun request(): ApiResult<CodeRequest>
}

/**
 * A code on its way. [challenge] is a credential for this one code (memory only, never stored or logged);
 * [phoneHint] shows the last 4 digits; [codeSent] = false when the SMS could not be sent (ask again).
 */
data class CodeRequest(val challenge: String, val phoneHint: String?, val codeSent: Boolean, val resendInSeconds: Long) {
    override fun toString() = "CodeRequest(<hidden>, codeSent=$codeSent)"
}

/** The challenge and the code the person typed: sent with the one action they confirm. */
data class Confirmation(val challenge: String, val code: String) {
    override fun toString() = "Confirmation(<hidden>)"

    companion object {
        /** The code as typed, digits only; null unless it is exactly 6 digits (then nothing is sent). */
        fun of(request: CodeRequest, typed: String?): Confirmation? {
            val digits = typed.orEmpty().filter { it.isDigit() }
            return if (digits.length == CODE_LENGTH) Confirmation(request.challenge, digits) else null
        }

        const val CODE_LENGTH = 6
    }
}

/** Used where no account is signed in (tests, previews): asking for a code always fails, so nothing is confirmed. */
object NoConfirmationCodes : ConfirmationCodes {
    override suspend fun request(): ApiResult<CodeRequest> =
        ApiResult.Failure(com.safesphere.app.core.network.ApiError.UNAUTHORIZED)
}
