package com.safesphere.app.core.network

import com.safesphere.app.feature.auth.domain.SessionStorage
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import retrofit2.Response

/** Why the server refused the saved token (Phase 10A: the WWW-Authenticate error_description). */
enum class SessionEndReason {
    /** Expired, missing or not accepted for an unstated reason (the Phase 2 meaning of a 401). */
    EXPIRED,
    /** The password was changed (on this or another phone): every older session ends. */
    CREDENTIALS_CHANGED,
    /** The account was deleted after its grace period: this phone removes the account's data. */
    ACCOUNT_DELETED,
    /** The account was switched off by the operator. */
    ACCOUNT_UNAVAILABLE
}

/** The account whose session ended, and why. */
data class SessionEnd(val userId: Long, val reason: SessionEndReason)

/**
 * The ONE place that ends the saved session after a 401 (used by ApiCallHandler and RemoteAuthRepository).
 *
 *  - It clears the session only when the rejected request carried the token that is still saved
 *    ([SessionStorage.clearIfToken]): a late 401 for an old token never signs out a newer session.
 *  - While a password change is on its way ([swapping]), a 401 for the token being replaced is ignored: in that
 *    moment the server may already refuse the old token although the new one has not arrived yet. During an SOS or a
 *    Safe Walk the server refuses a password change anyway, so this only protects ordinary background calls.
 *  - It publishes [ended] (who, why) so the app can explain the sign-out and, for a deleted account, remove that
 *    account's data from this phone.
 */
class SessionEnder(private val storage: SessionStorage) {

    private val _ended = MutableSharedFlow<SessionEnd>(extraBufferCapacity = 8)
    val ended: SharedFlow<SessionEnd> = _ended.asSharedFlow()

    private val _lastReason = MutableStateFlow<SessionEndReason?>(null)

    /** The reason of the most recent sign-out (the session-expired dialog words itself after it). */
    val lastReason: StateFlow<SessionEndReason?> = _lastReason.asStateFlow()

    @Volatile
    private var replacing: String? = null

    /** Runs [block] (a password change) while 401s for [oldToken] are not taken as a sign-out. */
    suspend fun <T> swapping(oldToken: String, block: suspend () -> T): T {
        replacing = oldToken
        try {
            return block()
        } finally {
            replacing = null
        }
    }

    /** A 401 arrived for [response]'s request. */
    suspend fun onUnauthorized(response: Response<*>?) {
        val sent = bearerToken(response)
        if (sent != null && sent == replacing) return
        end(sent, reasonOf(response?.headers()?.get("WWW-Authenticate")))
    }

    /** Ends the session if it still holds [token] (null = whatever is saved) and reports [reason]. */
    suspend fun end(token: String?, reason: SessionEndReason) {
        val cleared = storage.clearIfToken(token) ?: return
        _lastReason.value = reason
        _ended.emit(SessionEnd(cleared.user.id, reason))
    }

    companion object {
        fun bearerToken(response: Response<*>?): String? =
            response?.raw()?.request?.header("Authorization")
                ?.takeIf { it.startsWith("Bearer ") }
                ?.removePrefix("Bearer ")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }

        /** Reads RFC 6750 `error_description="..."`; anything unknown is EXPIRED (the old meaning). */
        fun reasonOf(header: String?): SessionEndReason {
            val description = header?.let { DESCRIPTION.find(it)?.groupValues?.get(1) }
            return when (description) {
                "credentials_changed" -> SessionEndReason.CREDENTIALS_CHANGED
                "account_deleted" -> SessionEndReason.ACCOUNT_DELETED
                "account_unavailable" -> SessionEndReason.ACCOUNT_UNAVAILABLE
                else -> SessionEndReason.EXPIRED
            }
        }

        private val DESCRIPTION = Regex("error_description=\"([a-z_]+)\"")
    }
}
