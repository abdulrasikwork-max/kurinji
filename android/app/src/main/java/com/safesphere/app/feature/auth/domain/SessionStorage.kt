package com.safesphere.app.feature.auth.domain

import kotlinx.coroutines.flow.Flow

/** Where the signed-in session is kept. Production: DataStore. Tests: an in-memory fake. */
interface SessionStorage {
    /** Emits the saved session, or null when logged out. */
    val session: Flow<AuthSession?>

    suspend fun save(session: AuthSession)

    suspend fun clear()
}
