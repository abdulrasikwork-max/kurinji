package com.safesphere.app.feature.auth.domain

import java.time.Instant

/**
 * The signed-in person, as the rest of the app sees them. No password, ever.
 * Phase 10A: [deletionRequestedAt] / [deletionDueAt] are set while an account deletion is pending (Home and the
 * Account screen show a banner until it is cancelled or carried out).
 */
data class AuthUser(
    val id: Long,
    val name: String,
    val email: String,
    val deletionRequestedAt: Instant? = null,
    val deletionDueAt: Instant? = null
) {
    val deletionPending: Boolean get() = deletionDueAt != null

    // The e-mail is personal data: keep it out of stray log lines.
    override fun toString() = "AuthUser(id=$id, deletionPending=$deletionPending)"
}

/**
 * What is remembered on the device between app launches: the token and basic profile data.
 *
 * Phase 11 C1: [refreshToken] gets a new access token after the 24 h one expired, even after weeks without the app
 * (stored encrypted with a Keystore key; null for a sign-in from before C1 until it is adopted). [needsSignIn]: the
 * server no longer accepts this sign-in (refresh refused, or this phone's session ended). The person is NOT signed out:
 * the account's safety data on this phone (a running SOS, unsent endings, the Safety Circle copy for offline SMS, a
 * Safe Walk) stays, the SOS keeps working on the phone, and only server sync waits until they sign in again.
 */
data class AuthSession(
    val token: String,
    val user: AuthUser,
    val refreshToken: String? = null,
    val needsSignIn: Boolean = false
) {
    override fun toString() = "AuthSession(token=<hidden>, refresh=${if (refreshToken != null) "<hidden>" else "none"}, " +
        "needsSignIn=$needsSignIn, user=$user)"
}

/** Everything that can go wrong, in app terms. The UI turns each one into a friendly message. */
enum class AuthError {
    INVALID_CREDENTIALS,
    EMAIL_ALREADY_USED,
    VALIDATION,
    UNAUTHORIZED,
    NO_CONNECTION,
    SERVER,
    UNKNOWN,
    /** 429 (Phase 7): too many failed sign-ins for this account or network, or too many new accounts. */
    TOO_MANY_ATTEMPTS,

    // ---- Phase 17B: phone verification (the server's code in ApiError.code) ----
    /** 403: the right password, but this account has no verified phone yet: the code screen comes next. */
    PHONE_NOT_VERIFIED,
    /** Not a 10-digit Indian mobile number. */
    PHONE_INVALID,
    /** The account has no number yet: enter one first. */
    PHONE_REQUIRED,
    /** Another account already verified this number. */
    PHONE_IN_USE,
    /** Already verified: just sign in. */
    PHONE_ALREADY_VERIFIED,
    /** The code request ended (30 min, or replaced): sign in again for a new one. */
    CHALLENGE_INVALID,
    /** A wrong code; the server's words say how many tries are left. */
    OTP_INVALID,
    OTP_EXPIRED,
    /** Too many wrong codes: ask for a new one. */
    OTP_LOCKED,
    /** Too soon or too many codes asked for; the server's words say how long to wait. */
    OTP_WAIT
}

/**
 * Phase 17B: a phone verification in progress. [token] is a credential for this verification only: kept in memory
 * ([PendingVerification]), never stored or logged. [phoneHint] shows only the last 4 digits. [needsPhone]: the account
 * has no number yet. [codeSent]: a code is waiting at that number (false = the SMS could not be sent; ask again).
 */
data class PhoneChallenge(
    val token: String,
    val phoneHint: String?,
    val needsPhone: Boolean,
    val codeSent: Boolean,
    val resendInSeconds: Long,
    val codeExpiresInSeconds: Long
) {
    override fun toString() = "PhoneChallenge(token=<hidden>, needsPhone=$needsPhone, codeSent=$codeSent)"
}

/** [message] (Phase 17B): the server's own words, only for errors whose text depends on the case (tries left, wait). */
sealed interface AuthResult<out T> {
    data class Success<T>(val data: T) : AuthResult<T>
    data class Failure(val error: AuthError, val message: String? = null) : AuthResult<Nothing>
}
