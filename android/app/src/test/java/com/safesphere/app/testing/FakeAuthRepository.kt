package com.safesphere.app.testing

import com.safesphere.app.feature.auth.domain.AuthError
import com.safesphere.app.feature.auth.domain.AuthRepository
import com.safesphere.app.feature.auth.domain.AuthResult
import com.safesphere.app.feature.auth.domain.AuthUser
import com.safesphere.app.feature.auth.domain.PhoneChallenge
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** In-memory test double: ViewModel tests never touch the network or DataStore. */
class FakeAuthRepository(signedInUser: AuthUser? = null) : AuthRepository {

    private val user = MutableStateFlow(signedInUser)

    var loginResult: AuthResult<AuthUser> = AuthResult.Success(DEFAULT_USER)
    /** Phase 17B: sign-up never signs in; it answers with a phone verification (a code was sent). */
    var registerResult: AuthResult<PhoneChallenge> = AuthResult.Success(CHALLENGE)
    var startResult: AuthResult<PhoneChallenge> = AuthResult.Success(CHALLENGE)
    var verifyResult: AuthResult<AuthUser> = AuthResult.Success(DEFAULT_USER)
    var resendResult: AuthResult<PhoneChallenge> = AuthResult.Success(CHALLENGE)
    var changeResult: AuthResult<PhoneChallenge> = AuthResult.Success(CHALLENGE)
    var refreshResult: AuthResult<AuthUser> = AuthResult.Success(DEFAULT_USER)

    var loginCalls = 0
        private set
    var registerCalls = 0
        private set
    var startCalls = 0
        private set
    var verifyCalls = 0
        private set
    var resendCalls = 0
        private set
    var refreshCalls = 0
        private set
    var logoutCalls = 0
        private set
    var lastLoginEmail: String? = null
        private set
    var lastRegisterName: String? = null
        private set
    var lastRegisterDateOfBirth: LocalDate? = null
        private set
    var lastRegisterPhone: String? = null
        private set
    var lastStartPassword: String? = null
        private set
    var lastVerifiedCode: String? = null
        private set
    var lastChangedPhone: String? = null
        private set

    override val currentUser: Flow<AuthUser?> = user

    /** The signed-in user right now (null = nobody). */
    fun currentUserNow(): AuthUser? = user.value

    override suspend fun register(
        name: String,
        email: String,
        password: String,
        dateOfBirth: LocalDate,
        phone: String
    ): AuthResult<PhoneChallenge> {
        registerCalls++
        lastRegisterName = name
        lastRegisterDateOfBirth = dateOfBirth
        lastRegisterPhone = phone
        return registerResult
    }

    override suspend fun login(email: String, password: String): AuthResult<AuthUser> {
        loginCalls++
        lastLoginEmail = email
        return loginResult.also { if (it is AuthResult.Success) user.value = it.data }
    }

    override suspend fun startPhoneVerification(email: String, password: String, phone: String?): AuthResult<PhoneChallenge> {
        startCalls++
        lastStartPassword = password
        return startResult
    }

    override suspend fun verifyPhone(challenge: PhoneChallenge, code: String): AuthResult<AuthUser> {
        verifyCalls++
        lastVerifiedCode = code
        return verifyResult.also { if (it is AuthResult.Success) user.value = it.data }
    }

    override suspend fun resendCode(challenge: PhoneChallenge): AuthResult<PhoneChallenge> {
        resendCalls++
        return resendResult
    }

    override suspend fun changePhone(challenge: PhoneChallenge, phone: String): AuthResult<PhoneChallenge> {
        lastChangedPhone = phone
        return changeResult
    }

    override suspend fun refreshProfile(): AuthResult<AuthUser> {
        refreshCalls++
        return refreshResult
    }

    override suspend fun logout() {
        logoutCalls++
        user.value = null
    }

    companion object {
        val DEFAULT_USER = AuthUser(id = 1, name = "Asha Devi", email = "asha@example.com")

        /** A code was just sent to ••••••3210; a new one may be asked for in 60 s. */
        val CHALLENGE = PhoneChallenge(token = "t".repeat(43), phoneHint = "••••••3210",
            needsPhone = false, codeSent = true, resendInSeconds = 60, codeExpiresInSeconds = 300)

        fun failure(error: AuthError, message: String? = null) = AuthResult.Failure(error, message)
    }
}
