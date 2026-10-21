package com.safesphere.app.feature.account.session

import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.account.domain.TokenRenewalPolicy
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Phase 10A.4 (application scope): keeps the saved 24 h token from running out while the app runs. Before 10A.4 a
 * token that expired during a long SOS signed the phone out and stopped location sharing (`docs/PHASE10.md` A4).
 *
 * It looks every 10 minutes while an SOS or Safe Walk runs (the foreground service keeps the process alive) and every
 * hour otherwise, and renews when less than 12 h are left ([TokenRenewalPolicy]). The server always grants a renewal
 * during an SOS or Safe Walk. A refused or failed renewal changes nothing: the current token keeps working until it
 * expires and the next look tries again. A renewal never revives a session that was signed out or replaced meanwhile
 * (the repository saves only over the same token). Nothing is logged.
 */
class SessionRenewer(
    scope: CoroutineScope,
    token: Flow<String?>,
    safetyRunning: Flow<Boolean>,
    private val renew: suspend () -> ApiResult<Unit>,
    private val now: () -> Instant = Instant::now
) {
    init {
        scope.launch {
            combine(token.distinctUntilChanged(), safetyRunning.distinctUntilChanged()) { t, s -> t to s }
                .collectLatest { (current, running) ->
                    if (current == null) return@collectLatest
                    while (true) {
                        // A successful renewal changes the token, which restarts this block with the new one.
                        if (TokenRenewalPolicy.due(TokenRenewalPolicy.expiresAt(current), now())) renew()
                        delay(if (running) TokenRenewalPolicy.CHECK_EVERY_DURING_SAFETY_MILLIS
                        else TokenRenewalPolicy.CHECK_EVERY_OTHERWISE_MILLIS)
                    }
                }
        }
    }
}
