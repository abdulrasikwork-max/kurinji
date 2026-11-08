package com.safesphere.app.feature.auth

import com.safesphere.app.core.network.BackendOrigin
import com.safesphere.app.feature.auth.data.remote.AuthInterceptor
import com.safesphere.app.feature.auth.domain.AuthSession
import com.safesphere.app.feature.auth.domain.AuthUser
import com.safesphere.app.testing.FakeSessionStorage
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Connection
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 17J: the sign-in token is attached only to the backend's own origin (scheme + host + port). */
class AuthInterceptorOriginTest {

    private val backend = "https://api.example.invalid/".toHttpUrl()
    private val user = AuthUser(7, "A", "a@example.com")

    private class FakeChain(private val request: Request) : Interceptor.Chain {
        var proceeded: Request? = null
        override fun request() = request
        override fun proceed(request: Request): Response {
            proceeded = request
            return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").build()
        }
        override fun connection(): Connection? = null
        override fun call(): Call = throw UnsupportedOperationException()
        override fun connectTimeoutMillis() = 0
        override fun withConnectTimeout(timeout: Int, unit: TimeUnit) = this
        override fun readTimeoutMillis() = 0
        override fun withReadTimeout(timeout: Int, unit: TimeUnit) = this
        override fun writeTimeoutMillis() = 0
        override fun withWriteTimeout(timeout: Int, unit: TimeUnit) = this
    }

    private fun headerSentTo(url: String, token: String = "tok-1", signedIn: Boolean = true): String? {
        val storage = FakeSessionStorage(if (signedIn) AuthSession(token, user) else null)
        val chain = FakeChain(Request.Builder().url(url).build())
        AuthInterceptor(storage, backend).intercept(chain)
        return chain.proceeded!!.header("Authorization")
    }

    @Test fun `the backend origin gets the token`() {
        assertEquals("Bearer tok-1", headerSentTo("https://api.example.invalid/api/posts"))
    }

    @Test fun `another host never gets the token`() {
        assertNull(headerSentTo("https://evil.example.invalid/api/posts"))
        assertNull(headerSentTo("https://api.example.invalid.evil.example/api/posts"))
        assertNull(headerSentTo("https://sub.api.example.invalid/api/posts"))
    }

    @Test fun `the same host on another port or scheme never gets the token`() {
        assertNull(headerSentTo("https://api.example.invalid:8443/api/posts"))
        assertNull(headerSentTo("http://api.example.invalid/api/posts"))
    }

    @Test fun `host names are compared without regard to case`() {
        assertEquals("Bearer tok-1", headerSentTo("https://API.Example.INVALID/api/posts"))
    }

    @Test fun `public auth paths, no session and an unreadable token send nothing`() {
        assertNull(headerSentTo("https://api.example.invalid/api/auth/login"))
        assertNull(headerSentTo("https://api.example.invalid/api/posts", signedIn = false))
        assertNull(headerSentTo("https://api.example.invalid/api/posts", token = ""))
    }

    @Test fun `the text form of the check refuses what does not parse or belongs elsewhere`() {
        assertTrue(BackendOrigin.isSame(backend, "https://api.example.invalid/media/1"))
        assertFalse(BackendOrigin.isSame(backend, "not a url"))
        assertFalse(BackendOrigin.isSame(backend, ""))
        assertFalse(BackendOrigin.isSame(backend, "https://other.example.invalid/media/1"))
    }
}
