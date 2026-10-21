package com.safesphere.app.feature.auth.data.remote

import com.safesphere.app.feature.auth.domain.AuthUser
import java.time.Instant

// These classes mirror the JSON of the Spring Boot API (see docs/API.md).

data class RegisterRequestDto(val name: String, val email: String, val password: String) {
    // Data classes print all fields in toString(). Redact so a stray log line can never leak a password.
    override fun toString() = "RegisterRequestDto(name=$name, email=<hidden>, password=<hidden>)"
}

data class LoginRequestDto(val email: String, val password: String) {
    override fun toString() = "LoginRequestDto(email=<hidden>, password=<hidden>)"
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

data class AuthResponseDto(
    val accessToken: String,
    val tokenType: String?,
    val expiresInSeconds: Long?,
    val user: UserDto
) {
    // The token is a credential: keep it out of logs too.
    override fun toString() = "AuthResponseDto(accessToken=<hidden>, user=$user)"
}
