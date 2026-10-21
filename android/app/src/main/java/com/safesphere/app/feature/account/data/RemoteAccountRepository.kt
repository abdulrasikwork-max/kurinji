package com.safesphere.app.feature.account.data

import com.safesphere.app.core.network.ApiCallHandler
import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.core.network.SessionEndReason
import com.safesphere.app.core.network.SessionEnder
import com.safesphere.app.feature.account.data.remote.AccountApi
import com.safesphere.app.feature.account.data.remote.ChangePasswordRequestDto
import com.safesphere.app.feature.account.data.remote.PasswordConfirmationDto
import com.safesphere.app.feature.account.domain.AccountRepository
import com.safesphere.app.feature.auth.domain.AuthSession
import com.safesphere.app.feature.auth.domain.SessionStorage
import java.time.Instant
import kotlinx.coroutines.flow.first

/**
 * Phase 10A. The password change swaps the token inside [SessionEnder.swapping]: the new token is saved before any
 * 401 for the old one can be taken as "signed out" (docs/PHASE10.md, R8). The server refuses a password change while
 * an SOS or a Safe Walk runs, so an emergency on this phone is never cut off by it.
 */
class RemoteAccountRepository(
    private val api: AccountApi,
    private val handler: ApiCallHandler,
    private val sessionStorage: SessionStorage,
    private val sessionEnder: SessionEnder
) : AccountRepository {

    override suspend fun changePassword(currentPassword: String, newPassword: String): ApiResult<Unit> {
        val old = sessionStorage.session.first()?.token ?: return ApiResult.Failure(ApiError.UNAUTHORIZED)
        val result = sessionEnder.swapping(old) {
            when (val answer = handler.execute { api.changePassword(ChangePasswordRequestDto(currentPassword, newPassword)) }) {
                is ApiResult.Success -> {
                    sessionStorage.save(AuthSession(answer.data.accessToken, answer.data.user.toDomain()))
                    ApiResult.Success(Unit)
                }
                is ApiResult.Failure -> answer
            }
        }
        // A 401 for the request itself was ignored while swapping: the old session really is over.
        if (result is ApiResult.Failure && result.error == ApiError.UNAUTHORIZED) {
            sessionEnder.end(old, SessionEndReason.EXPIRED)
        }
        return result
    }

    override suspend fun requestDeletion(password: String): ApiResult<Instant> =
        when (val answer = handler.execute { api.requestDeletion(PasswordConfirmationDto(password)) }) {
            is ApiResult.Failure -> answer
            is ApiResult.Success -> {
                val due = answer.data.deletionDueAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
                if (due == null) {
                    ApiResult.Failure(ApiError.UNKNOWN)
                } else {
                    val requested = answer.data.deletionRequestedAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
                    updateUser { it.copy(user = it.user.copy(deletionRequestedAt = requested, deletionDueAt = due)) }
                    ApiResult.Success(due)
                }
            }
        }

    override suspend fun cancelDeletion(password: String): ApiResult<Unit> =
        when (val answer = handler.execute { api.cancelDeletion(PasswordConfirmationDto(password)) }) {
            is ApiResult.Failure -> answer
            is ApiResult.Success -> {
                updateUser { it.copy(user = it.user.copy(deletionRequestedAt = null, deletionDueAt = null)) }
                ApiResult.Success(Unit)
            }
        }

    override suspend fun renewToken(): ApiResult<Unit> {
        val old = sessionStorage.session.first()?.token ?: return ApiResult.Failure(ApiError.UNAUTHORIZED)
        return when (val answer = handler.execute { api.renewToken() }) {
            is ApiResult.Failure -> answer
            is ApiResult.Success -> {
                // Only if nothing changed meanwhile (a sign-out or a password change must win over a renewal).
                sessionStorage.replaceToken(old, AuthSession(answer.data.accessToken, answer.data.user.toDomain()))
                ApiResult.Success(Unit)
            }
        }
    }

    private suspend fun updateUser(transform: (AuthSession) -> AuthSession) {
        val current = sessionStorage.session.first() ?: return
        sessionStorage.save(transform(current))
    }
}
