package com.safesphere.app.feature.account.session

import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.account.domain.TokenRenewalPolicy
import com.safesphere.app.feature.auth.domain.AuthSession
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Phase 10A.4 / 11 C1 (application scope): keeps the saved 24 h access token usable.
 *
 * It looks at once when the app starts or the session changes, then every 10 minutes while an SOS or Safe Walk runs
 * (the foreground service keeps the process alive) and every hour otherwise:
 *  - with a refresh secret (C1): refreshes when the token has expired (the app was not running for a day or more) or
 *    has less than 12 h left ([TokenRenewalPolicy]); [refresh] is SessionEnder.refresh, so a refused refresh means
 *    "sign in to sync" and never a deletion of local safety data;
 *  - without one (a sign-in from before C1, token still valid): renews once via POST /api/users/me/token, whose answer
 *    carries the refresh secret the server now gives that sign-in.
 * A failed or refused attempt changes nothing else; the next look tries again. Nothing is logged.
 */
class SessionRenewer(
    scope: CoroutineScope,
    session: Flow<AuthSession?>,
    safetyRunning: Flow<Boolean>,
    private val renew: suspend () -> ApiResult<Unit>,
    private val refresh: suspend (token: String) -> Unit,
    private val now: () -> Instant = Instant::now
) {
    /** What decides whether to act: the token, whether a refresh secret exists, and the "sign in to sync" mark. */
    private data class Key(val token: String, val hasRefresh: Boolean, val needsSignIn: Boolean)

    init {
        scope.launch {
            combine(
                session.distinctUntilChanged { a, b -> a?.let(::keyOf) == b?.let(::keyOf) },
                safetyRunning.distinctUntilChanged()
            ) { s, running -> s?.let(::keyOf) to running }
                .distinctUntilChanged()
                .collectLatest { (key, running) ->
                    if (key == null || key.needsSignIn) return@collectLatest
                    while (true) {
                        // A successful refresh or renewal changes the token, which restarts this block with the new one.
                        act(key)
                        delay(if (running) TokenRenewalPolicy.CHECK_EVERY_DURING_SAFETY_MILLIS
                        else TokenRenewalPolicy.CHECK_EVERY_OTHERWISE_MILLIS)
                    }
                }
        }
    }

    private suspend fun act(key: Key) {
        val expiresAt = TokenRenewalPolicy.expiresAt(key.token)
        val issuedAt = TokenRenewalPolicy.issuedAt(key.token)
        val at = now()
        when {
            key.hasRefresh -> if (TokenRenewalPolicy.expired(expiresAt, at) || TokenRenewalPolicy.due(expiresAt, at, issuedAt)) {
                refresh(key.token)
            }
            // A sign-in from before C1: get a refresh secret while the token still works (renewal adopts it).
            !TokenRenewalPolicy.expired(expiresAt, at) -> renew()
        }
    }

    private fun keyOf(session: AuthSession) = Key(session.token, session.refreshToken != null, session.needsSignIn)
}
