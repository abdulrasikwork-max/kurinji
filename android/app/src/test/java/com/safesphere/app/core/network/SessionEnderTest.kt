package com.safesphere.app.core.network

import com.safesphere.app.feature.auth.domain.AuthSession
import com.safesphere.app.feature.auth.domain.AuthUser
import com.safesphere.app.testing.FakeSessionStorage
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/**
 * Phase 10A: a 401 ends the session only when the rejected token is still the saved one, and the server's reason
 * (WWW-Authenticate error_description) reaches the app. R8: during a password change the old token's 401 is ignored.
 */
class SessionEnderTest {

    private val user = AuthUser(7, "Asha", "asha@example.com")

    @Test
    fun `a 401 for the saved token clears it and reports the server's reason`() = runTest {
        val storage = FakeSessionStorage(AuthSession("current", user))
        val ender = SessionEnder(storage)
        val reported = async { ender.ended.first() }
        runCurrent()

        ender.onUnauthorized(response401("current", "Bearer error=\"invalid_token\", error_description=\"account_deleted\""))

        assertNull(storage.savedSession)
        assertEquals(SessionEnd(7, SessionEndReason.ACCOUNT_DELETED), reported.await())
        assertEquals(SessionEndReason.ACCOUNT_DELETED, ender.lastReason.value)
    }

    @Test
    fun `a late 401 for an OLD token never signs out the newer session`() = runTest {
        val storage = FakeSessionStorage(AuthSession("new-token", user))
        val ender = SessionEnder(storage)

        ender.onUnauthorized(response401("old-token", "Bearer error=\"invalid_token\", error_description=\"credentials_changed\""))

        assertEquals("new-token", storage.savedSession?.token)
        assertNull(ender.lastReason.value)
    }

    @Test
    fun `while a password change is on its way, the old token's 401 is ignored`() = runTest {
        val storage = FakeSessionStorage(AuthSession("old-token", user))
        val ender = SessionEnder(storage)

        ender.swapping("old-token") {
            ender.onUnauthorized(response401("old-token", "Bearer error=\"invalid_token\", error_description=\"credentials_changed\""))
            assertNotNull(storage.savedSession) // not signed out in the middle of the swap
            storage.save(AuthSession("new-token", user))
        }
        // After the swap the old token's 401 is stale anyway.
        ender.onUnauthorized(response401("old-token", null))
        assertEquals("new-token", storage.savedSession?.token)
    }

    @Test
    fun `C1 - a 401 without a reason is an expiry, not a sign-out - without a refresh secret it means sign in to sync`() = runTest {
        val storage = FakeSessionStorage(AuthSession("t", user))
        val outcome = SessionEnder(storage).onUnauthorized(Response.error<Any>(401, "{}".toResponseBody(null)))
        assertEquals(UnauthorizedOutcome.SIGN_IN_NEEDED, outcome)
        assertEquals("t", storage.savedSession?.token)
        assertEquals(true, storage.savedSession?.needsSignIn)
    }

    @Test
    fun `reasons are read from the header, and anything unknown means expired`() {
        assertEquals(SessionEndReason.CREDENTIALS_CHANGED,
            SessionEnder.reasonOf("Bearer error=\"invalid_token\", error_description=\"credentials_changed\""))
        assertEquals(SessionEndReason.ACCOUNT_UNAVAILABLE,
            SessionEnder.reasonOf("Bearer error=\"invalid_token\", error_description=\"account_unavailable\""))
        assertEquals(SessionEndReason.EXPIRED, SessionEnder.reasonOf("Bearer"))
        assertEquals(SessionEndReason.EXPIRED, SessionEnder.reasonOf(null))
        assertEquals(SessionEndReason.EXPIRED, SessionEnder.reasonOf("Bearer error_description=\"something_else\""))
    }

    @Test
    fun `ApiCallHandler uses the same rule`() = runTest {
        val storage = FakeSessionStorage(AuthSession("new-token", user))
        val handler = ApiCallHandler(SessionEnder(storage))

        val result = handler.execute<Unit> { throw HttpException(response401("old-token", null)) }

        assertEquals(ApiResult.Failure(ApiError.UNAUTHORIZED), result)
        assertEquals("new-token", storage.savedSession?.token)
    }

    private fun response401(token: String, wwwAuthenticate: String?): Response<Any> {
        val raw = okhttp3.Response.Builder()
            .request(Request.Builder().url("http://localhost/api/safety-contacts").header("Authorization", "Bearer $token").build())
            .protocol(Protocol.HTTP_1_1)
            .code(401)
            .message("Unauthorized")
            .apply { if (wwwAuthenticate != null) header("WWW-Authenticate", wwwAuthenticate) }
            .build()
        return Response.error("{}".toResponseBody(null), raw)
    }
}
