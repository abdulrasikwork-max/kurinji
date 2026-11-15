package com.safesphere.app.feature.auth.domain

import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * The single entry point the app uses for accounts. ViewModels know only this interface, never
 * Retrofit or DataStore, so the networking can change without touching any screen.
 *
 * Phase N2 (decision N-7): the phone number and a texted code are the only way in; there is no e-mail and no password.
 * Sign-in and sign-up are one flow: [startSignIn] → [verifyCode] → (a new number only) [signUp].
 */
interface AuthRepository {

    /** The signed-in user, or null when logged out. Survives app restarts. */
    val currentUser: Flow<AuthUser?>

    /**
     * Phase 11 C1: true while the server needs a new sign-in ("sign in to sync"). The person is still signed in on
     * this phone: SOS, Safety Circle texts and a Safe Walk keep working; only server sync waits.
     */
    val signInNeeded: Flow<Boolean> get() = flowOf(false)

    /**
     * Texts a code to [phone] (already normalised, "+91…"). The answer is the same whether or not the number has an
     * account; nothing is saved on the phone.
     */
    suspend fun startSignIn(phone: String): AuthResult<PhoneChallenge>

    /**
     * The right code signs this phone in to the number's account (saved like any sign-in; a running SOS of this phone's
     * earlier sign-in to the same account moves over), or answers that the number has no account yet (a ticket).
     */
    suspend fun verifyCode(challenge: PhoneChallenge, code: String): AuthResult<SignInResult>

    /**
     * Phase N2: creates the account with [ticket], the person's real [name] and an adult [dateOfBirth] (Phase 13A: sent
     * once, stored nowhere), and signs this phone in.
     */
    suspend fun signUp(ticket: SignUpTicket, name: String, dateOfBirth: LocalDate): AuthResult<AuthUser>

    /** A new code to the same number (a cooldown and caps apply). */
    suspend fun resendCode(challenge: PhoneChallenge): AuthResult<PhoneChallenge>

    /** Another number, before the code was entered (a typo). */
    suspend fun changePhone(challenge: PhoneChallenge, phone: String): AuthResult<PhoneChallenge>

    /** Asks the server (GET /api/users/me) who the stored token belongs to. Clears the session if the token was rejected. */
    suspend fun refreshProfile(): AuthResult<AuthUser>

    /** Forgets the session on this device. */
    suspend fun logout()
}
