package com.safesphere.app.feature.auth.data.remote

import com.safesphere.app.feature.auth.domain.AuthUser
import java.time.Instant

// These classes mirror the JSON of the Spring Boot API (see docs/API.md).

/**
 * Phase 13A: [dateOfBirth] (yyyy-MM-dd) is checked for 18+ by the server and stored nowhere. Phase 17B: [phone] is the
 * mobile number the code goes to.
 */
data class RegisterRequestDto(
    val name: String,
    val email: String,
    val password: String,
    val dateOfBirth: String,
    val phone: String
) {
    // Data classes print all fields in toString(). Redact so a stray log line can never leak a password.
    override fun toString() =
        "RegisterRequestDto(name=$name, email=<hidden>, password=<hidden>, dateOfBirth=<hidden>, phone=<hidden>)"
}

/**
 * Phase 17B: the answer of register / phone start / resend / change. Fails closed: a missing token cannot be used
 * (the caller treats it as an unreadable answer); unknown flags mean "no code waiting" and "no number yet".
 */
data class PhoneChallengeDto(
    val status: String? = null,
    val challenge: String? = null,
    val phoneHint: String? = null,
    val needsPhone: Boolean? = null,
    val codeSent: Boolean? = null,
    val resendInSeconds: Long? = null,
    val codeExpiresInSeconds: Long? = null
) {
    fun toDomain(): com.safesphere.app.feature.auth.domain.PhoneChallenge {
        val token = challenge?.takeIf { it.isNotBlank() } ?: throw IllegalStateException("no challenge")
        return com.safesphere.app.feature.auth.domain.PhoneChallenge(
            token = token,
            phoneHint = phoneHint?.takeIf { it.isNotBlank() },
            needsPhone = needsPhone ?: (phoneHint == null),
            codeSent = codeSent ?: false,
            resendInSeconds = (resendInSeconds ?: 0).coerceIn(0, MAX_WAIT_SECONDS),
            codeExpiresInSeconds = (codeExpiresInSeconds ?: 0).coerceIn(0, MAX_WAIT_SECONDS)
        )
    }

    override fun toString() = "PhoneChallengeDto(challenge=<hidden>, needsPhone=$needsPhone, codeSent=$codeSent)"

    private companion object {
        const val MAX_WAIT_SECONDS = 24L * 60 * 60
    }
}

data class PhoneStartRequestDto(val email: String, val password: String, val phone: String? = null) {
    override fun toString() = "PhoneStartRequestDto(<hidden>)"
}

data class PhoneVerifyRequestDto(val challenge: String, val code: String) {
    override fun toString() = "PhoneVerifyRequestDto(<hidden>)"
}

data class PhoneResendRequestDto(val challenge: String) {
    override fun toString() = "PhoneResendRequestDto(<hidden>)"
}

data class PhoneChangeRequestDto(val challenge: String, val phone: String) {
    override fun toString() = "PhoneChangeRequestDto(<hidden>)"
}

/**
 * Phase 11 C1: [previousRefreshToken] = this phone's refresh token for the SAME account (signing in again after
 * "sign in to sync"): the server ends that old sign-in and hands its running SOS to the new one. Never another account's.
 */
data class LoginRequestDto(val email: String, val password: String, val previousRefreshToken: String? = null) {
    override fun toString() = "LoginRequestDto(email=<hidden>, password=<hidden>, previous=<hidden>)"
}

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
