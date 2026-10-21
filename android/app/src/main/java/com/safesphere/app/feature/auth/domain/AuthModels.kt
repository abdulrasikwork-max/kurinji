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

/** What is remembered on the device between app launches: the token and basic profile data. */
data class AuthSession(val token: String, val user: AuthUser) {
    override fun toString() = "AuthSession(token=<hidden>, user=$user)"
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
    TOO_MANY_ATTEMPTS
}

sealed interface AuthResult<out T> {
    data class Success<T>(val data: T) : AuthResult<T>
    data class Failure(val error: AuthError) : AuthResult<Nothing>
}
