package com.safesphere.app.feature.evidence.ui

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.safesphere.app.AppContainer
import com.safesphere.app.feature.evidence.capture.EvidenceRecorder
import com.safesphere.app.feature.evidence.data.PendingCapture
import com.safesphere.app.feature.evidence.domain.EvidenceKind
import com.safesphere.app.feature.evidence.domain.RecordingPolicy
import com.safesphere.app.feature.evidence.domain.RecordingStopReason
import com.safesphere.app.feature.evidence.session.EvidenceVault
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The Evidence card on the SOS screen (Phase 5): start/stop audio, photo and video through the camera app, and the
 * honest status of what is sealed, verified and waiting. All real work lives in the application-scoped holders
 * ([EvidenceRecorder], [EvidenceVault]), so it continues when the screen goes away.
 */
class EvidenceCaptureViewModel(
    private val recorder: EvidenceRecorder,
    private val vault: EvidenceVault,
    microphoneInForeground: StateFlow<Boolean>,
    private val freeBytes: () -> Long,
    private val elapsedMillis: () -> Long = SystemClock::elapsedRealtime,
    ticker: Flow<Unit> = flow { while (true) { emit(Unit); delay(1_000) } }
) : ViewModel() {

    private val emergencyId = MutableStateFlow<Long?>(null)

    /** The camera-app capture in progress (kept here so a rotation while the camera is open does not lose it). */
    private var pendingCamera: PendingCapture? = null

    val card: StateFlow<EvidenceCardUi> =
        combine(recorder.state, vault.items, vault.sync, microphoneInForeground, combine(emergencyId, ticker) { id, _ -> id }) {
                recording, _, sync, micForeground, id ->
            evidenceCardFor(recording, vault.itemsFor(vault.currentUserId.value, id ?: -1), sync, micForeground, elapsedMillis())
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EvidenceCardUi())

    /** The SOS screen tells which emergency is active (null when none). */
    fun setEmergency(id: Long?) {
        emergencyId.value = id
    }

    /** RECORD_AUDIO is granted. Returns why it cannot start, or null when it started. */
    fun startRecording(): RecordingStopReason? {
        val id = emergencyId.value ?: return RecordingStopReason.EMERGENCY_ENDED
        return recorder.start(id)
    }

    fun stopRecording() = recorder.stop()

    /** Reserves the private file the camera app will write; null (with the reason) when capture is not possible. */
    suspend fun prepareCameraCapture(kind: EvidenceKind): Result<File> {
        val id = emergencyId.value ?: return Result.failure(CaptureRefused(RecordingStopReason.EMERGENCY_ENDED))
        val userId = vault.currentUserId.value ?: return Result.failure(CaptureRefused(RecordingStopReason.SIGNED_OUT))
        RecordingPolicy.canStart(freeBytes(), true, true)?.let { return Result.failure(CaptureRefused(it)) }
        val (pending, file) = vault.prepareCapture(userId, id, kind)
        pendingCamera = pending
        return Result.success(file)
    }

    /** The camera app returned. Saved: seal it now. Cancelled: drop the empty file. */
    fun onCameraResult(saved: Boolean) {
        val pending = pendingCamera ?: return
        pendingCamera = null
        viewModelScope.launch {
            if (saved) vault.completeCapture(pending) else vault.discardCapture(pending)
        }
    }

    /** The camera app could not be opened at all. */
    fun onCameraUnavailable() = onCameraResult(saved = false)

    class CaptureRefused(val reason: RecordingStopReason) : Exception(reason.name)

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer {
                EvidenceCaptureViewModel(
                    recorder = container.evidenceRecorder,
                    vault = container.evidenceVault,
                    microphoneInForeground = container.microphoneInForeground,
                    freeBytes = { android.os.StatFs(container.evidenceFiles.vaultDir.parentFile!!.absolutePath).availableBytes }
                )
            }
        }
    }
}
