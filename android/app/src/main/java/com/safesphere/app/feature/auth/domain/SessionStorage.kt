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
}
