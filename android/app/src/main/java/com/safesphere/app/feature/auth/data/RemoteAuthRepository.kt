package com.safesphere.app.feature.auth.data

import com.safesphere.app.core.network.SessionEnder
import com.safesphere.app.feature.auth.data.remote.AuthApi
import com.safesphere.app.feature.auth.data.remote.AuthResponseDto
import com.safesphere.app.feature.auth.data.remote.LoginRequestDto
import com.safesphere.app.feature.auth.data.remote.RegisterRequestDto
import com.safesphere.app.feature.auth.domain.AuthError
import com.safesphere.app.feature.auth.domain.AuthRepository
import com.safesphere.app.feature.auth.domain.AuthResult
import com.safesphere.app.feature.auth.domain.AuthSession
import com.safesphere.app.feature.auth.domain.AuthUser
import com.safesphere.app.feature.auth.domain.SessionStorage
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import retrofit2.HttpException

/**
 * Talks to the Spring Boot backend through [AuthApi] and remembers the result in [SessionStorage].
 * It also translates every failure (HTTP status, no network, unreadable answer) into an [AuthError],
 * so no screen ever sees an exception, a stack trace or a raw server message.
 */
class RemoteAuthRepository(
    private val api: AuthApi,
    private val sessionStorage: SessionStorage,
    private val sessionEnder: SessionEnder = SessionEnder(sessionStorage)
) : AuthRepository {

    override val currentUser: Flow<AuthUser?> = sessionStorage.session.map { it?.user }

    override suspend fun register(name: String, email: String, password: String): AuthResult<AuthUser> =
        authenticate { api.register(RegisterRequestDto(name = name, email = email, password = password)) }

    override suspend fun login(email: String, password: String): AuthResult<AuthUser> =
        authenticate { api.login(LoginRequestDto(email = email, password = password)) }

    override suspend fun refreshProfile(): AuthResult<AuthUser> {
        val stored = sessionStorage.session.first() ?: return AuthResult.Failure(AuthError.UNAUTHORIZED)
        return try {
            val user = api.me().toDomain()
            // Only if the session is still the one asked about (a password change may have replaced it meanwhile).
            if (sessionStorage.session.first()?.token == stored.token) sessionStorage.save(stored.copy(user = user))
            AuthResult.Success(user)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            val error = errorForHttpCode(e.code(), unauthorizedMeans = AuthError.UNAUTHORIZED)
            // The server no longer accepts this token (expired, password changed, account gone): sign out locally,
            // but only if this token is still the saved one (Phase 10A, SessionEnder).
            if (error == AuthError.UNAUTHORIZED) sessionEnder.onUnauthorized(e.response())
            AuthResult.Failure(error)
        } catch (e: IOException) {
            AuthResult.Failure(AuthError.NO_CONNECTION)
        } catch (e: Exception) {
            AuthResult.Failure(AuthError.UNKNOWN)
        }
    }

    override suspend fun logout() {
        // The JWT is stateless, so there is nothing to call on the server in Phase 2. See docs/PHASE2.md.
        sessionStorage.clear()
    }

    private suspend fun authenticate(call: suspend () -> AuthResponseDto): AuthResult<AuthUser> =
        try {
            val response = call()
            val user = response.user.toDomain()
            sessionStorage.save(AuthSession(token = response.accessToken, user = user))
            AuthResult.Success(user)
        } catch (e: CancellationException) {
            throw e // never swallow coroutine cancellation
        } catch (e: HttpException) {
            AuthResult.Failure(errorForHttpCode(e.code(), unauthorizedMeans = AuthError.INVALID_CREDENTIALS))
        } catch (e: IOException) {
            AuthResult.Failure(AuthError.NO_CONNECTION)
        } catch (e: Exception) {
            AuthResult.Failure(AuthError.UNKNOWN) // for example an unreadable response body
        }

    private fun errorForHttpCode(code: Int, unauthorizedMeans: AuthError): AuthError = when (code) {
        401 -> unauthorizedMeans
        409 -> AuthError.EMAIL_ALREADY_USED
        429 -> AuthError.TOO_MANY_ATTEMPTS
        400, 422 -> AuthError.VALIDATION
        in 500..599 -> AuthError.SERVER
        else -> AuthError.UNKNOWN
    }
}
