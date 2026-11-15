package com.safesphere.app.feature.auth.data.remote

import com.safesphere.app.feature.auth.domain.AuthUser
import com.safesphere.app.feature.auth.domain.SignUpTicket
import java.time.Instant

// These classes mirror the JSON of the Spring Boot API (see docs/API.md).

/**
 * Phase 17B / N2: the answer of phone start / resend / change (and of a confirmation request). Fails closed: a missing
 * token cannot be used (the caller treats it as an unreadable answer); an unknown flag means "no code waiting".
 */
data class PhoneChallengeDto(
    val challenge: String? = null,
    val phoneHint: String? = null,
    val codeSent: Boolean? = null,
    val resendInSeconds: Long? = null,
    val codeExpiresInSeconds: Long? = null
) {
    fun toDomain(): com.safesphere.app.feature.auth.domain.PhoneChallenge {
        val token = challenge?.takeIf { it.isNotBlank() } ?: throw IllegalStateException("no challenge")
        return com.safesphere.app.feature.auth.domain.PhoneChallenge(
            token = token,
            phoneHint = phoneHint?.takeIf { it.isNotBlank() },
            codeSent = codeSent ?: false,
            resendInSeconds = (resendInSeconds ?: 0).coerceIn(0, MAX_WAIT_SECONDS),
            codeExpiresInSeconds = (codeExpiresInSeconds ?: 0).coerceIn(0, MAX_WAIT_SECONDS)
        )
    }

    override fun toString() = "PhoneChallengeDto(challenge=<hidden>, codeSent=$codeSent)"

    companion object {
        const val MAX_WAIT_SECONDS = 24L * 60 * 60
    }
}

/** N2: the only thing anyone types to sign in or to sign up. */
data class PhoneStartRequestDto(val phone: String) {
    override fun toString() = "PhoneStartRequestDto(<hidden>)"
}

/**
 * Phase 11 C1: [previousRefreshToken] = this phone's refresh token for the SAME account (signing in again after
 * "sign in to sync"): the server ends that old sign-in and hands its running SOS to the new one. The server ignores a
 * token of another account.
 */
data class PhoneVerifyRequestDto(val challenge: String, val code: String, val previousRefreshToken: String? = null) {
    override fun toString() = "PhoneVerifyRequestDto(<hidden>)"
}

/**
 * N2: the answer of phone/verify. [status] SIGNED_IN with [session], or SIGN_UP_NEEDED with [signUp]. Fails closed:
 * anything else, or a missing part, is an unreadable answer.
 */
data class VerifyResponseDto(
    val status: String? = null,
    val session: AuthResponseDto? = null,
    val signUp: SignUpTicketDto? = null
) {
    override fun toString() = "VerifyResponseDto(status=$status, <hidden>)"

    companion object {
        const val SIGNED_IN = "SIGNED_IN"
        const val SIGN_UP_NEEDED = "SIGN_UP_NEEDED"
    }
}

data class SignUpTicketDto(val ticket: String? = null, val phoneHint: String? = null, val expiresInSeconds: Long? = null) {
    fun toDomain(): SignUpTicket {
        val token = ticket?.takeIf { it.isNotBlank() } ?: throw IllegalStateException("no ticket")
        return SignUpTicket(token, phoneHint?.takeIf { it.isNotBlank() },
            (expiresInSeconds ?: 0).coerceIn(0, PhoneChallengeDto.MAX_WAIT_SECONDS))
    }

    override fun toString() = "SignUpTicketDto(<hidden>)"
}

/**
 * N2: body of POST /api/auth/sign-up. [name] = the real name; [dateOfBirth] (yyyy-MM-dd) is checked for 18+ by the
 * server and stored nowhere (Phase 13A).
 */
data class SignUpRequestDto(val ticket: String, val name: String, val dateOfBirth: String) {
    override fun toString() = "SignUpRequestDto(<hidden>)"
}

data class PhoneResendRequestDto(val challenge: String) {
    override fun toString() = "PhoneResendRequestDto(<hidden>)"
}

data class PhoneChangeRequestDto(val challenge: String, val phone: String) {
    override fun toString() = "PhoneChangeRequestDto(<hidden>)"
}

/** Phase 11 C1: body of /api/auth/refresh and /api/auth/sign-out. The token is a credential. */
data class RefreshRequestDto(val refreshToken: String) {
    override fun toString() = "RefreshRequestDto(<hidden>)"
}

/**
 * Phase 10A: [deletionRequestedAt] / [deletionDueAt] (ISO-8601, UTC) while an account deletion is pending.
 * Phase N2: [phoneHint] = the account's number with only the last 4 digits; there is no e-mail any more.
 */
data class UserDto(
    val id: Long,
    val name: String,
    val phoneHint: String? = null,
    val createdAt: String?,
    val deletionRequestedAt: String? = null,
    val deletionDueAt: String? = null
) {
    fun toDomain(): AuthUser {
        val requested = instantOrNull(deletionRequestedAt)
        val due = instantOrNull(deletionDueAt)
        // Both or neither (as on the server); a half-readable pair is shown as "pending, date unknown" rather than lost.
        return AuthUser(
            id = id, name = name, phoneHint = phoneHint?.takeIf { it.isNotBlank() },
            deletionRequestedAt = requested,
            deletionDueAt = due ?: if (deletionDueAt != null) Instant.EPOCH else null
        )
    }

    override fun toString() = "UserDto(id=$id, name=<hidden>, phone=<hidden>, deletionDueAt=$deletionDueAt)"

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
