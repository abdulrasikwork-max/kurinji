package com.safesphere.app.feature.auth.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/** Where the signed-in session is kept. Production: DataStore. Tests: an in-memory fake. */
interface SessionStorage {
    /** Emits the saved session, or null when logged out. */
    val session: Flow<AuthSession?>

    suspend fun save(session: AuthSession)

    suspend fun clear()

    /**
     * Phase 10A: clears the session only while its token is still [token] and returns the session that was cleared
     * (null = nothing cleared). A 401 for an OLD token (one replaced by a password change or a new sign-in while the
     * request was on its way) must never sign out the newer session. A null [token] clears unconditionally.
     * Implementations should make the check and the clear one atomic step; this default is for simple fakes.
     */
    suspend fun clearIfToken(token: String?): AuthSession? {
        val current = session.first() ?: return null
        if (token != null && current.token != token) return null
        clear()
        return current
    }

    /**
     * Phase 10A.4: saves [replacement] only while the saved token is still [oldToken] (a renewal must never bring back a
     * session that was signed out or replaced meanwhile). True when saved. Implementations should make it atomic.
     */
    suspend fun replaceToken(oldToken: String, replacement: AuthSession): Boolean {
        if (session.first()?.token != oldToken) return false
        save(replacement)
        return true
    }

    /**
     * Phase 11 C1: saves the answer of a refresh only while the saved session still holds [usedRefreshToken] for the
     * same account. A late answer for an old sign-in can never overwrite a newer one, and the access token and the
     * rotated refresh secret are always written together. True when saved. Implementations should make it atomic.
     */
    suspend fun replaceAfterRefresh(usedRefreshToken: String, replacement: AuthSession): Boolean {
        val current = session.first() ?: return false
        if (current.refreshToken != usedRefreshToken || current.user.id != replacement.user.id) return false
        save(replacement)
        return true
    }

    /**
     * Phase 11 C1: ONE atomic read-change-write of the saved session ([transform] gets what is saved NOW; null = signed
     * out; returning null clears). Found on the emulator: reading the session, waiting for the network and then saving
     * a copy wrote an already-replaced refresh secret back over the rotated one (the server then saw it as reuse).
     * Every change that keeps the rest of the session (profile, deletion dates, a new access token) must use this.
     * [transform] must be quick and pure. Implementations should make it atomic; this default is for simple fakes.
     */
    suspend fun update(transform: (AuthSession?) -> AuthSession?): AuthSession? {
        val next = transform(session.first())
        if (next == null) clear() else save(next)
        return next
    }

    /**
     * Phase 11 C1: marks the session "sign in to sync" while its token is still [token] (null = whatever is saved).
     * Nothing is deleted. Returns the marked session (null = nothing marked: signed out or a newer sign-in).
     */
    suspend fun markNeedsSignIn(token: String?): AuthSession? {
        val current = session.first() ?: return null
        if (token != null && current.token != token) return null
        val marked = current.copy(needsSignIn = true)
        if (!current.needsSignIn) save(marked)
        return marked
    }
}
