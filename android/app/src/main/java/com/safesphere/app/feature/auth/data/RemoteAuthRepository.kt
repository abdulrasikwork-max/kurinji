package com.safesphere.app.feature.auth.data

import com.safesphere.app.core.network.SessionEnder
import com.safesphere.app.core.network.UnauthorizedOutcome
import com.safesphere.app.feature.auth.data.remote.AuthApi
import com.safesphere.app.feature.auth.data.remote.AuthResponseDto
import com.safesphere.app.feature.auth.data.remote.LoginRequestDto
import com.safesphere.app.feature.auth.data.remote.PhoneChallengeDto
import com.safesphere.app.feature.auth.data.remote.PhoneChangeRequestDto
import com.safesphere.app.feature.auth.data.remote.PhoneResendRequestDto
import com.safesphere.app.feature.auth.data.remote.PhoneStartRequestDto
import com.safesphere.app.feature.auth.data.remote.PhoneVerifyRequestDto
import com.safesphere.app.feature.auth.data.remote.RefreshRequestDto
import com.safesphere.app.feature.auth.data.remote.RegisterRequestDto
import com.google.gson.JsonParser
import com.safesphere.app.feature.auth.domain.AuthError
import com.safesphere.app.feature.auth.domain.AuthRepository
import com.safesphere.app.feature.auth.domain.AuthResult
import com.safesphere.app.feature.auth.domain.AuthSession
import com.safesphere.app.feature.auth.domain.AuthUser
import com.safesphere.app.feature.auth.domain.PhoneChallenge
import com.safesphere.app.feature.auth.domain.SessionStorage
import java.io.IOException
import java.time.LocalDate
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import retrofit2.HttpException

/**
 * Talks to the Spring Boot backend through [AuthApi] and remembers the result in [SessionStorage].
 * It also translates every failure (HTTP status, no network, unreadable answer) into an [AuthError],
 * so no screen ever sees an exception, a stack trace or a raw server message.
 *
 * Phase 11 C1:
 *  - Signing in keeps the refresh token. Signing in again to the SAME account (after "sign in to sync") keeps every
 *    bit of local safety data and shows the server the old refresh token, so the old sign-in ends there and its running
 *    SOS moves to the new one (no duplicate, no password needed to end it).
 *  - Signing in to a DIFFERENT account first removes the previous account's data from this phone
 *    ([onAccountSwitch], the same clean-up as a sign-out) and ends the previous sign-in on the server (best effort).
 *  - Signing out ends this phone's sign-in on the server (best effort, a few seconds at most), then locally.
 */
class RemoteAuthRepository(
    private val api: AuthApi,
    private val sessionStorage: SessionStorage,
    private val sessionEnder: SessionEnder = SessionEnder(sessionStorage),
    /** Removes the previous account's safety data from this phone before another account's session is saved. */
    private val onAccountSwitch: suspend (previousUserId: Long) -> Unit = {}
) : AuthRepository {

    override val currentUser: Flow<AuthUser?> = sessionStorage.session.map { it?.user }

    override val signInNeeded: Flow<Boolean> = sessionStorage.session.map { it?.needsSignIn == true }

    override suspend fun register(
        name: String,
        email: String,
        password: String,
        dateOfBirth: LocalDate,
        phone: String
    ): AuthResult<PhoneChallenge> =
        // Phase 17B: sign-up starts no session (nothing is saved here); the texted code finishes it (verifyPhone).
        challengeCall(unauthorizedMeans = AuthError.UNKNOWN) {
            api.register(RegisterRequestDto(name = name, email = email, password = password,
                dateOfBirth = dateOfBirth.toString(), phone = phone))
        }

    override suspend fun startPhoneVerification(email: String, password: String, phone: String?): AuthResult<PhoneChallenge> =
        challengeCall(unauthorizedMeans = AuthError.INVALID_CREDENTIALS) {
            api.startPhoneVerification(PhoneStartRequestDto(email = email, password = password, phone = phone))
        }

    override suspend fun verifyPhone(challenge: PhoneChallenge, code: String): AuthResult<AuthUser> {
        val previous = sessionStorage.session.first()
        return authenticate(previous) { api.verifyPhone(PhoneVerifyRequestDto(challenge = challenge.token, code = code)) }
    }

    override suspend fun resendCode(challenge: PhoneChallenge): AuthResult<PhoneChallenge> =
        challengeCall(unauthorizedMeans = AuthError.UNKNOWN) { api.resendCode(PhoneResendRequestDto(challenge.token)) }

    override suspend fun changePhone(challenge: PhoneChallenge, phone: String): AuthResult<PhoneChallenge> =
        challengeCall(unauthorizedMeans = AuthError.UNKNOWN) {
            api.changePhone(PhoneChangeRequestDto(challenge = challenge.token, phone = phone))
        }

    /** Phase 17B: one call that answers with a verification (nothing is saved on the phone). */
    private suspend fun challengeCall(
        unauthorizedMeans: AuthError,
        call: suspend () -> PhoneChallengeDto
    ): AuthResult<PhoneChallenge> =
        try {
            AuthResult.Success(call().toDomain())
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            failureFor(e, unauthorizedMeans)
        } catch (e: IOException) {
            AuthResult.Failure(AuthError.NO_CONNECTION)
        } catch (e: Exception) {
            AuthResult.Failure(AuthError.UNKNOWN) // includes an answer without a challenge token
        }

    override suspend fun login(email: String, password: String): AuthResult<AuthUser> {
        val previous = sessionStorage.session.first()
        // Only the SAME account's old refresh token is ever sent (another account's would link two accounts).
        val sameAccount = previous?.takeIf { it.user.email.equals(email.trim(), ignoreCase = true) }
        return authenticate(previous) {
            api.login(LoginRequestDto(email = email, password = password, previousRefreshToken = sameAccount?.refreshToken))
        }
    }

    override suspend fun refreshProfile(): AuthResult<AuthUser> = profile(afterRefresh = false)

    private suspend fun profile(afterRefresh: Boolean): AuthResult<AuthUser> {
        val stored = sessionStorage.session.first() ?: return AuthResult.Failure(AuthError.UNAUTHORIZED)
        return try {
            val user = api.me().toDomain()
            // Only the user part, and only if the session is still the one asked about. One atomic step (Phase 11 C1):
            // a refresh may have rotated the tokens meanwhile, and they must not be written back over.
            sessionStorage.update { now -> if (now?.token == stored.token) now.copy(user = user) else now }
            AuthResult.Success(user)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            val error = errorForHttpCode(e.code(), unauthorizedMeans = AuthError.UNAUTHORIZED)
            if (error != AuthError.UNAUTHORIZED) return AuthResult.Failure(error)
            // Phase 11 C1: an expired token is refreshed and asked once more; revocations follow SessionEnder.
            when (sessionEnder.onUnauthorized(e.response(), afterRefresh)) {
                UnauthorizedOutcome.RETRY -> if (!afterRefresh) profile(afterRefresh = true) else AuthResult.Failure(error)
                UnauthorizedOutcome.OFFLINE -> AuthResult.Failure(AuthError.NO_CONNECTION)
                else -> AuthResult.Failure(error)
            }
        } catch (e: IOException) {
            AuthResult.Failure(AuthError.NO_CONNECTION)
        } catch (e: Exception) {
            AuthResult.Failure(AuthError.UNKNOWN)
        }
    }

    override suspend fun logout() {
        val current = sessionStorage.session.first()
        current?.refreshToken?.let { endOnServer(it) }
        sessionStorage.clear()
    }

    private suspend fun authenticate(previous: AuthSession?, call: suspend () -> AuthResponseDto): AuthResult<AuthUser> =
        try {
            val response = call()
            val user = response.user.toDomain()
            if (previous != null && previous.user.id != user.id) {
                // Another account on this phone: the previous account's data goes first (never visible to the new one).
                onAccountSwitch(previous.user.id)
                previous.refreshToken?.let { endOnServer(it) }
            }
            sessionStorage.save(AuthSession(token = response.accessToken, user = user, refreshToken = response.refreshToken))
            AuthResult.Success(user)
        } catch (e: CancellationException) {
            throw e // never swallow coroutine cancellation
        } catch (e: HttpException) {
            // Phase 17B: the right password of an account without a verified phone gives PHONE_NOT_VERIFIED (no session).
            failureFor(e, unauthorizedMeans = AuthError.INVALID_CREDENTIALS)
        } catch (e: IOException) {
            AuthResult.Failure(AuthError.NO_CONNECTION)
        } catch (e: Exception) {
            AuthResult.Failure(AuthError.UNKNOWN) // for example an unreadable response body
        }

    /**
     * The server's stable `code` (ApiError) decides first; only the codes whose words depend on the case (tries left,
     * seconds to wait) keep the server's message. Everything else falls back to the HTTP status.
     */
    private fun failureFor(e: HttpException, unauthorizedMeans: AuthError): AuthResult.Failure {
        val (code, message) = errorBody(e)
        val coded = code?.let { CODES[it] }
        if (coded != null) return AuthResult.Failure(coded, message?.takeIf { coded in SERVER_WORDED })
        return AuthResult.Failure(errorForHttpCode(e.code(), unauthorizedMeans))
    }

    /** The stable `code` and the message of our error body, or nulls. The body can be read only once. */
    private fun errorBody(e: HttpException): Pair<String?, String?> = try {
        val body = e.response()?.errorBody()?.string()
        val json = if (body.isNullOrBlank()) null
        else JsonParser.parseString(body).takeIf { it.isJsonObject }?.asJsonObject
        fun text(name: String) = json?.get(name)?.takeIf { it.isJsonPrimitive }?.asString
        text("code") to text("message")
    } catch (e: Exception) {
        null to null
    }

    /** Best effort: offline or slow, the sign-in simply ends on the server when its idle time runs out. */
    private suspend fun endOnServer(refreshToken: String) {
        try {
            withTimeoutOrNull(SIGN_OUT_TIMEOUT_MILLIS) { api.signOut(RefreshRequestDto(refreshToken)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Nothing to do: local sign-out must never depend on the server.
        }
    }

    private fun errorForHttpCode(code: Int, unauthorizedMeans: AuthError): AuthError = when (code) {
        401 -> unauthorizedMeans
        409 -> AuthError.EMAIL_ALREADY_USED
        429 -> AuthError.TOO_MANY_ATTEMPTS
        400, 422 -> AuthError.VALIDATION
        in 500..599 -> AuthError.SERVER
        else -> AuthError.UNKNOWN
    }

    private companion object {
        const val SIGN_OUT_TIMEOUT_MILLIS = 3_000L

        /** Phase 17B: the backend's PhoneVerificationException codes. Unknown codes fall back to the HTTP status. */
        val CODES = mapOf(
            "PHONE_NOT_VERIFIED" to AuthError.PHONE_NOT_VERIFIED,
            "PHONE_INVALID" to AuthError.PHONE_INVALID,
            "PHONE_REQUIRED" to AuthError.PHONE_REQUIRED,
            "PHONE_IN_USE" to AuthError.PHONE_IN_USE,
            "PHONE_ALREADY_VERIFIED" to AuthError.PHONE_ALREADY_VERIFIED,
            "CHALLENGE_INVALID" to AuthError.CHALLENGE_INVALID,
            "OTP_INVALID" to AuthError.OTP_INVALID,
            "OTP_EXPIRED" to AuthError.OTP_EXPIRED,
            "OTP_LOCKED" to AuthError.OTP_LOCKED,
            "OTP_WAIT" to AuthError.OTP_WAIT
        )

        /** Their words depend on the case ("2 tries left", "in 40 seconds"): the server's own text is shown. */
        val SERVER_WORDED = setOf(AuthError.OTP_INVALID, AuthError.OTP_WAIT)
    }
}
