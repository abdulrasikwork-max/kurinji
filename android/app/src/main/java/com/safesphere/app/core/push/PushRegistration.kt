package com.safesphere.app.core.push

import com.safesphere.app.core.network.ApiResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * N7 (D28): the phone's token belongs to its sign-in. Followed in application scope: a new sign-in (a new session id),
 * a new token from Firebase, or notifications becoming allowed → register (PUT, bound by the server to the sign-in of
 * the call); a sign-out → the token is deleted on the phone (the server removes its row with the sign-in) and every IRL
 * notification on this phone goes. A failure is retried at the next change or the next time IRL comes to the front.
 * Nothing is logged.
 */
class PushRegistration(
    private val tokens: PushTokenSource,
    private val register: suspend (token: String, appVersion: String?) -> ApiResult<Unit>,
    private val tray: PushTray,
    private val allowed: () -> Boolean,
    private val appVersion: String?
) {
    private val lock = Mutex()
    private var sessionId: String? = null
    private var userId: Long? = null
    private var registered: Pair<String, String>? = null
    private var knownToken: String? = null

    /** [signIns]: who is signed in on this phone and the sign-in's session id (null = nobody). */
    fun follow(scope: CoroutineScope, signIns: Flow<Pair<Long, String?>?>) {
        scope.launch {
            signIns.distinctUntilChanged().collect { signIn ->
                // Another account on this phone: the previous person's notifications go first.
                if (signIn != null && userId != null && signIn.first != userId) tray.cancelAll()
                userId = signIn?.first
                sessionId = signIn?.second
                sync()
            }
        }
    }

    /** Firebase gave this phone a new token (FirebaseMessagingService.onNewToken). */
    suspend fun onNewToken(token: String) {
        knownToken = token
        sync()
    }

    /** IRL came to the front or the notification permission changed. */
    suspend fun sync() = lock.withLock {
        val sid = sessionId
        val token = if (sid != null && allowed() && tokens.configured) knownToken ?: tokens.token().also { knownToken = it }
        else knownToken
        when (PushRules.registration(tokens.configured, sid, allowed(), token, registered)) {
            PushRules.Registration.REGISTER -> {
                if (register(token!!, appVersion) is ApiResult.Success) registered = sid!! to token
            }
            PushRules.Registration.FORGET -> {
                registered = null
                knownToken = null
                tray.cancelAll()
                runCatching { tokens.delete() }
            }
            PushRules.Registration.NOTHING -> Unit
        }
    }
}
