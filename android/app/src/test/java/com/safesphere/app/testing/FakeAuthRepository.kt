package com.safesphere.app.testing

import com.safesphere.app.feature.auth.domain.AuthError
import com.safesphere.app.feature.auth.domain.AuthRepository
import com.safesphere.app.feature.auth.domain.AuthResult
import com.safesphere.app.feature.auth.domain.AuthUser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** In-memory test double: ViewModel tests never touch the network or DataStore. */
class FakeAuthRepository(signedInUser: AuthUser? = null) : AuthRepository {

    private val user = MutableStateFlow(signedInUser)

    var loginResult: AuthResult<AuthUser> = AuthResult.Success(DEFAULT_USER)
    var registerResult: AuthResult<AuthUser> = AuthResult.Success(DEFAULT_USER)
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

    override val currentUser: Flow<AuthUser?> = user

    override suspend fun register(name: String, email: String, password: String): AuthResult<AuthUser> {
        registerCalls++
        lastRegisterName = name
        return registerResult.also { if (it is AuthResult.Success) user.value = it.data }
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
