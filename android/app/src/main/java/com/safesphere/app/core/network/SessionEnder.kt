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
    ACCOUNT_UNAVAILABLE,
    /** Phase 11 C1: this phone's sign-in ended on the server (signed out there, or a refresh secret was reused). */
    SESSION_REVOKED
}

/** The account whose session ended, and why. */
data class SessionEnd(val userId: Long, val reason: SessionEndReason)

/** Phase 11 C1: what a 401 led to. */
enum class UnauthorizedOutcome {
    /** A newer access token is saved: the request may be sent once more. */
    RETRY,
    /** The token expired and the server could not be reached for a new one: treat the request as offline. */
    OFFLINE,
    /** "Sign in to sync": the session and every bit of local safety data stay; server sync waits for a sign-in. */
    SIGN_IN_NEEDED,
    /** Signed out (password changed elsewhere, account deleted or disabled): the Phase 10A rules apply. */
    SIGNED_OUT,
    /** The 401 was for an old token (a newer session exists) or arrived during a password change: nothing to do. */
    IGNORED
}

/**
 * The ONE place that decides what a 401 means (used by ApiCallHandler, RemoteAuthRepository and the renewer).
 *
 * Phase 11 C1 — EXPIRED is not REVOKED:
 *  - no reason (the access token expired): refresh with the stored secret ([TokenRefresher], single-flight). If the
 *    server cannot be reached, nothing changes (OFFLINE). If it refuses, the session becomes "sign in to sync": NOTHING
 *    is deleted, so a running SOS, its unsent ending, the Safety Circle copy and a Safe Walk all stay on the phone.
 *  - session_revoked (this phone's sign-in ended on the server): "sign in to sync" as well.
 *  - credentials_changed, account_deleted, account_unavailable: a deliberate security event; the session is cleared as
 *    before (Phase 10A), and a deleted account's data is removed from the phone.
 *
 * Unchanged from Phase 10A: only the token that is still saved is ever acted on (a late 401 for an old token never
 * touches a newer session), and while a password change swaps tokens ([swapping]) the old token's 401 is ignored.
 */
class SessionEnder(
    private val storage: SessionStorage,
    private val refresher: TokenRefresher? = null
) {

    private val _ended = MutableSharedFlow<SessionEnd>(extraBufferCapacity = 8)
    val ended: SharedFlow<SessionEnd> = _ended.asSharedFlow()

    private val _lastReason = MutableStateFlow<SessionEndReason?>(null)

    /** The reason of the most recent sign-out or "sign in to sync" (the dialogs word themselves after it). */
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

    /**
     * A 401 arrived for [response]'s request. [afterRefresh] = the request was already sent again with a freshly
     * refreshed token and still refused: no second refresh (no loop); the session needs a sign-in.
     */
    suspend fun onUnauthorized(response: Response<*>?, afterRefresh: Boolean = false): UnauthorizedOutcome {
        val sent = bearerToken(response)
        if (sent != null && sent == replacing) return UnauthorizedOutcome.IGNORED
        return when (val reason = reasonOf(response?.headers()?.get("WWW-Authenticate"))) {
            SessionEndReason.CREDENTIALS_CHANGED, SessionEndReason.ACCOUNT_DELETED, SessionEndReason.ACCOUNT_UNAVAILABLE ->
                if (end(sent, reason)) UnauthorizedOutcome.SIGNED_OUT else UnauthorizedOutcome.IGNORED
            SessionEndReason.SESSION_REVOKED -> needSignIn(sent, reason)
            SessionEndReason.EXPIRED ->
                if (afterRefresh) needSignIn(sent, SessionEndReason.EXPIRED) else refresh(sent)
        }
    }

    /**
     * Gets a new access token for [token] (null = whatever is saved): on a 401, at app start and before the token runs
     * out (SessionRenewer). A refusal ends in "sign in to sync" (or a sign-out for a deleted / disabled account).
     */
    suspend fun refresh(token: String?): UnauthorizedOutcome {
        val refresher = refresher ?: return needSignIn(token, SessionEndReason.EXPIRED)
        return when (val outcome = refresher.refreshAfter(token)) {
            RefreshOutcome.Refreshed -> UnauthorizedOutcome.RETRY
            RefreshOutcome.TryLater -> UnauthorizedOutcome.OFFLINE
            is RefreshOutcome.Refused -> when (outcome.reason) {
                SessionEndReason.ACCOUNT_DELETED, SessionEndReason.ACCOUNT_UNAVAILABLE ->
                    if (end(token, outcome.reason)) UnauthorizedOutcome.SIGNED_OUT else UnauthorizedOutcome.IGNORED
                else -> needSignIn(token, outcome.reason)
            }
            RefreshOutcome.NotPossible -> needSignIn(token, SessionEndReason.EXPIRED)
        }
    }

    /** Ends the session if it still holds [token] (null = whatever is saved) and reports [reason]. True when ended. */
    suspend fun end(token: String?, reason: SessionEndReason): Boolean {
        val cleared = storage.clearIfToken(token) ?: return false
        _lastReason.value = reason
        _ended.emit(SessionEnd(cleared.user.id, reason))
        return true
    }

    private suspend fun needSignIn(token: String?, reason: SessionEndReason): UnauthorizedOutcome {
        storage.markNeedsSignIn(token) ?: return UnauthorizedOutcome.IGNORED
        _lastReason.value = reason
        return UnauthorizedOutcome.SIGN_IN_NEEDED
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
                "session_revoked" -> SessionEndReason.SESSION_REVOKED
                else -> SessionEndReason.EXPIRED
            }
        }

        private val DESCRIPTION = Regex("error_description=\"([a-z_]+)\"")
    }
}
