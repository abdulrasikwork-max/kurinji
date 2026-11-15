package com.safesphere.app.feature.auth.data

import com.safesphere.app.core.network.SessionEnder
import com.safesphere.app.core.network.UnauthorizedOutcome
import com.safesphere.app.feature.auth.data.remote.AuthApi
import com.safesphere.app.feature.auth.data.remote.AuthResponseDto
import com.safesphere.app.feature.auth.data.remote.PhoneChallengeDto
import com.safesphere.app.feature.auth.data.remote.PhoneChangeRequestDto
import com.safesphere.app.feature.auth.data.remote.PhoneResendRequestDto
import com.safesphere.app.feature.auth.data.remote.PhoneStartRequestDto
import com.safesphere.app.feature.auth.data.remote.PhoneVerifyRequestDto
import com.safesphere.app.feature.auth.data.remote.RefreshRequestDto
import com.safesphere.app.feature.auth.data.remote.SignUpRequestDto
import com.safesphere.app.feature.auth.data.remote.VerifyResponseDto
import com.google.gson.JsonParser
import com.safesphere.app.feature.auth.domain.AuthError
import com.safesphere.app.feature.auth.domain.AuthRepository
import com.safesphere.app.feature.auth.domain.AuthResult
import com.safesphere.app.feature.auth.domain.AuthSession
import com.safesphere.app.feature.auth.domain.AuthUser
import com.safesphere.app.feature.auth.domain.PhoneChallenge
import com.safesphere.app.feature.auth.domain.SignInResult
import com.safesphere.app.feature.auth.domain.SignUpTicket
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
 *    SOS moves to the new one (no duplicate, no code needed to end it). Phase N2: the phone cannot know the account
 *    before the code is checked; it sends its old refresh token when the number's last 4 digits match its session, and
 *    the server ignores a token of another account.
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

    override suspend fun startSignIn(phone: String): AuthResult<PhoneChallenge> =
        challengeCall { api.startSignIn(PhoneStartRequestDto(phone = phone)) }

    override suspend fun verifyCode(challenge: PhoneChallenge, code: String): AuthResult<SignInResult> {
        val previous = sessionStorage.session.first()
        // Only this phone's own earlier sign-in, and only when it looks like the same number.
        val sameNumber = previous?.takeIf { it.user.phoneHint != null && it.user.phoneHint == challenge.phoneHint }
        return try {
            val answer = api.verifyCode(PhoneVerifyRequestDto(challenge = challenge.token, code = code,
                previousRefreshToken = sameNumber?.refreshToken))
            when (answer.status) {
                VerifyResponseDto.SIGNED_IN -> {
                    val session = answer.session ?: throw IllegalStateException("no session")
                    AuthResult.Success(SignInResult.SignedIn(save(previous, session)))
                }
                VerifyResponseDto.SIGN_UP_NEEDED -> {
                    val ticket = answer.signUp?.toDomain() ?: throw IllegalStateException("no ticket")
                    AuthResult.Success(SignInResult.SignUpNeeded(ticket))
                }
                else -> AuthResult.Failure(AuthError.UNKNOWN)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            failureFor(e)
        } catch (e: IOException) {
            AuthResult.Failure(AuthError.NO_CONNECTION)
        } catch (e: Exception) {
            AuthResult.Failure(AuthError.UNKNOWN) // an unreadable or incomplete answer
        }
    }

    override suspend fun signUp(ticket: SignUpTicket, name: String, dateOfBirth: LocalDate): AuthResult<AuthUser> {
        val previous = sessionStorage.session.first()
        return authenticate(previous) {
            api.signUp(SignUpRequestDto(ticket = ticket.token, name = name, dateOfBirth = dateOfBirth.toString()))
        }
    }

    override suspend fun resendCode(challenge: PhoneChallenge): AuthResult<PhoneChallenge> =
        challengeCall { api.resendCode(PhoneResendRequestDto(challenge.token)) }

    override suspend fun changePhone(challenge: PhoneChallenge, phone: String): AuthResult<PhoneChallenge> =
        challengeCall {
            api.changePhone(PhoneChangeRequestDto(challenge = challenge.token, phone = phone))
        }

    /** Phase 17B: one call that answers with a code on its way (nothing is saved on the phone). */
    private suspend fun challengeCall(call: suspend () -> PhoneChallengeDto): AuthResult<PhoneChallenge> =
        try {
            AuthResult.Success(call().toDomain())
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            failureFor(e)
        } catch (e: IOException) {
            AuthResult.Failure(AuthError.NO_CONNECTION)
        } catch (e: Exception) {
            AuthResult.Failure(AuthError.UNKNOWN) // includes an answer without a challenge token
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
            AuthResult.Success(save(previous, call()))
        } catch (e: CancellationException) {
            throw e // never swallow coroutine cancellation
        } catch (e: HttpException) {
            failureFor(e)
        } catch (e: IOException) {
            AuthResult.Failure(AuthError.NO_CONNECTION)
        } catch (e: Exception) {
            AuthResult.Failure(AuthError.UNKNOWN) // for example an unreadable response body
        }

    /** Saves a new sign-in; another account's data on this phone goes first (never visible to the new one). */
    private suspend fun save(previous: AuthSession?, response: AuthResponseDto): AuthUser {
        val user = response.user.toDomain()
        if (previous != null && previous.user.id != user.id) {
            onAccountSwitch(previous.user.id)
            previous.refreshToken?.let { endOnServer(it) }
        }
        sessionStorage.save(AuthSession(token = response.accessToken, user = user, refreshToken = response.refreshToken))
        return user
    }

    /**
     * The server's stable `code` (ApiError) decides first; only the codes whose words depend on the case (tries left,
     * seconds to wait) keep the server's message. Everything else falls back to the HTTP status.
     */
    private fun failureFor(e: HttpException): AuthResult.Failure {
        val (code, message) = errorBody(e)
        val coded = code?.let { CODES[it] }
        if (coded != null) return AuthResult.Failure(coded, message?.takeIf { coded in SERVER_WORDED })
        return AuthResult.Failure(errorForHttpCode(e.code(), unauthorizedMeans = AuthError.UNKNOWN))
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
        429 -> AuthError.TOO_MANY_ATTEMPTS
        400, 422 -> AuthError.VALIDATION
        in 500..599 -> AuthError.SERVER
        else -> AuthError.UNKNOWN
    }

    private companion object {
        const val SIGN_OUT_TIMEOUT_MILLIS = 3_000L

        /** Phase 17B: the backend's PhoneVerificationException codes. Unknown codes fall back to the HTTP status. */
        val CODES = mapOf(
            "PHONE_INVALID" to AuthError.PHONE_INVALID,
            "PHONE_IN_USE" to AuthError.PHONE_IN_USE,
            "CHALLENGE_INVALID" to AuthError.CHALLENGE_INVALID,
            "TICKET_INVALID" to AuthError.TICKET_INVALID,
            "ACCOUNT_UNAVAILABLE" to AuthError.ACCOUNT_UNAVAILABLE,
            "OTP_INVALID" to AuthError.OTP_INVALID,
            "OTP_EXPIRED" to AuthError.OTP_EXPIRED,
            "OTP_LOCKED" to AuthError.OTP_LOCKED,
            "OTP_WAIT" to AuthError.OTP_WAIT
        )

        /** Their words depend on the case ("2 tries left", "in 40 seconds"): the server's own text is shown. */
        val SERVER_WORDED = setOf(AuthError.OTP_INVALID, AuthError.OTP_WAIT)
    }
}
