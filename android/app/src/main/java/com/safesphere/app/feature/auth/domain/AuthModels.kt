package com.safesphere.app.feature.auth.domain

/** The signed-in person, as the rest of the app sees them. No password, ever. */
data class AuthUser(val id: Long, val name: String, val email: String)

/** What is remembered on the device between app launches: the token and basic profile data. */
data class AuthSession(val token: String, val user: AuthUser)

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
