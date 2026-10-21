package com.safesphere.app.core.network

import com.safesphere.app.feature.auth.domain.AuthSession
import com.safesphere.app.feature.auth.domain.AuthUser
import com.safesphere.app.testing.FakeSessionStorage
import java.io.IOException
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class ApiCallHandlerTest {

    private val signedIn = AuthSession("jwt", AuthUser(1, "Asha", "asha@example.com"))
    private val storage = FakeSessionStorage(signedIn)
    private val handler = ApiCallHandler(storage)

    private fun httpError(code: Int, body: String = "{}") =
        HttpException(Response.error<Any>(code, body.toResponseBody(null)))

    @Test
    fun `success is returned unchanged`() = runTest {
        val result = handler.execute { 42 }

        assertEquals(ApiResult.Success(42), result)
    }

    @Test
    fun `a 401 clears the saved session and reports UNAUTHORIZED`() = runTest {
        val result = handler.execute<Unit> { throw httpError(401) }

        assertEquals(ApiResult.Failure(ApiError.UNAUTHORIZED), result)
        assertNull(storage.savedSession)
    }

    @Test
    fun `a 409 keeps the session and passes on the server's own message`() = runTest {
        val body = """{"status":409,"message":"This phone number is already in your Safety Circle"}"""

        val result = handler.execute<Unit> { throw httpError(409, body) }

        assertEquals(
            ApiResult.Failure(ApiError.CONFLICT, "This phone number is already in your Safety Circle"),
            result
        )
        assertNotNull(storage.savedSession)
    }

    @Test
    fun `server messages are NOT passed on for other errors`() = runTest {
        val result = handler.execute<Unit> { throw httpError(500, """{"message":"internal details"}""") }

        assertEquals(ApiResult.Failure(ApiError.SERVER), result)
    }

    @Test
    fun `an unreadable conflict body still gives a conflict without a message`() = runTest {
        val result = handler.execute<Unit> { throw httpError(409, "<html>not json</html>") }

        assertEquals(ApiResult.Failure(ApiError.CONFLICT, null), result)
    }

    @Test
    fun `no network maps to NO_CONNECTION`() = runTest {
        assertEquals(ApiResult.Failure(ApiError.NO_CONNECTION), handler.execute<Unit> { throw IOException("offline") })
    }

    @Test
    fun `any other failure maps to UNKNOWN instead of crashing`() = runTest {
        assertEquals(ApiResult.Failure(ApiError.UNKNOWN), handler.execute<Unit> { throw IllegalStateException("bad json") })
    }
}
