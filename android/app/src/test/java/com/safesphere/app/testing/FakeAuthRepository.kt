package com.safesphere.app.testing

import com.safesphere.app.feature.auth.domain.AuthError
import com.safesphere.app.feature.auth.domain.AuthRepository
import com.safesphere.app.feature.auth.domain.AuthResult
import com.safesphere.app.feature.auth.domain.AuthUser
import com.safesphere.app.feature.auth.domain.VerificationPending
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** In-memory test double: ViewModel tests never touch the network or DataStore. */
class FakeAuthRepository(signedInUser: AuthUser? = null) : AuthRepository {

    private val user = MutableStateFlow(signedInUser)

    var loginResult: AuthResult<AuthUser> = AuthResult.Success(DEFAULT_USER)
    /** V25: sign-up never signs in; it answers that a verification e-mail went to the (normalised) address. */
    var registerResult: AuthResult<VerificationPending> = AuthResult.Success(VerificationPending("asha@example.com"))
    var resendResult: AuthResult<Unit> = AuthResult.Success(Unit)
    var resendCalls = 0
        private set
    var lastResendEmail: String? = null
        private set
    var lastResendPassword: String? = null
        private set
    var refreshResult: AuthResult<AuthUser> = AuthResult.Success(DEFAULT_USER)

    var loginCalls = 0
        private set
    var registerCalls = 0
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

    override val currentUser: Flow<AuthUser?> = user

    /** The signed-in user right now (null = nobody). */
    fun currentUserNow(): AuthUser? = user.value

    override suspend fun register(
        name: String,
        email: String,
        password: String,
        dateOfBirth: LocalDate
    ): AuthResult<VerificationPending> {
        registerCalls++
        lastRegisterName = name
        lastRegisterDateOfBirth = dateOfBirth
        return registerResult
    }

    override suspend fun resendVerification(email: String, password: String): AuthResult<Unit> {
        resendCalls++
        lastResendEmail = email
        lastResendPassword = password
        return resendResult
    }

    override suspend fun login(email: String, password: String): AuthResult<AuthUser> {
        loginCalls++
        lastLoginEmail = email
        return loginResult.also { if (it is AuthResult.Success) user.value = it.data }
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

        fun failure(error: AuthError) = AuthResult.Failure(error)
    }
}
