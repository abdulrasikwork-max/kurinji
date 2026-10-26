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
    /** 403 + code EMAIL_NOT_VERIFIED (V25): the right password, but the e-mail address is not confirmed yet. */
    EMAIL_NOT_VERIFIED
}

/**
 * V25: a new account waiting for its owner to open the link e-mailed to [email] (the server's normalised address).
 * Nothing is signed in and nothing is stored on the phone.
 */
data class VerificationPending(val email: String) {
    override fun toString() = "VerificationPending(email=<hidden>)"
}

sealed interface AuthResult<out T> {
    data class Success<T>(val data: T) : AuthResult<T>
    data class Failure(val error: AuthError) : AuthResult<Nothing>
}
