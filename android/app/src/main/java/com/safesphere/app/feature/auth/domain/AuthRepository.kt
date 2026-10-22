package com.safesphere.app.feature.auth.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * The single entry point the app uses for accounts. ViewModels know only this interface, never
 * Retrofit or DataStore, so the networking can change without touching any screen.
 */
interface AuthRepository {

    /** The signed-in user, or null when logged out. Survives app restarts. */
    val currentUser: Flow<AuthUser?>

    /**
     * Phase 11 C1: true while the server needs the password again ("sign in to sync"). The person is still signed in on
     * this phone: SOS, Safety Circle texts and a Safe Walk keep working; only server sync waits.
     */
    val signInNeeded: Flow<Boolean> get() = flowOf(false)

    /** Creates an account on the server and signs in. */
    suspend fun register(name: String, email: String, password: String): AuthResult<AuthUser>

    /** Checks credentials on the server and signs in. */
    suspend fun login(email: String, password: String): AuthResult<AuthUser>

    /** Asks the server (GET /api/users/me) who the stored token belongs to. Clears the session if the token was rejected. */
    suspend fun refreshProfile(): AuthResult<AuthUser>

    /** Forgets the session on this device. */
    suspend fun logout()
}
