package com.safesphere.app.feature.auth.domain

import kotlinx.coroutines.flow.Flow

/**
 * The single entry point the app uses for accounts. ViewModels know only this interface, never
 * Retrofit or DataStore, so the networking can change without touching any screen.
 */
interface AuthRepository {

    /** The signed-in user, or null when logged out. Survives app restarts. */
    val currentUser: Flow<AuthUser?>

    /** Creates an account on the server and signs in. */
    suspend fun register(name: String, email: String, password: String): AuthResult<AuthUser>

    /** Checks credentials on the server and signs in. */
    suspend fun login(email: String, password: String): AuthResult<AuthUser>

    /** Asks the server (GET /api/users/me) who the stored token belongs to. Clears the session if the token was rejected. */
    suspend fun refreshProfile(): AuthResult<AuthUser>

    /** Forgets the session on this device. */
    suspend fun logout()
}
