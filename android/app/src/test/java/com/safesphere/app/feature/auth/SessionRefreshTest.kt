package com.safesphere.app.feature.auth

import com.safesphere.app.core.network.ApiCallHandler
import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.core.network.RefreshOutcome
import com.safesphere.app.core.network.SessionEnd
import com.safesphere.app.core.network.SessionEndReason
import com.safesphere.app.core.network.SessionEnder
import com.safesphere.app.core.network.UnauthorizedOutcome
import com.safesphere.app.feature.auth.data.RemoteAuthRepository
import com.safesphere.app.feature.auth.data.SessionRefresher
import com.safesphere.app.feature.auth.data.remote.UserDto
import com.safesphere.app.feature.auth.domain.AuthSession
import com.safesphere.app.feature.auth.domain.AuthUser
import com.safesphere.app.feature.auth.domain.SessionStorage
import com.safesphere.app.testing.FakeAuthApi
import com.safesphere.app.testing.FakeSessionStorage
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/**
 * Phase 11 C1 "session continuity" on the phone: EXPIRED is not REVOKED. An expired access token is refreshed with the
 * stored secret (one refresh at a time, the request sent once more, never in a loop); a refused refresh keeps the
 * session as "sign in to sync" (nothing deleted); revocations still sign out; a late answer never overwrites a newer
 * session; signing in again keeps or clears local data depending on the account; signing out ends the server session.
 */
class SessionRefreshTest {

    private val user = AuthUser(7, "Asha", "asha@example.com")
    private val expired = AuthSession("expired-token", user, refreshToken = "refresh-1")
    private val api = FakeAuthApi().apply {
        refreshOutcome = { FakeAuthApi.authResponse(token = "fresh-token", refresh = "refresh-2", user = userDto7()) }
    }

    private fun userDto7() = UserDto(id = 7, name = "Asha", email = "asha@example.com", createdAt = null)

    private fun setUp(session: AuthSession? = expired): Triple<FakeSessionStorage, SessionEnder, ApiCallHandler> {
        val storage = FakeSessionStorage(session)
        val ender = SessionEnder(storage, SessionRefresher(storage, api))
        return Triple(storage, ender, ApiCallHandler(ender))
    }

    // ---- expired -> refresh -> retry ----------------------------------------------------------------------------

    @Test
    fun `an expired 401 triggers a refresh, both tokens are replaced together, and the call is sent once more`() = runTest {
        val (storage, _, handler) = setUp()
        val tokensSent = mutableListOf<String>()

        val result = handler.execute {
            val token = storage.session.first()!!.token
            tokensSent += token
            if (token == "expired-token") throw expiredFor(token)
            "saved"
        }

        assertEquals(ApiResult.Success("saved"), result)
        assertEquals(listOf("expired-token", "fresh-token"), tokensSent)
        assertEquals(listOf("refresh-1"), api.refreshTokensSeen)
        val saved = storage.savedSession!!
        assertEquals("fresh-token", saved.token)
        assertEquals("refresh-2", saved.refreshToken) // the rotated secret, saved with the new access token
        assertFalse(saved.needsSignIn)
    }

    @Test
    fun `many requests refused at once cause ONE refresh, and all of them succeed afterwards`() = runTest {
        val (storage, _, handler) = setUp()
        val gate = CompletableDeferred<Unit>()
        api.beforeRefreshAnswer = { gate.await() } // hold the refresh in flight while the others arrive

        val calls = (1..5).map {
            async {
                handler.execute {
                    val token = storage.session.first()!!.token
                    if (token == "expired-token") throw expiredFor(token)
                    token
                }
            }
        }
        runCurrent()
        gate.complete(Unit)
        val results = calls.awaitAll()

        assertEquals(1, api.refreshCalls)
        assertTrue(results.all { it == ApiResult.Success("fresh-token") })
    }

    @Test
    fun `a refresh loop is impossible - a request still refused after one refresh means sign in to sync`() = runTest {
        val (storage, _, handler) = setUp()
        var attempts = 0

        val result = handler.execute<Unit> {
            attempts++
            throw expiredFor(storage.session.first()!!.token)
        }

        assertEquals(ApiResult.Failure(ApiError.UNAUTHORIZED), result)
        assertEquals(2, attempts) // the original and ONE retry
        assertEquals(1, api.refreshCalls)
        assertTrue(storage.savedSession!!.needsSignIn)
        // Once marked, nothing asks the server for a refresh again.
        handler.execute<Unit> { throw expiredFor(storage.session.first()!!.token) }
        assertEquals(1, api.refreshCalls)
    }

    // ---- refresh fails ----------------------------------------------------------------------------------------

    @Test
    fun `offline during the refresh - the call counts as offline and nothing changes`() = runTest {
        val (storage, _, handler) = setUp()
        api.refreshOutcome = { throw IOException("offline") }

        val result = handler.execute<Unit> { throw expiredFor("expired-token") }

        assertEquals(ApiResult.Failure(ApiError.NO_CONNECTION), result)
        assertEquals(expired, storage.savedSession) // same token, same secret, not marked
    }

    @Test
    fun `a revoked refresh session keeps everything and asks for a sign-in`() = runTest {
        val (storage, ender, handler) = setUp()
        api.refreshOutcome = { throw FakeAuthApi.refused("session_revoked") }

        val result = handler.execute<Unit> { throw expiredFor("expired-token") }

        assertEquals(ApiResult.Failure(ApiError.UNAUTHORIZED), result)
        assertEquals(expired.copy(needsSignIn = true), storage.savedSession)
        assertEquals(0, storage.clearCalls)
        assertEquals(SessionEndReason.SESSION_REVOKED, ender.lastReason.value)
    }

    @Test
    fun `a refresh refused because the account was deleted signs out (Phase 10A wipe rule)`() = runTest {
        val (storage, ender, _) = setUp()
        api.refreshOutcome = { throw FakeAuthApi.refused("account_deleted") }
        val ended = mutableListOf<SessionEnd>()
        val watcher = launch { ender.ended.collect { ended += it } }
        runCurrent()

        assertEquals(UnauthorizedOutcome.SIGNED_OUT, ender.refresh("expired-token"))

        runCurrent()
        assertNull(storage.savedSession)
        assertEquals(listOf(SessionEnd(7, SessionEndReason.ACCOUNT_DELETED)), ended)
        watcher.cancel()
    }

    @Test
    fun `revocations still sign out - a changed password clears the session as before`() = runTest {
        val (storage, ender, _) = setUp(AuthSession("current", user, refreshToken = "r"))

        val outcome = ender.onUnauthorized(response401("current", "credentials_changed"))

        assertEquals(UnauthorizedOutcome.SIGNED_OUT, outcome)
        assertNull(storage.savedSession)
        assertEquals(0, api.refreshCalls)
    }

    @Test
    fun `this phone's session ended on the server (session_revoked on a request) means sign in to sync`() = runTest {
        val (storage, ender, _) = setUp(AuthSession("current", user, refreshToken = "r"))

        assertEquals(UnauthorizedOutcome.SIGN_IN_NEEDED, ender.onUnauthorized(response401("current", "session_revoked")))

        assertTrue(storage.savedSession!!.needsSignIn)
        assertEquals(0, api.refreshCalls)
    }

    // ---- stale answers never touch a newer session ----------------------------------------------------------------

    @Test
    fun `an old token's 401 never destroys or refreshes a newer session`() = runTest {
        val newer = AuthSession("newer-token", user, refreshToken = "refresh-9")
        val (storage, ender, _) = setUp(newer)

        assertEquals(UnauthorizedOutcome.IGNORED, ender.onUnauthorized(response401("old-token", "credentials_changed")))
        assertEquals(UnauthorizedOutcome.IGNORED, ender.onUnauthorized(response401("old-token", "session_revoked")))
        // An expired OLD token: the newer one is already saved, so the caller just retries with it (no refresh).
        assertEquals(UnauthorizedOutcome.RETRY, ender.onUnauthorized(response401("old-token", null)))

        assertEquals(newer, storage.savedSession)
        assertEquals(0, api.refreshCalls)
    }

    @Test
    fun `a late refresh answer cannot overwrite a session that changed while it was on its way`() = runTest {
        val storage = FakeSessionStorage(expired)
        val refresher = SessionRefresher(storage, api)
        val newSignIn = AuthSession("signed-in-again", user, refreshToken = "refresh-new")
        api.beforeRefreshAnswer = { storage.save(newSignIn) } // the person signed in again meanwhile

        assertEquals(RefreshOutcome.NotPossible, refresher.refreshAfter("expired-token"))
        assertEquals(newSignIn, storage.savedSession)
    }

    @Test
    fun `a refresh answer for another account is never saved`() = runTest {
        val storage = FakeSessionStorage(expired)
        api.refreshOutcome = { FakeAuthApi.authResponse(token = "x", refresh = "y", user = UserDto(8, "B", "b@example.com", null)) }

        assertEquals(RefreshOutcome.NotPossible, SessionRefresher(storage, api).refreshAfter("expired-token"))
        assertEquals(expired, storage.savedSession)
    }

    @Test
    fun `without a refresh secret (a sign-in from before C1) an expired token means sign in to sync`() = runTest {
        val (storage, _, handler) = setUp(AuthSession("expired-token", user, refreshToken = null))

        assertEquals(ApiResult.Failure(ApiError.UNAUTHORIZED), handler.execute<Unit> { throw expiredFor("expired-token") })
        assertEquals(0, api.refreshCalls)
        assertTrue(storage.savedSession!!.needsSignIn)
        assertEquals("expired-token", storage.savedSession!!.token)
    }

    // ---- signing in again / another account / signing out -------------------------------------------------------

    @Test
    fun `signing in again to the SAME account keeps local data and hands the old sign-in over`() = runTest {
        val storage = FakeSessionStorage(expired.copy(needsSignIn = true))
        val switches = mutableListOf<Long>()
        api.loginOutcome = { FakeAuthApi.authResponse(token = "new-access", refresh = "new-refresh", user = userDto7()) }
        val repository = RemoteAuthRepository(api, storage, SessionEnder(storage), onAccountSwitch = { switches += it })

        repository.login("Asha@Example.com ", "password")

        assertEquals("refresh-1", api.lastLoginRequest?.previousRefreshToken)
        assertTrue(switches.isEmpty()) // nothing of this person's is removed
        assertEquals(AuthSession("new-access", user, refreshToken = "new-refresh"), storage.savedSession)
        assertTrue(api.signedOutTokens.isEmpty()) // the server ends the old sign-in as part of the login
    }

    @Test
    fun `signing in to ANOTHER account first removes the previous account's data, and never sends its secret`() = runTest {
        val storage = FakeSessionStorage(expired.copy(needsSignIn = true))
        val other = UserDto(id = 8, name = "Bina", email = "bina@example.com", createdAt = null)
        api.loginOutcome = { FakeAuthApi.authResponse(token = "b-access", refresh = "b-refresh", user = other) }
        var sessionDuringCleanUp: AuthSession? = null
        val switches = mutableListOf<Long>()
        val repository = RemoteAuthRepository(api, storage, SessionEnder(storage), onAccountSwitch = {
            switches += it
            sessionDuringCleanUp = storage.savedSession
        })

        repository.login("bina@example.com", "password")

        assertNull(api.lastLoginRequest?.previousRefreshToken) // no link between the two accounts is sent
        assertEquals(listOf(7L), switches)
        assertEquals(7L, sessionDuringCleanUp?.user?.id) // the clean-up ran BEFORE the new account was saved
        assertEquals(listOf("refresh-1"), api.signedOutTokens) // the previous sign-in ends on the server
        assertEquals(8L, storage.savedSession?.user?.id)
    }

    @Test
    fun `signing out ends the sign-in on the server and clears this phone, also when offline`() = runTest {
        val storage = FakeSessionStorage(AuthSession("t", user, refreshToken = "refresh-1"))
        RemoteAuthRepository(api, storage).logout()
        assertEquals(listOf("refresh-1"), api.signedOutTokens)
        assertNull(storage.savedSession)

        val offline = FakeSessionStorage(AuthSession("t", user, refreshToken = "refresh-2"))
        api.signOutOutcome = { throw IOException("offline") }
        RemoteAuthRepository(api, offline).logout()
        assertNull(offline.savedSession)
    }

    @Test
    fun `refreshProfile at app start refreshes an expired token and asks once more`() = runTest {
        val (storage, ender, _) = setUp()
        var meCalls = 0
        api.meOutcome = {
            meCalls++
            if (meCalls == 1) throw expiredFor("expired-token")
            userDto7()
        }

        val result = RemoteAuthRepository(api, storage, ender).refreshProfile()

        assertTrue(result is com.safesphere.app.feature.auth.domain.AuthResult.Success)
        assertEquals(2, meCalls)
        assertEquals("fresh-token", storage.savedSession?.token)
    }

    /**
     * A storage in which a refresh commits right after any plain read of [session] (the read still returns the earlier
     * value): the window DataStore allows between two separate edits. [update] is atomic, as in DataStoreSessionStorage.
     */
    private class RacingStorage(initial: AuthSession) : SessionStorage {
        val current = MutableStateFlow<AuthSession?>(initial)
        var armed = false
        var rotated = false
        override val session: Flow<AuthSession?> = current.map { seen ->
            if (armed && seen != null) {
                armed = false
                rotated = true
                current.value = seen.copy(token = "fresh-token", refreshToken = "refresh-2") // the refresh commits
            }
            seen
        }
        override suspend fun save(session: AuthSession) { current.value = session }
        override suspend fun clear() { current.value = null }
        override suspend fun update(transform: (AuthSession?) -> AuthSession?): AuthSession? =
            transform(current.value).also { current.value = it }
    }

    @Test
    fun `a profile update never writes an already-replaced refresh secret back (emulator find)`() = runTest {
        val storage = RacingStorage(AuthSession("old-access", user, refreshToken = "refresh-1"))
        api.meOutcome = {
            storage.armed = true // from now on, the next plain read races with a committed refresh
            userDto7()
        }

        RemoteAuthRepository(api, storage).refreshProfile()

        // Invariant: a rotation that committed is never lost (the server only accepts the newest secret). Before the
        // fix, profile() read the session, the refresh committed, and the stale copy was saved over it.
        assertTrue(!storage.rotated || storage.current.value?.refreshToken == "refresh-2")
        assertEquals("Asha", storage.current.value?.user?.name) // and the profile answer was still applied
    }

    @Test
    fun `only revocations get the forced sign-out dialog, everything else is a closable sign in to sync`() {
        assertTrue(com.safesphere.app.core.ui.signsOut(SessionEndReason.CREDENTIALS_CHANGED))
        assertTrue(com.safesphere.app.core.ui.signsOut(SessionEndReason.ACCOUNT_DELETED))
        assertTrue(com.safesphere.app.core.ui.signsOut(SessionEndReason.ACCOUNT_UNAVAILABLE))
        assertFalse(com.safesphere.app.core.ui.signsOut(SessionEndReason.EXPIRED))
        assertFalse(com.safesphere.app.core.ui.signsOut(SessionEndReason.SESSION_REVOKED))
        assertFalse(com.safesphere.app.core.ui.signsOut(null))
        // Found on the emulator: a phone with no session left always gets the forced dialog, whatever the last reason.
        assertTrue(com.safesphere.app.core.ui.forcedSignOutDialog(SessionEndReason.EXPIRED, sessionOnPhone = false))
        assertTrue(com.safesphere.app.core.ui.forcedSignOutDialog(null, sessionOnPhone = false))
        assertFalse(com.safesphere.app.core.ui.forcedSignOutDialog(SessionEndReason.EXPIRED, sessionOnPhone = true))
        assertFalse(com.safesphere.app.core.ui.forcedSignOutDialog(SessionEndReason.SESSION_REVOKED, sessionOnPhone = true))
        assertTrue(com.safesphere.app.core.ui.forcedSignOutDialog(SessionEndReason.CREDENTIALS_CHANGED, sessionOnPhone = true))
        assertEquals(com.safesphere.app.R.string.session_sign_in_to_sync_revoked,
            com.safesphere.app.core.ui.signInToSyncTexts(SessionEndReason.SESSION_REVOKED).second)
        assertEquals(com.safesphere.app.R.string.session_sign_in_to_sync,
            com.safesphere.app.core.ui.signInToSyncTexts(SessionEndReason.EXPIRED).second)
    }

    @Test
    fun `secrets never appear in toString`() {
        val text = AuthSession("access-secret", user, refreshToken = "refresh-secret").toString()
        assertFalse(text.contains("access-secret"))
        assertFalse(text.contains("refresh-secret"))
        assertFalse(FakeAuthApi.authResponse("a1", "r1").toString().contains("r1"))
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    /** A plain 401 (no reason) for a request that carried [token]: what the server sends for an expired token. */
    private fun expiredFor(token: String) = HttpException(response401(token, null))

    private fun response401(token: String, reason: String?): Response<Any> {
        val raw = okhttp3.Response.Builder()
            .request(Request.Builder().url("http://localhost/api/emergencies").header("Authorization", "Bearer $token").build())
            .protocol(Protocol.HTTP_1_1)
            .code(401)
            .message("Unauthorized")
            .header("WWW-Authenticate", if (reason == null) "Bearer" else "Bearer error=\"invalid_token\", error_description=\"$reason\"")
            .build()
        return Response.error("{}".toResponseBody(null), raw)
    }
}
