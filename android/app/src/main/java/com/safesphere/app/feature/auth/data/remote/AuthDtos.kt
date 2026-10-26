package com.safesphere.app.feature.auth.data.remote

import com.safesphere.app.feature.auth.domain.AuthUser
import java.time.Instant

// These classes mirror the JSON of the Spring Boot API (see docs/API.md).

/** Phase 13A: [dateOfBirth] (yyyy-MM-dd) is checked for 18+ by the server and stored nowhere. */
data class RegisterRequestDto(val name: String, val email: String, val password: String, val dateOfBirth: String) {
    // Data classes print all fields in toString(). Redact so a stray log line can never leak a password.
    override fun toString() = "RegisterRequestDto(name=$name, email=<hidden>, password=<hidden>, dateOfBirth=<hidden>)"
}

/**
 * Phase 11 C1: [previousRefreshToken] = this phone's refresh token for the SAME account (signing in again after
 * "sign in to sync"): the server ends that old sign-in and hands its running SOS to the new one. Never another account's.
 */
data class LoginRequestDto(val email: String, val password: String, val previousRefreshToken: String? = null) {
    override fun toString() = "LoginRequestDto(email=<hidden>, password=<hidden>, previous=<hidden>)"
}

/** V25: answer of /api/auth/register: no session; [status] is "VERIFICATION_REQUIRED", [email] the normalised address. */
data class RegistrationResponseDto(val status: String?, val email: String?) {
    override fun toString() = "RegistrationResponseDto(status=$status, email=<hidden>)"
}

/** V25: body of /api/auth/verification/resend. The password proves the request comes from the account's owner. */
data class ResendVerificationRequestDto(val email: String, val password: String) {
    override fun toString() = "ResendVerificationRequestDto(<hidden>)"
}

/** V25: {"status": "RECEIVED"} (resend) — the same whatever the account's state. */
data class VerificationStatusDto(val status: String?)

/** Phase 11 C1: body of /api/auth/refresh and /api/auth/sign-out. The token is a credential. */
data class RefreshRequestDto(val refreshToken: String) {
    override fun toString() = "RefreshRequestDto(<hidden>)"
}

/** Phase 10A: [deletionRequestedAt] / [deletionDueAt] (ISO-8601, UTC) while an account deletion is pending. */
data class UserDto(
    val id: Long,
    val name: String,
    val email: String,
    val createdAt: String?,
    val deletionRequestedAt: String? = null,
    val deletionDueAt: String? = null
) {
    fun toDomain(): AuthUser {
        val requested = instantOrNull(deletionRequestedAt)
        val due = instantOrNull(deletionDueAt)
        // Both or neither (as on the server); a half-readable pair is shown as "pending, date unknown" rather than lost.
        return AuthUser(
            id = id, name = name, email = email,
            deletionRequestedAt = requested,
            deletionDueAt = due ?: if (deletionDueAt != null) Instant.EPOCH else null
        )
    }

    override fun toString() = "UserDto(id=$id, name=<hidden>, email=<hidden>, deletionDueAt=$deletionDueAt)"

    private companion object {
        fun instantOrNull(value: String?): Instant? = value?.let { runCatching { Instant.parse(it) }.getOrNull() }
    }
}

/** Phase 11 C1: [refreshToken] is set when the answer starts or rotates a device session; null = keep the one you have. */
data class AuthResponseDto(
    val accessToken: String,
    val tokenType: String?,
    val expiresInSeconds: Long?,
    val user: UserDto,
    val refreshToken: String? = null
) {
    // The tokens are credentials: keep them out of logs too.
    override fun toString() = "AuthResponseDto(accessToken=<hidden>, refreshToken=<hidden>, user=$user)"
}
