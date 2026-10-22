package com.safesphere.app.feature.emergency

import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.auth.domain.AuthSession
import com.safesphere.app.feature.emergency.data.UnsentEndingsCodec
import com.safesphere.app.feature.emergency.domain.ActiveEmergencyRecord
import com.safesphere.app.feature.emergency.domain.EmergencyIds
import com.safesphere.app.feature.emergency.domain.EmergencyStatus
import com.safesphere.app.feature.emergency.domain.LocalEnd
import com.safesphere.app.feature.emergency.session.SosRecorder
import com.safesphere.app.testing.FakeActiveEmergencyStore
import com.safesphere.app.testing.FakeEmergencyRepository
import com.safesphere.app.testing.FakeEmergencyRepository.Companion.event
import com.safesphere.app.testing.FakeLocationSharingController
import com.safesphere.app.testing.MainDispatcherRule
import com.safesphere.app.testing.sessionFor
import com.safesphere.app.testing.settle
import com.safesphere.app.testing.sosRecorderForTest
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Phase 11: the holder that starts every SOS on the phone and records it on the server when it can. */
@OptIn(ExperimentalCoroutinesApi::class)
class SosRecorderTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val server = FakeEmergencyRepository()
    private val store = FakeActiveEmergencyStore()
    private val sharing = FakeLocationSharingController()

    @Test
    fun `local ids are negative, stable for a reference, and never collide with server ids`() {
        val ref = "6f1c2c3e-1d2b-4c5a-9e8f-0a1b2c3d4e5f"
        assertTrue(EmergencyIds.localIdFor(ref) < 0)
        assertEquals(EmergencyIds.localIdFor(ref), EmergencyIds.localIdFor(ref))
        assertFalse(EmergencyIds.isLocal(1))
        assertTrue(EmergencyIds.isLocal(EmergencyIds.localIdFor("00000000-0000-4000-8000-000000000000")))
    }

    @Test
    fun `the first attempt sends no phone time, retries send it, and every attempt uses the same reference`() = runTest {
        server.startResult = ApiResult.Failure(ApiError.NO_CONNECTION)
        val sos = sosRecorderForTest(server, store)
        runCurrent()
        val record = sos.startLocal(7)
        runCurrent()
        assertNull(server.recordRequests.single().startedAt)
        assertEquals(record.clientRef, server.recordRequests.single().clientRef)

        advanceTimeBy(5_001)
        runCurrent()
        advanceTimeBy(10_001)
        runCurrent()
        assertEquals(3, server.recordRequests.size)
        assertTrue(server.recordRequests.drop(1).all { it.startedAt == record.startedAt })
        assertEquals(setOf(record.clientRef), server.recordRequests.map { it.clientRef }.toSet())
    }

    @Test
    fun `a returning connection makes it try at once instead of waiting for the backoff`() = runTest {
        server.startResult = ApiResult.Failure(ApiError.NO_CONNECTION)
        val online = MutableStateFlow(false)
        val sos = sosRecorderForTest(server, store, online = online)
        runCurrent()
        sos.startLocal(7)
        runCurrent()
        assertEquals(1, server.startCalls)

        server.startResult = ApiResult.Success(event(id = 12))
        online.value = true
        runCurrent()
        assertEquals(2, server.startCalls)
        assertEquals(12L, sos.state.value.current?.emergencyId)
        assertEquals(true, sos.state.value.current?.onServer)
    }

    @Test
    fun `once recorded, the holders move to the server's id and nothing more is sent`() = runTest {
        server.startResult = ApiResult.Success(event(id = 21))
        val sos = sosRecorderForTest(server, store, rebinders = listOf(sharing))
        runCurrent()
        val record = sos.startLocal(7)
        settle()
        assertEquals(listOf(record.emergencyId to 21L), sharing.rebinds)
        assertEquals(1, server.startCalls)
        advanceTimeBy(600_000)
        runCurrent()
        assertEquals(1, server.startCalls)
        assertEquals(21L, store.record?.emergencyId)
    }

    @Test
    fun `an SOS that started offline is recorded after a restart, from the record on the phone`() = runTest {
        val ref = "00000000-0000-4000-8000-000000000042"
        store.record = ActiveEmergencyRecord(7, EmergencyIds.localIdFor(ref), Instant.parse("2026-10-15T09:00:00Z"),
            clientRef = ref, onServer = false)
        server.startResult = ApiResult.Success(event(id = 42))
        val sos = sosRecorderForTest(server, store)
        settle()
        assertEquals(ref, server.recordRequests.single().clientRef)
        assertEquals(Instant.parse("2026-10-15T09:00:00Z"), server.recordRequests.single().startedAt)
        assertEquals(42L, sos.state.value.current?.emergencyId)
    }

    @Test
    fun `endings kept on the phone are told to the server after a restart, oldest first`() = runTest {
        val first = ActiveEmergencyRecord(7, 30, Instant.parse("2026-10-15T09:00:00Z"),
            ended = LocalEnd(resolved = false, at = Instant.parse("2026-10-15T09:10:00Z")))
        val ref = "00000000-0000-4000-8000-000000000043"
        val second = ActiveEmergencyRecord(7, EmergencyIds.localIdFor(ref), Instant.parse("2026-10-15T11:00:00Z"),
            clientRef = ref, onServer = false, ended = LocalEnd(true, Instant.parse("2026-10-15T11:20:00Z")))
        store.unsent = listOf(first, second)
        server.startResult = ApiResult.Success(event(id = 31, status = EmergencyStatus.RESOLVED))
        val sos = sosRecorderForTest(server, store)
        settle()

        assertEquals(1, server.cancelCalls)
        assertEquals(Instant.parse("2026-10-15T09:10:00Z"), server.lastEndedAt)
        val request = server.recordRequests.single()
        assertEquals(ref, request.clientRef)
        assertEquals(LocalEnd(true, Instant.parse("2026-10-15T11:20:00Z")), request.ended)
        assertTrue(store.unsent.isEmpty())
        assertTrue(sos.state.value.unsent.isEmpty())
    }

    @Test
    fun `if the first attempt got through but its answer was lost, the ending goes to the stored emergency`() = runTest {
        server.startResult = ApiResult.Failure(ApiError.NO_CONNECTION)
        val sos = sosRecorderForTest(server, store)
        runCurrent()
        sos.startLocal(7)
        runCurrent()
        sos.endLocally(resolved = true)
        // The server answers the reference with the emergency it stored the first time: still ACTIVE.
        server.startResult = ApiResult.Success(event(id = 50, status = EmergencyStatus.ACTIVE))
        advanceTimeBy(10_001)
        runCurrent()
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, server.resolveCalls)
        assertNotNull(server.lastEndedAt)
        assertTrue(sos.state.value.unsent.isEmpty())
    }

    @Test
    fun `a second running SOS on the server (another phone) is joined, not duplicated`() = runTest {
        server.startResult = ApiResult.Failure(ApiError.CONFLICT, "An emergency is already active")
        server.historyResult = ApiResult.Success(listOf(event(id = 88)))
        val sos = sosRecorderForTest(server, store, rebinders = listOf(sharing))
        runCurrent()
        val record = sos.startLocal(7)
        settle()
        assertEquals(88L, sos.state.value.current?.emergencyId)
        assertEquals(listOf(record.emergencyId to 88L), sharing.rebinds)
    }

    @Test
    fun `a refusal (not a connection problem) stops the attempts and is shown, the SOS keeps running here`() = runTest {
        server.startResult = ApiResult.Failure(ApiError.VALIDATION)
        val sos = sosRecorderForTest(server, store)
        runCurrent()
        sos.startLocal(7)
        settle()
        advanceTimeBy(600_000)
        runCurrent()
        assertEquals(1, server.startCalls)
        assertTrue(sos.state.value.refused)
        assertNotNull(sos.state.value.current)
    }

    @Test
    fun `an expired session waits (no retry loop), and a sign-out forgets everything`() = runTest {
        server.startResult = ApiResult.Failure(ApiError.UNAUTHORIZED)
        val session = MutableStateFlow<AuthSession?>(sessionFor(7))
        val sos = sosRecorderForTest(server, store, session = session)
        runCurrent()
        sos.startLocal(7)
        settle()
        advanceTimeBy(600_000)
        runCurrent()
        assertEquals(1, server.startCalls)

        session.value = null
        runCurrent()
        assertNull(sos.state.value.current)
        assertTrue(sos.state.value.unsent.isEmpty())
    }

    @Test
    fun `C1 - an expired session keeps the running SOS and its ending, and a new token records it exactly once`() = runTest {
        server.startResult = ApiResult.Failure(ApiError.UNAUTHORIZED)
        val session = MutableStateFlow<AuthSession?>(sessionFor(7).copy(needsSignIn = true))
        val sos = sosRecorderForTest(server, store, session = session)
        runCurrent()
        val started = sos.startLocal(7)
        settle()
        sos.endLocally(resolved = true) // "I'm safe" while the server needs a sign-in
        settle()
        advanceTimeBy(600_000)
        runCurrent()
        // Nothing was thrown away: the ending waits on the phone (and in the store, for a restart).
        assertEquals(started.clientRef, sos.state.value.unsent.single().clientRef)
        assertEquals(started.clientRef, store.unsent.single().clientRef)

        // A refresh (or signing in again) gives a usable token: recorded at once, as ended, with the same reference.
        server.startResult = ApiResult.Success(event(id = 40, status = EmergencyStatus.RESOLVED))
        session.value = sessionFor(7).copy(token = "refreshed")
        settle()
        assertTrue(sos.state.value.unsent.isEmpty())
        assertEquals(setOf(started.clientRef), server.recordRequests.map { it.clientRef }.toSet())
        assertNotNull(server.recordRequests.last().ended) // recorded as already ended: no second, running SOS
        val calls = server.startCalls
        advanceTimeBy(600_000)
        runCurrent()
        assertEquals(calls, server.startCalls) // and never sent again
    }

    @Test
    fun `C1 - another account signing in on this phone never sees the previous account's SOS`() = runTest {
        server.startResult = ApiResult.Failure(ApiError.NO_CONNECTION)
        val session = MutableStateFlow<AuthSession?>(sessionFor(7))
        val sos = sosRecorderForTest(server, store, session = session)
        runCurrent()
        sos.startLocal(7)
        settle()
        session.value = sessionFor(8) // account switch (its clean-up has already emptied the store)
        settle()
        assertNull(sos.state.value.current)
        assertNull(sos.current(8))
        assertNull(sos.current(7))
    }

    @Test
    fun `another person's records on the same phone are never sent with this person's session`() = runTest {
        store.unsent = listOf(ActiveEmergencyRecord(999, 30, Instant.parse("2026-10-15T09:00:00Z"),
            ended = LocalEnd(true, Instant.parse("2026-10-15T09:10:00Z"))))
        sosRecorderForTest(server, store)
        settle()
        advanceTimeBy(600_000)
        runCurrent()
        assertEquals(0, server.resolveCalls)
        assertEquals(0, server.startCalls)
        assertEquals(1, store.unsent.size)
    }

    @Test
    fun `at most five endings are kept, the oldest is dropped first`() = runTest {
        server.startResult = ApiResult.Failure(ApiError.NO_CONNECTION)
        val sos = sosRecorderForTest(server, store)
        runCurrent()
        repeat(SosRecorder.MAX_UNSENT + 2) {
            sos.startLocal(7)
            sos.endLocally(resolved = true)
        }
        assertEquals(SosRecorder.MAX_UNSENT, store.unsent.size)
    }

    @Test
    fun `endings survive the phone's storage format exactly, and nothing else is stored`() {
        val ref = "00000000-0000-4000-8000-000000000044"
        val records = listOf(
            ActiveEmergencyRecord(7, EmergencyIds.localIdFor(ref), Instant.ofEpochMilli(1_800_000_000_123),
                clientRef = ref, onServer = false, ended = LocalEnd(false, Instant.ofEpochMilli(1_800_000_900_000)),
                dialPromptOpened = true),
            ActiveEmergencyRecord(7, 30, Instant.ofEpochMilli(1_800_000_000_000), ended = LocalEnd(true, Instant.ofEpochMilli(1_800_000_100_000)))
        )
        records.forEach { assertEquals(it, UnsentEndingsCodec.decode(UnsentEndingsCodec.encode(it))) }
        // Only numbers, a UUID and flags: no text, no number, no location can be in a line.
        assertTrue(records.all { UnsentEndingsCodec.encode(it).matches(Regex("^[0-9a-f;\\-truefals]*$")) })
        assertNull(UnsentEndingsCodec.decode("garbage"))
        assertNull(UnsentEndingsCodec.decode("7;30;1;;true;;;false")) // a running record is never kept here
    }

    @Test
    fun `Call 112 before the server has the SOS is recorded once, after it has it`() = runTest {
        server.startResult = ApiResult.Failure(ApiError.NO_CONNECTION)
        val dial = mutableListOf<Long>()
        val sos = sosRecorderForTest(server, store, dialPrompts = dial)
        runCurrent()
        sos.startLocal(7)
        runCurrent()
        sos.onDialPromptOpened()
        sos.onDialPromptOpened()
        runCurrent()
        assertTrue(dial.isEmpty())
        assertEquals(true, store.record?.dialPromptOpened)

        server.startResult = ApiResult.Success(event(id = 64))
        advanceTimeBy(5_001)
        runCurrent()
        assertEquals(listOf(64L), dial)
        sos.onDialPromptOpened() // now on the server: straight through
        assertEquals(listOf(64L, 64L), dial)
    }
}
