package com.safesphere.app.testing

import com.safesphere.app.feature.auth.domain.AuthError
import com.safesphere.app.feature.auth.domain.AuthRepository
import com.safesphere.app.feature.auth.domain.AuthResult
import com.safesphere.app.feature.auth.domain.AuthUser
import com.safesphere.app.feature.auth.domain.PhoneChallenge
import com.safesphere.app.feature.auth.domain.SignInResult
import com.safesphere.app.feature.auth.domain.SignUpTicket
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** In-memory test double: ViewModel tests never touch the network or DataStore. Phase N2: phone and code only. */
class FakeAuthRepository(signedInUser: AuthUser? = null) : AuthRepository {

    private val user = MutableStateFlow(signedInUser)

    var startResult: AuthResult<PhoneChallenge> = AuthResult.Success(CHALLENGE)
    var verifyResult: AuthResult<SignInResult> = AuthResult.Success(SignInResult.SignedIn(DEFAULT_USER))
    var signUpResult: AuthResult<AuthUser> = AuthResult.Success(DEFAULT_USER)
    var resendResult: AuthResult<PhoneChallenge> = AuthResult.Success(CHALLENGE)
    var changeResult: AuthResult<PhoneChallenge> = AuthResult.Success(CHALLENGE)
    var refreshResult: AuthResult<AuthUser> = AuthResult.Success(DEFAULT_USER)

    var startCalls = 0
        private set
    var verifyCalls = 0
        private set
    var signUpCalls = 0
        private set
    var resendCalls = 0
        private set
    var refreshCalls = 0
        private set
    var logoutCalls = 0
        private set
    var lastStartPhone: String? = null
        private set
    var lastVerifiedCode: String? = null
        private set
    var lastSignUpTicket: SignUpTicket? = null
        private set
    var lastSignUpName: String? = null
        private set
    var lastSignUpDateOfBirth: LocalDate? = null
        private set
    var lastChangedPhone: String? = null
        private set

    override val currentUser: Flow<AuthUser?> = user

    /** The signed-in user right now (null = nobody). */
    fun currentUserNow(): AuthUser? = user.value

    override suspend fun startSignIn(phone: String): AuthResult<PhoneChallenge> {
        startCalls++
        lastStartPhone = phone
        return startResult
    }

    override suspend fun verifyCode(challenge: PhoneChallenge, code: String): AuthResult<SignInResult> {
        verifyCalls++
        lastVerifiedCode = code
        return verifyResult.also {
            if (it is AuthResult.Success && it.data is SignInResult.SignedIn) {
                user.value = (it.data as SignInResult.SignedIn).user
            }
        }
    }

    override suspend fun signUp(ticket: SignUpTicket, name: String, dateOfBirth: LocalDate): AuthResult<AuthUser> {
        signUpCalls++
        lastSignUpTicket = ticket
        lastSignUpName = name
        lastSignUpDateOfBirth = dateOfBirth
        return signUpResult.also { if (it is AuthResult.Success) user.value = it.data }
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
        val DEFAULT_USER = AuthUser(id = 1, name = "Asha Devi", phoneHint = "••••••3210")

        /** A code was just sent to ••••••3210; a new one may be asked for in 60 s. */
        val CHALLENGE = PhoneChallenge(token = "t".repeat(43), phoneHint = "••••••3210",
            codeSent = true, resendInSeconds = 60, codeExpiresInSeconds = 300)

        /** The right code for a number without an account. */
        val TICKET = SignUpTicket(token = "k".repeat(43), phoneHint = "••••••3210", expiresInSeconds = 1800)

        fun failure(error: AuthError, message: String? = null) = AuthResult.Failure(error, message)
    }
}
