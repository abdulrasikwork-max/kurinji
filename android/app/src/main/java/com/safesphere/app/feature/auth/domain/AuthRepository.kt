package com.safesphere.app.feature.auth.domain

import java.time.LocalDate
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

    /**
     * Creates an account on the server. V25: it does NOT sign in: the server e-mails a link to [email], and the account
     * works once its owner opens it. Phase 13A: [dateOfBirth] must be 18+ (checked on the phone and on the server); it
     * is sent once and stored nowhere.
     */
    suspend fun register(name: String, email: String, password: String, dateOfBirth: LocalDate): AuthResult<VerificationPending>

    /** Checks credentials on the server and signs in. V25: [AuthError.EMAIL_NOT_VERIFIED] while the e-mail is unconfirmed. */
    suspend fun login(email: String, password: String): AuthResult<AuthUser>

    /**
     * V25: asks for a new verification e-mail (the previous link stops working). The server answers the same for every
     * address and password, so success only means "asked"; it sends only for the right password of an unconfirmed
     * account. Nothing is stored.
     */
    suspend fun resendVerification(email: String, password: String): AuthResult<Unit>

    /** Asks the server (GET /api/users/me) who the stored token belongs to. Clears the session if the token was rejected. */
    suspend fun refreshProfile(): AuthResult<AuthUser>

    /** Forgets the session on this device. */
    suspend fun logout()
}
