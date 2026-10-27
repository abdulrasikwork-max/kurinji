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
     * Creates an account on the server. Phase 17B: it does NOT sign in: the server texts a code to [phone] and the
     * answer is the verification to finish with [verifyPhone]. Phase 13A: [dateOfBirth] must be 18+ (checked on the
     * phone and on the server); it is sent once and stored nowhere.
     */
    suspend fun register(
        name: String,
        email: String,
        password: String,
        dateOfBirth: LocalDate,
        phone: String
    ): AuthResult<PhoneChallenge>

    /** Checks credentials on the server and signs in. Phase 17B: [AuthError.PHONE_NOT_VERIFIED] until a phone is verified. */
    suspend fun login(email: String, password: String): AuthResult<AuthUser>

    /**
     * Phase 17B: the right password of an account without a verified phone (an interrupted sign-up, or an account from
     * before 17B) gets a verification; [phone] is needed only when the account has no number yet.
     */
    suspend fun startPhoneVerification(email: String, password: String, phone: String? = null): AuthResult<PhoneChallenge>

    /** Phase 17B: the right code verifies the number and signs this phone in (saved like a sign-in). */
    suspend fun verifyPhone(challenge: PhoneChallenge, code: String): AuthResult<AuthUser>

    /** Phase 17B: a new code to the same number (a cooldown and caps apply). */
    suspend fun resendCode(challenge: PhoneChallenge): AuthResult<PhoneChallenge>

    /** Phase 17B: another number, before the first one was verified. */
    suspend fun changePhone(challenge: PhoneChallenge, phone: String): AuthResult<PhoneChallenge>

    /** Asks the server (GET /api/users/me) who the stored token belongs to. Clears the session if the token was rejected. */
    suspend fun refreshProfile(): AuthResult<AuthUser>

    /** Forgets the session on this device. */
    suspend fun logout()
}
