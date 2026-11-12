package com.safesphere.app.core.network

import com.safesphere.app.testing.FakeSessionStorage
import com.safesphere.app.feature.auth.domain.AuthSession
import com.safesphere.app.feature.auth.domain.AuthUser
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Found on a real phone over the pilot's slow path (laptop on the phone's hotspot): a 3 second video took 34 to 37
 * seconds to be taken in and the shared 15 second read timeout closed the request while its body was still arriving.
 * Uploads (photo, video, poster, profile photo) now get 120 s read and write timeouts, marked on their calls; every
 * other request keeps 15 s; a timeout is told apart from "no connection" so the composer can say "too slow, try again".
 */
class UploadTimeoutTest {

    /** A chain that remembers what it was asked to do (Interceptor.Chain is a public interface; no network needed). */
    private class FakeChain(
        private val request: Request,
        private val connectMs: Int = 15_000,
        private val readMs: Int = 15_000,
        private val writeMs: Int = 15_000,
        private val log: Log = Log()
    ) : Interceptor.Chain {
        class Log {
            var sent: Request? = null
            var readMs = -1
            var writeMs = -1
            var connectMs = -1
        }

        override fun request() = request
        override fun proceed(request: Request): Response {
            log.sent = request
            log.readMs = readMs
            log.writeMs = writeMs
            log.connectMs = connectMs
            return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").build()
        }

        override fun connection(): Connection? = null
        override fun call(): Call = throw UnsupportedOperationException()
        override fun connectTimeoutMillis() = connectMs
        override fun readTimeoutMillis() = readMs
        override fun writeTimeoutMillis() = writeMs
        override fun withConnectTimeout(timeout: Int, unit: TimeUnit) =
            FakeChain(request, unit.toMillis(timeout.toLong()).toInt(), readMs, writeMs, log)
        override fun withReadTimeout(timeout: Int, unit: TimeUnit) =
            FakeChain(request, connectMs, unit.toMillis(timeout.toLong()).toInt(), writeMs, log)
        override fun withWriteTimeout(timeout: Int, unit: TimeUnit) =
            FakeChain(request, connectMs, readMs, unit.toMillis(timeout.toLong()).toInt(), log)
    }

    private fun request(marked: Boolean) = Request.Builder().url("https://api.example.test/api/media")
        .apply { if (marked) header(UploadTimeoutInterceptor.HEADER, "long") }.build()

    @Test
    fun `a marked upload gets 120 s to read and write, the connect timeout is unchanged, and the marker is never sent`() {
        val log = FakeChain.Log()
        UploadTimeoutInterceptor().intercept(FakeChain(request(marked = true), log = log))

        assertEquals(120_000, log.readMs)
        assertEquals(120_000, log.writeMs)
        assertEquals(15_000, log.connectMs)
        assertNull("the marker must not leave the phone", log.sent!!.header(UploadTimeoutInterceptor.HEADER))
        assertEquals("https://api.example.test/api/media", log.sent!!.url.toString())
    }

    @Test
    fun `every other request keeps the shared timeouts and is passed on untouched`() {
        val log = FakeChain.Log()
        val plain = request(marked = false)
        UploadTimeoutInterceptor().intercept(FakeChain(plain, log = log))

        assertEquals(15_000, log.readMs)
        assertEquals(15_000, log.writeMs)
        assertEquals(15_000, log.connectMs)
        assertSame(plain, log.sent)
    }

    @Test
    fun `the upload limit is 120 seconds and the marker line matches the header name`() {
        assertEquals(120L, UploadTimeoutInterceptor.UPLOAD_TIMEOUT_SECONDS)
        assertEquals("${UploadTimeoutInterceptor.HEADER}: long", UploadTimeoutInterceptor.HEADER_LINE)
    }

    // ---- a timeout is not "no connection" ----

    private val handler = ApiCallHandler(FakeSessionStorage(AuthSession("jwt", AuthUser(1, "Asha", "asha@example.com"))))

    @Test
    fun `a read or write that ran out of time is reported as timed out`() = runTest {
        assertEquals(ApiResult.Failure(ApiError.NO_CONNECTION, timedOut = true),
            handler.execute<Unit> { throw SocketTimeoutException("timeout") })
        assertEquals(ApiResult.Failure(ApiError.NO_CONNECTION, timedOut = true),
            handler.execute<Unit> { throw InterruptedIOException("timeout") })
    }

    @Test
    fun `being offline, or any other I-O failure, is not a timeout`() = runTest {
        val offline = handler.execute<Unit> { throw IOException("offline") } as ApiResult.Failure
        assertEquals(ApiError.NO_CONNECTION, offline.error)
        assertFalse(offline.timedOut)
        val interrupted = handler.execute<Unit> { throw InterruptedIOException("thread interrupted") } as ApiResult.Failure
        assertFalse(interrupted.timedOut)
        assertEquals(ApiResult.Failure(ApiError.NO_CONNECTION), handler.execute<Unit> { throw IOException("offline") })
    }

    // ---- wiring (source guards: the Android build cannot show which calls carry the marker) ----

    private fun main(path: String) = File("src/main/java/com/safesphere/app/$path").readText()

    @Test
    fun `only the three upload calls are marked, and they are the photo, poster and profile photo uploads`() {
        val experience = main("feature/social/data/remote/ExperienceApi.kt")
        val social = main("feature/social/data/remote/SocialApi.kt")
        val marker = Regex.escape("@Headers(UploadTimeoutInterceptor.HEADER_LINE)")
        assertTrue(Regex("""$marker[^\n]*\n\s*@POST\("api/media"\)""").containsMatchIn(experience))
        assertTrue(Regex("""$marker[^\n]*\n\s*@POST\("api/media/\{id\}/poster"\)""").containsMatchIn(experience))
        assertTrue(Regex("""$marker[^\n]*\n\s*@PUT\("api/profiles/me/photo"\)""").containsMatchIn(social))
        val everywhere = File("src/main/java").walkTopDown().filter { it.extension == "kt" }
            .sumOf { Regex(Regex.escape("@Headers(UploadTimeoutInterceptor.HEADER_LINE)")).findAll(it.readText()).count() }
        assertEquals("no other call may carry the long timeout", 3, everywhere)
    }

    @Test
    fun `the shared client keeps 15 seconds and installs the upload interceptor`() {
        val container = main("AppContainer.kt")
        assertTrue(container.contains("const val NETWORK_TIMEOUT_SECONDS = 15L"))
        for (kind in listOf("connectTimeout", "readTimeout", "writeTimeout")) {
            assertTrue("$kind uses the shared 15 s", container.contains(".$kind(NETWORK_TIMEOUT_SECONDS, TimeUnit.SECONDS)"))
        }
        assertTrue(container.contains(".addInterceptor(UploadTimeoutInterceptor())"))
        assertEquals("one Retrofit instance", 1, Regex("""Retrofit\.Builder\(\)""").findAll(container).count())
    }
}
