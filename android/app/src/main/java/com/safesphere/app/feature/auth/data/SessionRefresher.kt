package com.safesphere.app.feature.auth.data

import com.safesphere.app.core.network.RefreshOutcome
import com.safesphere.app.core.network.SessionEndReason
import com.safesphere.app.core.network.SessionEnder
import com.safesphere.app.core.network.TokenRefresher
import com.safesphere.app.feature.auth.data.remote.AuthApi
import com.safesphere.app.feature.auth.data.remote.RefreshRequestDto
import com.safesphere.app.feature.auth.domain.AuthSession
import com.safesphere.app.feature.auth.domain.SessionStorage
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import retrofit2.HttpException

/**
 * Phase 11 C1: gets a new access token with the stored refresh secret (POST /api/auth/refresh). ONE at a time
 * ([mutex]): when several requests are refused at once, the first refreshes and the others find the newer token already
 * saved (no refresh storm, and no second use of a secret the server just replaced).
 *
 * The server rotates the secret on every refresh, so its answer must never be lost: the call and the save run
 * NonCancellable, and the save is atomic and conditional ([SessionStorage.replaceAfterRefresh]): it happens only while
 * this phone still holds the secret that was used, for the same account. A late answer for an old sign-in can never
 * overwrite a newer one.
 */
class SessionRefresher(
    private val storage: SessionStorage,
    private val api: AuthApi
) : TokenRefresher {

    private val mutex = Mutex()

    override suspend fun refreshAfter(rejectedToken: String?): RefreshOutcome = mutex.withLock {
        val current = storage.session.first() ?: return@withLock RefreshOutcome.NotPossible
        // Someone else refreshed (or signed in again) while this request was on its way: just use the newer token.
        if (rejectedToken != null && current.token != rejectedToken) return@withLock RefreshOutcome.Refreshed
        if (current.needsSignIn) return@withLock RefreshOutcome.NotPossible
        val used = current.refreshToken ?: return@withLock RefreshOutcome.NotPossible
        withContext(NonCancellable) { refreshWith(current, used) }
    }

    private suspend fun refreshWith(current: AuthSession, used: String): RefreshOutcome =
        try {
            val answer = api.refresh(RefreshRequestDto(used))
            val rotated = answer.refreshToken
            if (rotated == null) {
                RefreshOutcome.TryLater // not a valid answer (the server always rotates): change nothing
            } else {
                val replacement = AuthSession(answer.accessToken, answer.user.toDomain(), refreshToken = rotated)
                if (replacement.user.id != current.user.id) {
                    RefreshOutcome.NotPossible
                } else if (storage.replaceAfterRefresh(used, replacement)) {
                    RefreshOutcome.Refreshed
                } else {
                    RefreshOutcome.NotPossible // signed out or switched account meanwhile: nothing to bring back
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            when (e.code()) {
                401 -> RefreshOutcome.Refused(refusalReason(e))
                400, 404 -> RefreshOutcome.Refused(SessionEndReason.SESSION_REVOKED)
                else -> RefreshOutcome.TryLater // 429, 5xx, proxies: the secret may still be good
            }
        } catch (e: IOException) {
            RefreshOutcome.TryLater
        } catch (e: Exception) {
            RefreshOutcome.TryLater // an unreadable answer: try again later rather than give up the session
        }

    private fun refusalReason(e: HttpException): SessionEndReason =
        when (val reason = SessionEnder.reasonOf(e.response()?.headers()?.get("WWW-Authenticate"))) {
            SessionEndReason.ACCOUNT_DELETED, SessionEndReason.ACCOUNT_UNAVAILABLE -> reason
            else -> SessionEndReason.SESSION_REVOKED
        }
}
