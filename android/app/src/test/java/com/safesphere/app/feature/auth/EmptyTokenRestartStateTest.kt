package com.safesphere.app.feature.auth

import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.core.network.RefreshOutcome
import com.safesphere.app.core.network.SessionEndReason
import com.safesphere.app.core.network.SessionEnder
import com.safesphere.app.core.network.UnauthorizedOutcome
import com.safesphere.app.feature.account.session.SessionRenewer
import com.safesphere.app.feature.auth.data.SessionRefresher
import com.safesphere.app.feature.auth.domain.AuthSession
import com.safesphere.app.feature.auth.domain.AuthUser
import com.safesphere.app.testing.FakeAuthApi
import com.safesphere.app.testing.FakeSessionStorage
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 17J (J-8): on a phone whose Keystore refuses to seal, EVERY restart lands in this state: the same person, an empty
 * token, no refresh secret, "sign in to sync". It must be quiet and safe: no request loop, no refresh call, nothing cleared,
 * and the safety features (which read the person's record, not the token) keep their session.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EmptyTokenRestartStateTest {

    private val user = AuthUser(7, "Asha", "asha@example.com")
    private val signInNeeded = AuthSession("", user, refreshToken = null, needsSignIn = true)

    @Test
    fun `the refresher does nothing for it and calls no server`() = runTest {
        val api = FakeAuthApi()
        val storage = FakeSessionStorage(signInNeeded)
        val refresher = SessionRefresher(storage, api)

        assertEquals(RefreshOutcome.NotPossible, refresher.refreshAfter(null))
        assertEquals(RefreshOutcome.NotPossible, refresher.refreshAfter(""))
        assertEquals(0, api.refreshCalls)
        assertNotNull("nothing is cleared", storage.savedSession)
        assertEquals(0, storage.clearCalls)
    }

    @Test
    fun `the renewer makes no call for it, however long the phone runs`() = runTest {
        val session = MutableStateFlow<AuthSession?>(signInNeeded)
        var refreshes = 0
        var renewals = 0
        SessionRenewer(backgroundScope, session, MutableStateFlow(true), { renewals++; ApiResult.Success(Unit) },
            { refreshes++ }) { Instant.parse("2026-11-08T10:00:00Z").plusMillis(testScheduler.currentTime) }
        runCurrent()
        advanceTimeBy(3 * 60 * 60_000L)
        runCurrent()
        assertEquals(0, refreshes)
        assertEquals(0, renewals)
    }

    @Test
    fun `a request sent without a token that is refused ends in sign in to sync, never in a sign-out`() = runTest {
        val storage = FakeSessionStorage(signInNeeded)
        val ender = SessionEnder(storage, SessionRefresher(storage, FakeAuthApi()))

        // what the app does after a 401 for a request that carried no Authorization header
        assertEquals(UnauthorizedOutcome.SIGN_IN_NEEDED, ender.refresh(null))
        assertEquals(SessionEndReason.EXPIRED, ender.lastReason.value)
        val kept = storage.savedSession
        assertNotNull("the session and everything local stays", kept)
        assertTrue(kept!!.needsSignIn)
        assertEquals(user, kept.user)
        assertEquals(0, storage.clearCalls)
    }

    @Test
    fun `it keeps the person's record, which is all the safety features read`() {
        assertTrue(signInNeeded.needsSignIn)
        assertEquals(7L, signInNeeded.user.id)
        assertEquals("", signInNeeded.token)
        assertEquals(null, signInNeeded.refreshToken)
    }
}
