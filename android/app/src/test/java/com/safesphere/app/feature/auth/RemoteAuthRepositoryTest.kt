package com.safesphere.app.feature.auth

import com.safesphere.app.feature.auth.data.RemoteAuthRepository
import com.safesphere.app.feature.auth.data.remote.RegisterRequestDto
import com.safesphere.app.feature.auth.domain.AuthError
import com.safesphere.app.feature.auth.domain.AuthResult
import com.safesphere.app.feature.auth.domain.AuthSession
import com.safesphere.app.feature.auth.domain.AuthUser
import com.safesphere.app.testing.FakeAuthApi
import com.safesphere.app.testing.FakeSessionStorage
import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests the repository against a fake server: no network, no emulator needed. */
class RemoteAuthRepositoryTest {

    private val api = FakeAuthApi()
    private val storage = FakeSessionStorage()
    private val repository = RemoteAuthRepository(api, storage)

    private fun failureOf(result: AuthResult<*>): AuthError =
        (result as AuthResult.Failure).error

    // ------------------------------------------------------------------ login

    @Test
    fun `login success saves token and user, never the password`() = runTest {
        api.loginOutcome = { FakeAuthApi.authResponse(token = "jwt-123") }

        val result = repository.login("asha@example.com", "my-password")

        assertTrue(result is AuthResult.Success)
        assertEquals("jwt-123", storage.savedSession?.token)
        assertEquals("asha@example.com", storage.savedSession?.user?.email)
        // The stored session has no password field at all, and the request object redacts it from logs.
        assertFalse(api.lastLoginRequest.toString().contains("my-password"))
        assertEquals("asha@example.com", repository.currentUser.first()?.email)
    }

    @Test
    fun `login with wrong credentials maps 401 to INVALID_CREDENTIALS and saves nothing`() = runTest {
        api.loginOutcome = { throw FakeAuthApi.httpError(401) }

        val result = repository.login("asha@example.com", "wrong")

        assertEquals(AuthError.INVALID_CREDENTIALS, failureOf(result))
        assertNull(storage.savedSession)
        assertEquals(0, storage.saveCalls)
    }

    @Test
    fun `no network maps to NO_CONNECTION`() = runTest {
        api.loginOutcome = { throw IOException("unreachable") }

        assertEquals(AuthError.NO_CONNECTION, failureOf(repository.login("a@b.com", "x")))
    }

    @Test
    fun `server error maps 500 to SERVER`() = runTest {
        api.loginOutcome = { throw FakeAuthApi.httpError(500) }

        assertEquals(AuthError.SERVER, failureOf(repository.login("a@b.com", "x")))
    }

    @Test
    fun `too many sign-in attempts maps 429 to TOO_MANY_ATTEMPTS`() = runTest {
        api.loginOutcome = { throw FakeAuthApi.httpError(429) }

        assertEquals(AuthError.TOO_MANY_ATTEMPTS, failureOf(repository.login("a@b.com", "x")))
        assertNull(storage.savedSession)
    }

    @Test
    fun `an unreadable response maps to UNKNOWN instead of crashing`() = runTest {
        api.loginOutcome = { throw IllegalStateException("malformed json") }

        assertEquals(AuthError.UNKNOWN, failureOf(repository.login("a@b.com", "x")))
    }

    // ------------------------------------------------------------------ register

    @Test
    fun `register success signs the new user in`() = runTest {
        val result = repository.register("Asha Devi", "asha@example.com", "longenough1")

        assertTrue(result is AuthResult.Success)
        assertEquals("Asha Devi", api.lastRegisterRequest?.name)
        assertEquals("test-token", storage.savedSession?.token)
    }

    @Test
    fun `register with an existing email maps 409 to EMAIL_ALREADY_USED`() = runTest {
        api.registerOutcome = { throw FakeAuthApi.httpError(409) }

        val result = repository.register("Asha Devi", "asha@example.com", "longenough1")

        assertEquals(AuthError.EMAIL_ALREADY_USED, failureOf(result))
        assertNull(storage.savedSession)
    }

    @Test
    fun `register with rejected input maps 400 to VALIDATION`() = runTest {
        api.registerOutcome = { throw FakeAuthApi.httpError(400) }

        assertEquals(AuthError.VALIDATION, failureOf(repository.register("A", "a@b.com", "longenough1")))
    }

    // ------------------------------------------------------------------ profile refresh

    @Test
    fun `refreshProfile updates the stored user and keeps the token`() = runTest {
        val old = AuthSession("jwt-abc", AuthUser(7, "Old Name", "asha@example.com"))
        val storage = FakeSessionStorage(old)
        api.meOutcome = { FakeAuthApi.userDto(name = "New Name") }

        val result = RemoteAuthRepository(api, storage).refreshProfile()

        assertTrue(result is AuthResult.Success)
        assertEquals("New Name", storage.savedSession?.user?.name)
        assertEquals("jwt-abc", storage.savedSession?.token)
    }

    @Test
    fun `refreshProfile with a rejected token clears the session`() = runTest {
        val storage = FakeSessionStorage(AuthSession("expired", AuthUser(7, "Asha", "asha@example.com")))
        api.meOutcome = { throw FakeAuthApi.httpError(401) }

        val result = RemoteAuthRepository(api, storage).refreshProfile()

        assertEquals(AuthError.UNAUTHORIZED, failureOf(result))
        assertNull(storage.savedSession)
    }

    @Test
    fun `refreshProfile while offline keeps the session`() = runTest {
        val storage = FakeSessionStorage(AuthSession("jwt", AuthUser(7, "Asha", "asha@example.com")))
        api.meOutcome = { throw IOException("offline") }

        val result = RemoteAuthRepository(api, storage).refreshProfile()

        assertEquals(AuthError.NO_CONNECTION, failureOf(result))
        assertEquals("jwt", storage.savedSession?.token)
    }

    @Test
    fun `refreshProfile without a session does not call the server`() = runTest {
        val result = repository.refreshProfile()

        assertEquals(AuthError.UNAUTHORIZED, failureOf(result))
        assertEquals(0, api.meCalls)
    }

    // ------------------------------------------------------------------ logout

    @Test
    fun `logout clears the stored session`() = runTest {
        val storage = FakeSessionStorage(AuthSession("jwt", AuthUser(7, "Asha", "asha@example.com")))

        RemoteAuthRepository(api, storage).logout()

        assertNull(storage.savedSession)
        assertEquals(1, storage.clearCalls)
    }

    @Test
    fun `request objects never print passwords`() {
        val text = RegisterRequestDto("Asha", "asha@example.com", "super-secret-pw").toString()

        assertFalse(text.contains("super-secret-pw"))
        assertFalse(text.contains("asha@example.com"))
    }
}
