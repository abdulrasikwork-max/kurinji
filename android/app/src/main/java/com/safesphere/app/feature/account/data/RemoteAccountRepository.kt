package com.safesphere.app.feature.account.data

import com.safesphere.app.core.network.ApiCallHandler
import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
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
        val session = sessionStorage.session.first() ?: return ApiResult.Failure(ApiError.UNAUTHORIZED)
        val old = session.token
        val result = sessionEnder.swapping(old) {
            when (val answer = handler.execute { api.changePassword(ChangePasswordRequestDto(currentPassword, newPassword)) }) {
                is ApiResult.Success -> {
                    // Phase 11 C1: this phone's sign-in continues; it keeps the refresh secret stored NOW (a refresh
                    // may have rotated it during the call) unless the answer brought a new one. One atomic step.
                    sessionStorage.update { now ->
                        AuthSession(answer.data.accessToken, answer.data.user.toDomain(),
                            refreshToken = answer.data.refreshToken ?: now?.refreshToken ?: session.refreshToken)
                    }
                    ApiResult.Success(Unit)
                }
                is ApiResult.Failure -> answer
            }
        }
        // A 401 for the request itself was ignored while swapping. Phase 11 C1: an expired token is refreshed (the
        // person taps again); a refused refresh means "sign in to sync" - never a silent deletion of safety data.
        if (result is ApiResult.Failure && result.error == ApiError.UNAUTHORIZED) {
            sessionEnder.refresh(old)
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
                // Only if nothing changed meanwhile (a sign-out, a password change or a refresh must win over a
                // renewal). Phase 11 C1: a sign-in from before C1 receives its refresh secret here (once); otherwise the
                // secret stored NOW is kept. One atomic step.
                sessionStorage.update { now ->
                    if (now?.token != old) now
                    else AuthSession(answer.data.accessToken, answer.data.user.toDomain(),
                        refreshToken = answer.data.refreshToken ?: now.refreshToken)
                }
                ApiResult.Success(Unit)
            }
        }
    }

    /** Changes only the user part, keeping the rest of the session as stored now (one atomic step, Phase 11 C1). */
    private suspend fun updateUser(transform: (AuthSession) -> AuthSession) {
        sessionStorage.update { now -> now?.let(transform) }
    }
}
