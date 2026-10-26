package com.safesphere.app.feature.auth.data

import com.safesphere.app.core.network.SessionEnder
import com.safesphere.app.core.network.UnauthorizedOutcome
import com.safesphere.app.feature.auth.data.remote.AuthApi
import com.safesphere.app.feature.auth.data.remote.AuthResponseDto
import com.safesphere.app.feature.auth.data.remote.LoginRequestDto
import com.safesphere.app.feature.auth.data.remote.RefreshRequestDto
import com.safesphere.app.feature.auth.data.remote.RegisterRequestDto
import com.safesphere.app.feature.auth.data.remote.ResendVerificationRequestDto
import com.google.gson.JsonParser
import com.safesphere.app.feature.auth.domain.AuthError
import com.safesphere.app.feature.auth.domain.AuthRepository
import com.safesphere.app.feature.auth.domain.AuthResult
import com.safesphere.app.feature.auth.domain.AuthSession
import com.safesphere.app.feature.auth.domain.AuthUser
import com.safesphere.app.feature.auth.domain.SessionStorage
import com.safesphere.app.feature.auth.domain.VerificationPending
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
        dateOfBirth: LocalDate
    ): AuthResult<VerificationPending> =
        // V25: sign-up starts no session (nothing is saved here); the account works once the e-mailed link is opened.
        try {
            val answer = api.register(
                RegisterRequestDto(name = name, email = email, password = password, dateOfBirth = dateOfBirth.toString())
            )
            AuthResult.Success(VerificationPending(answer.email?.takeIf { it.isNotBlank() } ?: email.trim().lowercase()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            AuthResult.Failure(errorForHttpCode(e.code(), unauthorizedMeans = AuthError.UNKNOWN))
        } catch (e: IOException) {
            AuthResult.Failure(AuthError.NO_CONNECTION)
        } catch (e: Exception) {
            AuthResult.Failure(AuthError.UNKNOWN)
        }

    override suspend fun resendVerification(email: String, password: String): AuthResult<Unit> =
        try {
            api.resendVerification(ResendVerificationRequestDto(email = email, password = password))
            AuthResult.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            AuthResult.Failure(errorForHttpCode(e.code(), unauthorizedMeans = AuthError.UNKNOWN))
        } catch (e: IOException) {
            AuthResult.Failure(AuthError.NO_CONNECTION)
        } catch (e: Exception) {
            AuthResult.Failure(AuthError.UNKNOWN)
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
            // V25: the right password of an account whose e-mail is not confirmed yet (no session was started).
            if (e.code() == 403 && errorCode(e) == EMAIL_NOT_VERIFIED_CODE) AuthResult.Failure(AuthError.EMAIL_NOT_VERIFIED)
            else AuthResult.Failure(errorForHttpCode(e.code(), unauthorizedMeans = AuthError.INVALID_CREDENTIALS))
        } catch (e: IOException) {
            AuthResult.Failure(AuthError.NO_CONNECTION)
        } catch (e: Exception) {
            AuthResult.Failure(AuthError.UNKNOWN) // for example an unreadable response body
        }

    /** V25: the stable `code` of our error body (ApiError), or null. Never shown to the person. */
    private fun errorCode(e: HttpException): String? = try {
        val body = e.response()?.errorBody()?.string()
        if (body.isNullOrBlank()) null
        else JsonParser.parseString(body).takeIf { it.isJsonObject }?.asJsonObject?.get("code")
            ?.takeIf { it.isJsonPrimitive }?.asString
    } catch (e: Exception) {
        null
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
        const val EMAIL_NOT_VERIFIED_CODE = "EMAIL_NOT_VERIFIED"
    }
}
