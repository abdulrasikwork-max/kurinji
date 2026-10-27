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
    fun `register success does NOT sign in, it only returns the phone verification`() = runTest {
        val result = repository.register("Asha Devi", "Asha@Example.com", "longenough1", ADULT, "+919876543210")

        val challenge = (result as AuthResult.Success).data
        assertEquals("c".repeat(43), challenge.token)
        assertTrue(challenge.codeSent)
        assertFalse(challenge.toString().contains("ccc")) // the token never reaches a log line
        assertEquals("Asha Devi", api.lastRegisterRequest?.name)
        assertEquals("+919876543210", api.lastRegisterRequest?.phone)
        assertEquals("1998-05-14", api.lastRegisterRequest?.dateOfBirth) // Phase 13A: ISO date, as the server reads it
        assertFalse(api.lastRegisterRequest.toString().contains("9876543210"))
        assertNull(storage.savedSession) // Phase 17B: no session before the phone is verified
        assertEquals(0, storage.saveCalls)
    }

    // ------------------------------------------------------------------ Phase 17B phone verification

    @Test
    fun `login of an unverified phone maps 403 PHONE_NOT_VERIFIED and saves nothing`() = runTest {
        api.loginOutcome = { throw FakeAuthApi.httpError(403, "PHONE_NOT_VERIFIED") }

        assertEquals(AuthError.PHONE_NOT_VERIFIED, failureOf(repository.login("asha@example.com", "right-password")))
        assertNull(storage.savedSession)
    }

    @Test
    fun `a 403 without that code is not mistaken for an unverified phone`() = runTest {
        api.loginOutcome = { throw FakeAuthApi.httpError(403) }

        assertEquals(AuthError.UNKNOWN, failureOf(repository.login("asha@example.com", "x")))
    }

    @Test
    fun `the right code signs in and saves the session like a sign-in`() = runTest {
        val challenge = (repository.register("A", "a@b.com", "longenough1", ADULT, "+919876543210")
            as AuthResult.Success).data

        val result = repository.verifyPhone(challenge, "123456")

        assertTrue(result is AuthResult.Success)
        assertEquals("verified-token", storage.savedSession?.token)
        assertEquals("new-refresh", storage.savedSession?.refreshToken)
        assertEquals("123456", api.lastVerifyRequest?.code)
        assertFalse(api.lastVerifyRequest.toString().contains("123456"))
    }

    @Test
    fun `a wrong code keeps the server's words, other refusals map to their codes`() = runTest {
        val challenge = (repository.register("A", "a@b.com", "longenough1", ADULT, "+919876543210")
            as AuthResult.Success).data
        api.verifyOutcome = { throw FakeAuthApi.httpError(400, "OTP_INVALID", "That code is not right. 2 tries left.") }
        val wrong = repository.verifyPhone(challenge, "000000") as AuthResult.Failure
        assertEquals(AuthError.OTP_INVALID, wrong.error)
        assertEquals("That code is not right. 2 tries left.", wrong.message)

        for ((code, error) in listOf("OTP_EXPIRED" to AuthError.OTP_EXPIRED, "CHALLENGE_INVALID" to AuthError.CHALLENGE_INVALID,
                "PHONE_IN_USE" to AuthError.PHONE_IN_USE, "PHONE_REQUIRED" to AuthError.PHONE_REQUIRED)) {
            api.verifyOutcome = { throw FakeAuthApi.httpError(400, code, "server text") }
            val failure = repository.verifyPhone(challenge, "000000") as AuthResult.Failure
            assertEquals(error, failure.error)
            assertNull(failure.message) // only case-dependent words are shown from the server
        }
        api.verifyOutcome = { throw FakeAuthApi.httpError(429, "OTP_LOCKED") }
        assertEquals(AuthError.OTP_LOCKED, failureOf(repository.verifyPhone(challenge, "000000")))
        assertNull(storage.savedSession)
    }

    @Test
    fun `start, resend and change answer with a verification, and an answer without a token fails closed`() = runTest {
        val challenge = (repository.startPhoneVerification("a@b.com", "pw-123") as AuthResult.Success).data
        assertFalse(api.lastStartRequest.toString().contains("pw-123"))
        api.startOutcome = { throw FakeAuthApi.httpError(401) }
        assertEquals(AuthError.INVALID_CREDENTIALS, failureOf(repository.startPhoneVerification("a@b.com", "wrong")))

        assertTrue(repository.resendCode(challenge) is AuthResult.Success)
        api.resendOutcome = { throw FakeAuthApi.httpError(429, "OTP_WAIT", "You can ask for a new code in 40 seconds.") }
        val wait = repository.resendCode(challenge) as AuthResult.Failure
        assertEquals(AuthError.OTP_WAIT, wait.error)
        assertEquals("You can ask for a new code in 40 seconds.", wait.message)

        api.changeOutcome = { FakeAuthApi.challengeDto(token = null) }
        assertEquals(AuthError.UNKNOWN, failureOf(repository.changePhone(challenge, "+919876500000")))
        api.changeOutcome = { throw IOException("offline") }
        assertEquals(AuthError.NO_CONNECTION, failureOf(repository.changePhone(challenge, "+919876500000")))
    }

    @Test
    fun `register with an existing email maps 409 to EMAIL_ALREADY_USED`() = runTest {
        api.registerOutcome = { throw FakeAuthApi.httpError(409) }

        val result = repository.register("Asha Devi", "asha@example.com", "longenough1", ADULT, "+919876543210")

        assertEquals(AuthError.EMAIL_ALREADY_USED, failureOf(result))
        assertNull(storage.savedSession)
    }

    @Test
    fun `register with rejected input maps 400 to VALIDATION`() = runTest {
        api.registerOutcome = { throw FakeAuthApi.httpError(400) }

        assertEquals(AuthError.VALIDATION,
            failureOf(repository.register("A", "a@b.com", "longenough1", ADULT, "+919876543210")))
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
    fun `C1 - refreshProfile with an expired token and no refresh secret keeps the session and asks for a sign-in`() = runTest {
        val storage = FakeSessionStorage(AuthSession("expired", AuthUser(7, "Asha", "asha@example.com")))
        api.meOutcome = { throw FakeAuthApi.httpError(401) }

        val result = RemoteAuthRepository(api, storage).refreshProfile()

        assertEquals(AuthError.UNAUTHORIZED, failureOf(result))
        assertEquals("expired", storage.savedSession?.token)
        assertEquals(true, storage.savedSession?.needsSignIn)
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
        val text = RegisterRequestDto("Asha", "asha@example.com", "super-secret-pw", "1998-05-14", "+919876543210").toString()

        assertFalse(text.contains("super-secret-pw"))
        assertFalse(text.contains("asha@example.com"))
        assertFalse(text.contains("1998-05-14"))
    }

    private companion object {
        val ADULT: java.time.LocalDate = java.time.LocalDate.of(1998, 5, 14)
    }
}
