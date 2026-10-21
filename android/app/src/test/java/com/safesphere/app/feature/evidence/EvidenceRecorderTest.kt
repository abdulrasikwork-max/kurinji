package com.safesphere.app.feature.evidence

import com.safesphere.app.R
import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.auth.domain.AuthSession
import com.safesphere.app.feature.auth.domain.AuthUser
import com.safesphere.app.feature.evidence.capture.EvidenceRecorder
import com.safesphere.app.feature.evidence.data.EvidenceFiles
import com.safesphere.app.feature.evidence.domain.EvidenceKind
import com.safesphere.app.feature.evidence.domain.RecordingPolicy
import com.safesphere.app.feature.evidence.domain.RecordingStopReason
import com.safesphere.app.feature.evidence.session.EvidenceVault
import com.safesphere.app.testing.FakeAudioWriter
import com.safesphere.app.testing.FakeConnectivityMonitor
import com.safesphere.app.testing.FakeEvidenceRepository
import com.safesphere.app.testing.FakeKeyWrapper
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
import org.junit.rules.TemporaryFolder

/**
 * Phase 5: audio evidence is recorded only on request during an ACTIVE emergency, in 30-second parts that are each
 * sealed as they close, always with a notice, and it stops by itself for the documented reasons. Virtual time only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EvidenceRecorderTest {

    @get:Rule val folder = TemporaryFolder()

    private val writer = FakeAudioWriter()
    private val activeEmergency = MutableStateFlow<Long?>(EMERGENCY)
    private val session = MutableStateFlow<AuthSession?>(AuthSession("t", AuthUser(USER, "Asha", "a@example.com")))
    private var freeBytes = Long.MAX_VALUE
    private val repository = FakeEvidenceRepository().apply {
        failNext = ApiResult.Failure(ApiError.NO_CONNECTION) // keep everything on the phone in these tests
        failCount = Int.MAX_VALUE
    }
    private lateinit var vault: EvidenceVault

    private fun TestScope.recorder(): EvidenceRecorder {
        val dispatcher = StandardTestDispatcher(testScheduler)
        vault = EvidenceVault(
            scope = backgroundScope, io = dispatcher,
            files = EvidenceFiles(folder.root.resolve("files"), folder.root.resolve("cache")),
            keyWrapper = FakeKeyWrapper(), repository = repository, connectivityMonitor = FakeConnectivityMonitor(),
            session = session, now = { Instant.EPOCH.plusMillis(testScheduler.currentTime) },
            retryDelaysMillis = listOf(3_600_000)
        )
        return EvidenceRecorder(
            scope = backgroundScope, recorderDispatcher = dispatcher, vault = vault, writer = writer,
            storage = { freeBytes }, activeEmergencyId = activeEmergency, currentUserId = vault.currentUserId,
            elapsedMillis = { testScheduler.currentTime }, now = { Instant.EPOCH.plusMillis(testScheduler.currentTime) }
        ).also { runCurrent() }
    }

    private fun audioItems() = vault.items.value.filter { it.kind == EvidenceKind.AUDIO }

    @Test
    fun `nothing records until the person starts it, and only for the active emergency`() = runTest {
        val recorder = recorder()
        advanceTimeBy(60_000)
        assertTrue(writer.started.isEmpty())
        assertNull(recorder.currentNotice())

        assertEquals(RecordingStopReason.EMERGENCY_ENDED, recorder.start(OTHER_EMERGENCY))
        activeEmergency.value = null
        runCurrent()
        assertEquals(RecordingStopReason.EMERGENCY_ENDED, recorder.start(EMERGENCY))
        assertTrue(writer.started.isEmpty())
    }

    @Test
    fun `recording shows a notice and seals a part every 30 seconds`() = runTest {
        val recorder = recorder()
        assertNull(recorder.start(EMERGENCY))
        runCurrent()
        assertEquals(R.string.recording_notification_title, recorder.currentNotice()!!.title)

        advanceTimeBy(RecordingPolicy.SEGMENT.toMillis() * 3 + 500)
        runCurrent()
        assertEquals(3, audioItems().size)
        assertEquals(4, writer.started.size) // the fourth part is being recorded
        assertEquals(1, audioItems().map { it.recordingRef }.distinct().size)
        assertEquals(listOf(0, 1, 2), audioItems().map { it.sequenceNo })

        recorder.stop()
        advanceTimeBy(2_000)
        runCurrent()
        assertFalse(recorder.state.value.isRecording)
        assertEquals(RecordingStopReason.STOPPED_BY_USER, recorder.state.value.lastStopReason)
        assertEquals(4, audioItems().size)
        assertEquals(4, recorder.state.value.partsSealed)
        assertNull(recorder.currentNotice())
        // Capture times are consecutive and inside the recording.
        audioItems().zipWithNext { a, b -> assertTrue(!b.capturedStartedAt.isBefore(a.capturedEndedAt)) }
    }

    @Test
    fun `ending the emergency stops the recording and keeps every part`() = runTest {
        val recorder = recorder()
        recorder.start(EMERGENCY)
        advanceTimeBy(45_000)
        activeEmergency.value = null
        advanceTimeBy(2_000)
        runCurrent()

        assertFalse(recorder.state.value.isRecording)
        assertEquals(RecordingStopReason.EMERGENCY_ENDED, recorder.state.value.lastStopReason)
        assertEquals(2, audioItems().size)
    }

    @Test
    fun `signing out stops the recording`() = runTest {
        val recorder = recorder()
        recorder.start(EMERGENCY)
        advanceTimeBy(5_000)
        session.value = null
        advanceTimeBy(2_000)
        runCurrent()

        assertEquals(RecordingStopReason.SIGNED_OUT, recorder.state.value.lastStopReason)
    }

    @Test
    fun `it stops before the phone runs out of storage, and refuses to start without space`() = runTest {
        val recorder = recorder()
        recorder.start(EMERGENCY)
        advanceTimeBy(10_000)
        freeBytes = RecordingPolicy.MIN_FREE_BYTES - 1
        advanceTimeBy(2_000)
        runCurrent()

        assertEquals(RecordingStopReason.STORAGE_LOW, recorder.state.value.lastStopReason)
        assertEquals(RecordingStopReason.STORAGE_LOW, recorder.start(EMERGENCY))
    }

    @Test
    fun `it stops after the maximum duration`() = runTest {
        val recorder = recorder()
        recorder.start(EMERGENCY)
        advanceTimeBy(RecordingPolicy.MAX_DURATION.toMillis() + 5_000)
        runCurrent()

        assertEquals(RecordingStopReason.MAX_DURATION, recorder.state.value.lastStopReason)
        assertEquals(RecordingPolicy.MAX_DURATION.dividedBy(RecordingPolicy.SEGMENT).toInt(), audioItems().size)
    }

    @Test
    fun `a busy microphone is reported, not hidden`() = runTest {
        writer.failStart = true
        val recorder = recorder()
        recorder.start(EMERGENCY)
        runCurrent()

        assertFalse(recorder.state.value.isRecording)
        assertTrue(recorder.state.value.startFailed)
        assertTrue(audioItems().isEmpty())
    }

    @Test
    fun `losing the microphone mid-recording stops it and keeps what was recorded`() = runTest {
        val recorder = recorder()
        recorder.start(EMERGENCY)
        advanceTimeBy(40_000)
        writer.onError!!.invoke()
        advanceTimeBy(2_000)
        runCurrent()

        assertEquals(RecordingStopReason.MICROPHONE_UNAVAILABLE, recorder.state.value.lastStopReason)
        assertEquals(2, audioItems().size)
    }

    @Test
    fun `the policy itself`() {
        assertNull(RecordingPolicy.stopReason(Duration.ofMinutes(10), Long.MAX_VALUE, true, true))
        assertEquals(RecordingStopReason.SIGNED_OUT, RecordingPolicy.stopReason(Duration.ZERO, Long.MAX_VALUE, true, false))
        assertEquals(RecordingStopReason.EMERGENCY_ENDED, RecordingPolicy.stopReason(Duration.ZERO, Long.MAX_VALUE, false, true))
        assertEquals(RecordingStopReason.STORAGE_LOW, RecordingPolicy.stopReason(Duration.ZERO, 1, true, true))
        assertEquals(RecordingStopReason.MAX_DURATION, RecordingPolicy.stopReason(Duration.ofMinutes(60), Long.MAX_VALUE, true, true))
        assertTrue(RecordingPolicy.shouldRotate(Duration.ofSeconds(30)))
        assertFalse(RecordingPolicy.shouldRotate(Duration.ofSeconds(29)))
    }

    private companion object {
        const val USER = 7L
        const val EMERGENCY = 42L
        const val OTHER_EMERGENCY = 43L
    }
}
