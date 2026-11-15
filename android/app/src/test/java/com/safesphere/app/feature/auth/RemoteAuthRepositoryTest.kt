package com.safesphere.app.feature.auth

import com.safesphere.app.feature.auth.data.RemoteAuthRepository
import com.safesphere.app.feature.auth.data.remote.SignUpRequestDto
import com.safesphere.app.feature.auth.data.remote.VerifyResponseDto
import com.safesphere.app.feature.auth.domain.AuthError
import com.safesphere.app.feature.auth.domain.AuthResult
import com.safesphere.app.feature.auth.domain.AuthSession
import com.safesphere.app.feature.auth.domain.AuthUser
import com.safesphere.app.feature.auth.domain.SignInResult
import com.safesphere.app.testing.FakeAuthApi
import com.safesphere.app.testing.FakeAuthRepository
import com.safesphere.app.testing.FakeSessionStorage
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests the repository against a fake server: no network, no emulator needed. Phase N2: phone and code only. */
class RemoteAuthRepositoryTest {

    private val api = FakeAuthApi()
    private val storage = FakeSessionStorage()
    private val repository = RemoteAuthRepository(api, storage)
    private val challenge = FakeAuthRepository.CHALLENGE

    private fun failureOf(result: AuthResult<*>): AuthError =
        (result as AuthResult.Failure).error

    // ------------------------------------------------------------------ start

    @Test
    fun `start sends the number and answers with the code request, saving nothing`() = runTest {
        val result = repository.startSignIn("+919876543210")

        assertTrue(result is AuthResult.Success)
        assertEquals("+919876543210", api.lastStartRequest?.phone)
        assertEquals("••••••3210", (result as AuthResult.Success).data.phoneHint)
        assertEquals(0, storage.saveCalls)
    }

    @Test
    fun `start, resend and change fail closed on an answer without a token`() = runTest {
        api.startOutcome = { FakeAuthApi.challengeDto(token = null) }
        api.resendOutcome = { FakeAuthApi.challengeDto(token = " ") }
        api.changeOutcome = { FakeAuthApi.challengeDto(token = null) }

        assertEquals(AuthError.UNKNOWN, failureOf(repository.startSignIn("+919876543210")))
        assertEquals(AuthError.UNKNOWN, failureOf(repository.resendCode(challenge)))
        assertEquals(AuthError.UNKNOWN, failureOf(repository.changePhone(challenge, "+919876543211")))
    }

    @Test
    fun `network and server problems map to their errors`() = runTest {
        api.startOutcome = { throw IOException("unreachable") }
        assertEquals(AuthError.NO_CONNECTION, failureOf(repository.startSignIn("+919876543210")))
        api.startOutcome = { throw FakeAuthApi.httpError(500) }
        assertEquals(AuthError.SERVER, failureOf(repository.startSignIn("+919876543210")))
        api.startOutcome = { throw FakeAuthApi.httpError(429) }
        assertEquals(AuthError.TOO_MANY_ATTEMPTS, failureOf(repository.startSignIn("+919876543210")))
    }

    // ------------------------------------------------------------------ verify

    @Test
    fun `the right code for a known number signs in and saves the session`() = runTest {
        val result = repository.verifyCode(challenge, "123456")

        val signedIn = (result as AuthResult.Success).data as SignInResult.SignedIn
        assertEquals("Asha Devi", signedIn.user.name)
        assertEquals("verified-token", storage.savedSession?.token)
        assertEquals("new-refresh", storage.savedSession?.refreshToken)
        assertEquals("••••••3210", storage.savedSession?.user?.phoneHint)
        assertEquals("Asha Devi", repository.currentUser.first()?.name)
        assertEquals("123456", api.lastVerifyRequest?.code)
    }

    @Test
    fun `the right code for a new number gives a ticket and signs nobody in`() = runTest {
        api.verifyOutcome = { FakeAuthApi.signUpNeeded() }

        val result = repository.verifyCode(challenge, "123456")

        val needed = (result as AuthResult.Success).data as SignInResult.SignUpNeeded
        assertEquals("k".repeat(43), needed.ticket.token)
        assertEquals(1800, needed.ticket.expiresInSeconds)
        assertNull(storage.savedSession)
        assertEquals(0, storage.saveCalls)
    }

    @Test
    fun `an incomplete or unknown verify answer fails closed`() = runTest {
        api.verifyOutcome = { FakeAuthApi.signUpNeeded(ticket = null) }
        assertEquals(AuthError.UNKNOWN, failureOf(repository.verifyCode(challenge, "123456")))
        api.verifyOutcome = { VerifyResponseDto(status = "SIGNED_IN") }
        assertEquals(AuthError.UNKNOWN, failureOf(repository.verifyCode(challenge, "123456")))
        api.verifyOutcome = { VerifyResponseDto(status = "SOMETHING_ELSE") }
        assertEquals(AuthError.UNKNOWN, failureOf(repository.verifyCode(challenge, "123456")))
        assertNull(storage.savedSession)
    }

    @Test
    fun `a wrong code keeps the server's words, other refusals map to their codes`() = runTest {
        api.verifyOutcome = { throw FakeAuthApi.httpError(400, "OTP_INVALID", "That code is not right. 2 tries left.") }
        val wrong = repository.verifyCode(challenge, "000000") as AuthResult.Failure
        assertEquals(AuthError.OTP_INVALID, wrong.error)
        assertEquals("That code is not right. 2 tries left.", wrong.message)

        for ((code, error) in listOf("OTP_EXPIRED" to AuthError.OTP_EXPIRED, "OTP_LOCKED" to AuthError.OTP_LOCKED,
                "CHALLENGE_INVALID" to AuthError.CHALLENGE_INVALID, "ACCOUNT_UNAVAILABLE" to AuthError.ACCOUNT_UNAVAILABLE)) {
            api.verifyOutcome = { throw FakeAuthApi.httpError(400, code, "server words") }
            val refused = repository.verifyCode(challenge, "000000") as AuthResult.Failure
            assertEquals(error, refused.error)
            assertNull("only case-dependent words are passed on", refused.message)
        }
        assertNull(storage.savedSession)
    }

    @Test
    fun `C1 - signing in again sends this phone's old refresh token only for the same number`() = runTest {
        val sameNumber = FakeSessionStorage(AuthSession("old", AuthUser(7, "Asha Devi", "••••••3210"),
            refreshToken = "old-refresh", needsSignIn = true))
        RemoteAuthRepository(api, sameNumber).verifyCode(challenge, "123456")
        assertEquals("old-refresh", api.lastVerifyRequest?.previousRefreshToken)

        val otherNumber = FakeSessionStorage(AuthSession("old", AuthUser(9, "Ravi", "••••••9999"),
            refreshToken = "other-refresh"))
        RemoteAuthRepository(api, otherNumber).verifyCode(challenge, "123456")
        assertNull(api.lastVerifyRequest?.previousRefreshToken)
    }

    @Test
    fun `signing in to another account first removes the previous account's data and ends its sign-in`() = runTest {
        val switched = mutableListOf<Long>()
        val previous = FakeSessionStorage(AuthSession("old", AuthUser(9, "Ravi", "••••••9999"), refreshToken = "r9"))
        RemoteAuthRepository(api, previous, onAccountSwitch = { switched += it }).verifyCode(challenge, "123456")

        assertEquals(listOf(9L), switched)
        assertEquals(listOf("r9"), api.signedOutTokens)
        assertEquals(7L, previous.savedSession?.user?.id)
    }

    // ------------------------------------------------------------------ sign-up

    @Test
    fun `sign-up sends the ticket, the name and the date, then saves the new session`() = runTest {
        val result = repository.signUp(FakeAuthRepository.TICKET, "Kavya Raman", LocalDate.of(2000, 1, 15))

        assertTrue(result is AuthResult.Success)
        val sent = api.lastSignUpRequest!!
        assertEquals("k".repeat(43), sent.ticket)
        assertEquals("Kavya Raman", sent.name)
        assertEquals("2000-01-15", sent.dateOfBirth)
        assertEquals("new-account-token", storage.savedSession?.token)
        assertEquals("new-refresh", storage.savedSession?.refreshToken)
    }

    @Test
    fun `sign-up refusals map to their errors and save nothing`() = runTest {
        api.signUpOutcome = { throw FakeAuthApi.httpError(400, "TICKET_INVALID") }
        assertEquals(AuthError.TICKET_INVALID,
            failureOf(repository.signUp(FakeAuthRepository.TICKET, "Kavya Raman", LocalDate.of(2000, 1, 15))))
        api.signUpOutcome = { throw FakeAuthApi.httpError(409, "PHONE_IN_USE") }
        assertEquals(AuthError.PHONE_IN_USE,
            failureOf(repository.signUp(FakeAuthRepository.TICKET, "Kavya Raman", LocalDate.of(2000, 1, 15))))
        api.signUpOutcome = { throw FakeAuthApi.httpError(400) }
        assertEquals(AuthError.VALIDATION,
            failureOf(repository.signUp(FakeAuthRepository.TICKET, "K4vya", LocalDate.of(2000, 1, 15))))
        api.signUpOutcome = { throw IllegalStateException("unreadable") }
        assertEquals(AuthError.UNKNOWN,
            failureOf(repository.signUp(FakeAuthRepository.TICKET, "Kavya Raman", LocalDate.of(2000, 1, 15))))
        assertNull(storage.savedSession)
    }

    // ------------------------------------------------------------------ profile refresh

    @Test
    fun `refreshProfile updates the stored user and keeps the token`() = runTest {
        val old = AuthSession("jwt-abc", AuthUser(7, "Old Name", "••••••3210"))
        val storage = FakeSessionStorage(old)
        api.meOutcome = { FakeAuthApi.userDto(name = "New Name") }

        val result = RemoteAuthRepository(api, storage).refreshProfile()

        assertTrue(result is AuthResult.Success)
        assertEquals("New Name", storage.savedSession?.user?.name)
        assertEquals("jwt-abc", storage.savedSession?.token)
    }

    @Test
    fun `C1 - refreshProfile with an expired token and no refresh secret keeps the session and asks for a sign-in`() = runTest {
        val storage = FakeSessionStorage(AuthSession("expired", AuthUser(7, "Asha", "••••••3210")))
        api.meOutcome = { throw FakeAuthApi.httpError(401) }

        val result = RemoteAuthRepository(api, storage).refreshProfile()

        assertEquals(AuthError.UNAUTHORIZED, failureOf(result))
        assertEquals("expired", storage.savedSession?.token)
        assertEquals(true, storage.savedSession?.needsSignIn)
    }

    @Test
    fun `refreshProfile while offline keeps the session`() = runTest {
        val storage = FakeSessionStorage(AuthSession("jwt", AuthUser(7, "Asha", "••••••3210")))
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
        val storage = FakeSessionStorage(AuthSession("jwt", AuthUser(7, "Asha", "••••••3210")))

        RemoteAuthRepository(api, storage).logout()

        assertNull(storage.savedSession)
        assertEquals(1, storage.clearCalls)
    }

    @Test
    fun `request and answer objects never print the ticket, the name, the date or tokens`() {
        val text = SignUpRequestDto("k".repeat(43), "Kavya Raman", "1998-05-14").toString() +
            FakeAuthApi.signUpNeeded().toString() + FakeAuthRepository.TICKET.toString() + FakeAuthRepository.CHALLENGE
        assertFalse(text.contains("kkkkk"))
        assertFalse(text.contains("Kavya"))
        assertFalse(text.contains("1998-05-14"))
        assertFalse(text.contains("tttt"))
    }
}
