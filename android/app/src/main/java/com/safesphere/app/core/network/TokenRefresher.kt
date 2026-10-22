package com.safesphere.app.core.network

/** Phase 11 C1: what came of asking the server for a new access token with the stored refresh secret. */
sealed interface RefreshOutcome {
    /** A newer access token is saved (by this call, or by another one that was quicker). Retry the request. */
    data object Refreshed : RefreshOutcome

    /** The server could not be reached or had a problem: nothing changed, try again later. */
    data object TryLater : RefreshOutcome

    /** The server refused the refresh secret ([reason]: SESSION_REVOKED, ACCOUNT_DELETED or ACCOUNT_UNAVAILABLE). */
    data class Refused(val reason: SessionEndReason) : RefreshOutcome

    /** Nothing to refresh with: signed out, a newer sign-in, already "sign in to sync", or no refresh secret. */
    data object NotPossible : RefreshOutcome
}

/** Gets a new access token after [rejectedToken] was refused (null = whatever is saved). Single-flight. */
fun interface TokenRefresher {
    suspend fun refreshAfter(rejectedToken: String?): RefreshOutcome
}
