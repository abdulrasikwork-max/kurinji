package com.safesphere.app.feature.account.data

import com.safesphere.app.core.confirm.CodeRequest
import com.safesphere.app.core.confirm.Confirmation
import com.safesphere.app.core.network.ApiCallHandler
import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.core.network.SessionEnder
import com.safesphere.app.feature.account.data.remote.AccountApi
import com.safesphere.app.feature.account.data.remote.CodeConfirmationDto
import com.safesphere.app.feature.account.data.remote.NameChangeDto
import com.safesphere.app.feature.account.domain.AccountRepository
import com.safesphere.app.feature.auth.domain.AuthSession
import com.safesphere.app.feature.auth.domain.SessionStorage
import java.time.Instant
import kotlinx.coroutines.flow.first

/**
 * Phase 10A, reworked in N2 (a fresh texted code instead of the password). Signing out the other phones swaps the token
 * inside [SessionEnder.swapping]: the new token is saved before any 401 for the old one can be taken as "signed out"
 * (docs/PHASE10.md, R8). The server refuses it while an SOS or a Safe Walk runs, so an emergency on this phone is never
 * cut off by it.
 */
class RemoteAccountRepository(
    private val api: AccountApi,
    private val handler: ApiCallHandler,
    private val sessionStorage: SessionStorage,
    private val sessionEnder: SessionEnder
) : AccountRepository {

    override suspend fun request(): ApiResult<CodeRequest> =
        when (val answer = handler.execute { api.requestConfirmation() }) {
            is ApiResult.Failure -> answer
            is ApiResult.Success -> {
                // Fails closed: an answer without a challenge cannot confirm anything.
                val challenge = answer.data.challenge?.takeIf { it.isNotBlank() }
                if (challenge == null) {
                    ApiResult.Failure(ApiError.UNKNOWN)
                } else {
                    ApiResult.Success(CodeRequest(challenge, answer.data.phoneHint?.takeIf { it.isNotBlank() },
                        answer.data.codeSent ?: false, (answer.data.resendInSeconds ?: 0).coerceAtLeast(0)))
                }
            }
        }

    override suspend fun signOutOtherPhones(confirmation: Confirmation): ApiResult<Unit> {
        val session = sessionStorage.session.first() ?: return ApiResult.Failure(ApiError.UNAUTHORIZED)
        val old = session.token
        val result = sessionEnder.swapping(old) {
            when (val answer = handler.execute { api.signOutOtherPhones(confirmation.toDto()) }) {
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

    override suspend fun changeName(name: String): ApiResult<Unit> =
        when (val answer = handler.execute { api.changeName(NameChangeDto(name)) }) {
            is ApiResult.Failure -> answer
            is ApiResult.Success -> {
                val updated = answer.data.toDomain()
                updateUser { it.copy(user = it.user.copy(name = updated.name)) }
                ApiResult.Success(Unit)
            }
        }

    override suspend fun requestDeletion(confirmation: Confirmation): ApiResult<Instant> =
        when (val answer = handler.execute { api.requestDeletion(confirmation.toDto()) }) {
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

    override suspend fun cancelDeletion(confirmation: Confirmation): ApiResult<Unit> =
        when (val answer = handler.execute { api.cancelDeletion(confirmation.toDto()) }) {
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
                // Only if nothing changed meanwhile (a sign-out, signing out the other phones or a refresh must win
                // over a renewal). Phase 11 C1: a sign-in from before C1 receives its refresh secret here (once);
                // otherwise the secret stored NOW is kept. One atomic step.
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

    private fun Confirmation.toDto() = CodeConfirmationDto(challenge, code)
}
