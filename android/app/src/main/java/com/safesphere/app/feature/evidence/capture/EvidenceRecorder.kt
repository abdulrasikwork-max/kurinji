package com.safesphere.app.feature.evidence.capture

import com.safesphere.app.R
import com.safesphere.app.core.tracking.TrackingNotice
import com.safesphere.app.core.tracking.TrackingNoticeSource
import com.safesphere.app.feature.evidence.domain.EvidenceKind
import com.safesphere.app.feature.evidence.domain.RecordingPolicy
import com.safesphere.app.feature.evidence.domain.RecordingStopReason
import com.safesphere.app.feature.evidence.session.EvidenceVault
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the SOS card and the notification show about audio evidence. */
data class RecorderState(
    val isRecording: Boolean = false,
    val emergencyId: Long? = null,
    /** Monotonic start (elapsed-realtime milliseconds) for the on-screen timer. */
    val startedElapsedMillis: Long? = null,
    val startedAt: Instant? = null,
    val partsSealed: Int = 0,
    /** Why the last recording ended (null while recording or before the first one). */
    val lastStopReason: RecordingStopReason? = null,
    /** The last attempt to start failed (no microphone, refused by Android). */
    val startFailed: Boolean = false
)

/**
 * The ONE holder of audio evidence recording (Phase 5). Started and stopped only by the person, from the visible SOS
 * screen, while an emergency is ACTIVE; the tracking foreground service reads [notices] to show "Recording audio" and to
 * add the microphone service type, and Android shows its own microphone indicator. Nothing records in secret.
 *
 * The recording is a chain of [RecordingPolicy.SEGMENT] parts. Each finished part is handed to [EvidenceVault], which
 * encrypts and hashes it at once and queues it for upload; the part being recorded is sealed after a crash by the
 * vault's recovery. Parts of one recording share a recordingRef.
 *
 * Time: the timer uses the monotonic [elapsedMillis] clock (immune to the person or the network changing the phone's
 * clock); the stored capture times use the wall clock [now] (checked by the server against the emergency).
 */
class EvidenceRecorder(
    private val scope: CoroutineScope,
    /** Single-threaded: MediaRecorder must not be used from two threads. */
    private val recorderDispatcher: CoroutineDispatcher,
    private val vault: EvidenceVault,
    private val writer: AudioSegmentWriter,
    private val storage: StorageSpace,
    /** The ACTIVE emergency the screen shows (null = none). */
    activeEmergencyId: Flow<Long?>,
    private val currentUserId: StateFlow<Long?>,
    private val elapsedMillis: () -> Long,
    private val now: () -> Instant = Instant::now,
    private val tickMillis: Long = 1_000
) : TrackingNoticeSource {

    private val _state = MutableStateFlow(RecorderState())
    val state: StateFlow<RecorderState> = _state.asStateFlow()

    private val activeEmergency = MutableStateFlow<Long?>(null)
    private var job: Job? = null
    private var stopRequest: RecordingStopReason? = null

    init {
        scope.launch {
            activeEmergencyId.collect { id ->
                activeEmergency.value = id
                val recording = _state.value
                if (recording.isRecording && recording.emergencyId != id) requestStop(RecordingStopReason.EMERGENCY_ENDED)
            }
        }
        scope.launch {
            currentUserId.collect { if (it == null && _state.value.isRecording) requestStop(RecordingStopReason.SIGNED_OUT) }
        }
    }

    // ---- tracking service ------------------------------------------------------------------------

    override fun currentNotice(): TrackingNotice? = noticeFor(_state.value)
    override val notices: Flow<TrackingNotice?> get() = state.map(::noticeFor)

    // ---- controls -------------------------------------------------------------------------------------

    /**
     * Starts recording for [emergencyId] (the caller has RECORD_AUDIO). Returns the reason it cannot start, or null.
     */
    fun start(emergencyId: Long): RecordingStopReason? {
        if (_state.value.isRecording) return null
        val userId = currentUserId.value
        val refusal = RecordingPolicy.canStart(storage.freeBytes(), activeEmergency.value == emergencyId, userId != null)
        if (refusal != null || userId == null) return refusal ?: RecordingStopReason.SIGNED_OUT
        stopRequest = null
        _state.value = RecorderState(
            isRecording = true,
            emergencyId = emergencyId,
            startedElapsedMillis = elapsedMillis(),
            startedAt = now()
        )
        job = scope.launch { record(userId, emergencyId, UUID.randomUUID().toString()) }
        return null
    }

    fun stop() = requestStop(RecordingStopReason.STOPPED_BY_USER)

    private fun requestStop(reason: RecordingStopReason) {
        if (!_state.value.isRecording) return
        if (stopRequest == null) stopRequest = reason
    }

    // ---- the recording loop ---------------------------------------------------------------------------

    private suspend fun record(userId: Long, emergencyId: Long, recordingRef: String) {
        val startedElapsed = _state.value.startedElapsedMillis ?: elapsedMillis()
        var reason: RecordingStopReason
        var firstPart = true
        try {
            while (true) {
                val prepared = try {
                    vault.prepareCapture(userId, emergencyId, EvidenceKind.AUDIO, recordingRef)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                if (prepared == null) {
                    if (firstPart) _state.value = _state.value.copy(startFailed = true)
                    reason = RecordingStopReason.SAVE_FAILED
                    break
                }
                val (pending, file) = prepared
                val started = try {
                    withContext(recorderDispatcher) { writer.start(file) { requestStop(RecordingStopReason.MICROPHONE_UNAVAILABLE) } }
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    false
                }
                if (!started) {
                    vault.discardCapture(pending)
                    if (firstPart) _state.value = _state.value.copy(startFailed = true)
                    reason = RecordingStopReason.MICROPHONE_UNAVAILABLE
                    break
                }
                firstPart = false
                val partStarted = elapsedMillis()
                var stopNow: RecordingStopReason? = null
                while (true) {
                    delay(tickMillis)
                    stopNow = stopRequest ?: RecordingPolicy.stopReason(
                        Duration.ofMillis(elapsedMillis() - startedElapsed), storage.freeBytes(),
                        activeEmergency.value == emergencyId, currentUserId.value == userId
                    )
                    if (stopNow != null || RecordingPolicy.shouldRotate(Duration.ofMillis(elapsedMillis() - partStarted))) break
                }
                val usable = withContext(recorderDispatcher) { writer.stop() }
                // Seal in the background: the next part starts right away (gap of a fraction of a second).
                scope.launch {
                    val sealed = if (usable) vault.completeCapture(pending, now()) else null.also { vault.discardCapture(pending) }
                    if (sealed != null) _state.value = _state.value.copy(partsSealed = _state.value.partsSealed + 1)
                }
                if (stopNow != null) {
                    reason = stopNow
                    break
                }
            }
        } catch (e: CancellationException) {
            withContext(kotlinx.coroutines.NonCancellable + recorderDispatcher) { writer.stop() }
            throw e
        }
        _state.value = _state.value.copy(isRecording = false, startedElapsedMillis = null, lastStopReason = reason)
        stopRequest = null
        job = null
    }

    companion object {
        /** Highest priority in the ONE tracking notification: the person must always see that audio is recorded. */
        fun noticeFor(state: RecorderState): TrackingNotice? =
            if (state.isRecording) TrackingNotice(R.string.recording_notification_title, R.string.recording_notification_text) else null
    }
}
