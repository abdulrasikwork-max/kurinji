package com.safesphere.app.feature.auth.domain

import java.time.Instant

/**
 * The signed-in person, as the rest of the app sees them. No password, ever (since Phase N2 there is none at all).
 * [name] is the real name (N-23: the one name IRL shows). [phoneHint] = the account's number with only the last 4
 * digits ("••••••3210", where confirmation codes go); null for a session saved before N2.
 * Phase 10A: [deletionRequestedAt] / [deletionDueAt] are set while an account deletion is pending (Home and the
 * Account screen show a banner until it is cancelled or carried out).
 */
data class AuthUser(
    val id: Long,
    val name: String,
    val phoneHint: String? = null,
    val deletionRequestedAt: Instant? = null,
    val deletionDueAt: Instant? = null
) {
    val deletionPending: Boolean get() = deletionDueAt != null

    // The name and number are personal data: keep them out of stray log lines.
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
    VALIDATION,
    UNAUTHORIZED,
    NO_CONNECTION,
    SERVER,
    UNKNOWN,
    /** 429 (Phase 7): too many new accounts from this network. */
    TOO_MANY_ATTEMPTS,

    // ---- Phase 17B / N2: the phone code (the server's code in ApiError.code) ----
    /** Not a 10-digit Indian mobile number. */
    PHONE_INVALID,
    /** Two sign-ups for one number at the same moment: this one lost (enter the number again to sign in). */
    PHONE_IN_USE,
    /** The code request ended (30 min, or replaced): enter the number again for a new one. */
    CHALLENGE_INVALID,
    /** N2: the sign-up ticket was used or ran out: enter the number again. */
    TICKET_INVALID,
    /** N2: the right code, but this number's account was switched off by the IRL team. */
    ACCOUNT_UNAVAILABLE,
    /** A wrong code; the server's words say how many tries are left. */
    OTP_INVALID,
    OTP_EXPIRED,
    /** Too many wrong codes: ask for a new one. */
    OTP_LOCKED,
    /** Too soon or too many codes asked for; the server's words say how long to wait. */
    OTP_WAIT
}

/**
 * Phase 17B / N2: a code on its way to a number. [token] is a credential for this code only: kept in memory
 * ([PendingVerification]), never stored or logged. [phoneHint] shows only the last 4 digits. [codeSent]: a code is
 * waiting at that number (false = the SMS could not be sent; ask again).
 */
data class PhoneChallenge(
    val token: String,
    val phoneHint: String?,
    val codeSent: Boolean,
    val resendInSeconds: Long,
    val codeExpiresInSeconds: Long
) {
    override fun toString() = "PhoneChallenge(token=<hidden>, codeSent=$codeSent)"
}

/**
 * Phase N2: the right code for a number that has no account yet. [token] is a one-use credential (30 min) that, with a
 * real name and an adult date of birth, creates the account. Memory only ([PendingVerification]); never stored.
 */
data class SignUpTicket(val token: String, val phoneHint: String?, val expiresInSeconds: Long) {
    override fun toString() = "SignUpTicket(<hidden>)"
}

/** Phase N2: what the right code led to. */
sealed interface SignInResult {
    /** The number's account is signed in on this phone (saved like any sign-in). */
    data class SignedIn(val user: AuthUser) : SignInResult

    /** The number has no account yet: the name-and-age screen comes next. */
    data class SignUpNeeded(val ticket: SignUpTicket) : SignInResult
}

/** [message] (Phase 17B): the server's own words, only for errors whose text depends on the case (tries left, wait). */
sealed interface AuthResult<out T> {
    data class Success<T>(val data: T) : AuthResult<T>
    data class Failure(val error: AuthError, val message: String? = null) : AuthResult<Nothing>
}
