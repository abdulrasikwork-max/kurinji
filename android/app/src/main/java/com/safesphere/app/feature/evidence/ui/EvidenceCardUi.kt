package com.safesphere.app.feature.evidence.ui

import com.safesphere.app.feature.evidence.capture.RecorderState
import com.safesphere.app.feature.evidence.domain.CloudState
import com.safesphere.app.feature.evidence.domain.RecordingStopReason
import com.safesphere.app.feature.evidence.domain.VaultItem
import com.safesphere.app.feature.evidence.session.EvidenceSyncState

/** One extra line under the recording status, most important first. */
enum class EvidenceHint {
    /** Recording, but Android did not let the service keep the microphone with the screen off. */
    KEEP_APP_OPEN,
    START_FAILED,
    STOPPED_MAX_DURATION,
    STOPPED_STORAGE_LOW,
    STOPPED_MICROPHONE_LOST,
    STOPPED_SAVE_FAILED,
    OFFLINE_WAITING,
    NO_ESCROW
}

/** What the SOS screen's Evidence card shows. Built by the pure [evidenceCardFor] (unit-tested). */
data class EvidenceCardUi(
    val isRecording: Boolean = false,
    val elapsedSeconds: Long = 0,
    val partsSealed: Int = 0,
    val sealedOnPhone: Int = 0,
    val verifiedInVault: Int = 0,
    val waitingForUpload: Int = 0,
    val uploading: Boolean = false,
    val hints: List<EvidenceHint> = emptyList()
)

/**
 * @param items this emergency's items of the signed-in person
 * @param nowElapsedMillis monotonic clock, for the timer
 * @param microphoneInForeground the tracking service holds the microphone type (recording survives the screen off)
 */
fun evidenceCardFor(
    recorder: RecorderState,
    items: List<VaultItem>,
    sync: EvidenceSyncState,
    microphoneInForeground: Boolean,
    nowElapsedMillis: Long
): EvidenceCardUi {
    val live = items.filterNot { it.isDeleted }
    val waiting = live.count { it.needsUpload }
    val hints = buildList {
        if (recorder.isRecording && !microphoneInForeground) add(EvidenceHint.KEEP_APP_OPEN)
        if (!recorder.isRecording) {
            if (recorder.startFailed) add(EvidenceHint.START_FAILED)
            when (recorder.lastStopReason) {
                RecordingStopReason.MAX_DURATION -> add(EvidenceHint.STOPPED_MAX_DURATION)
                RecordingStopReason.STORAGE_LOW -> add(EvidenceHint.STOPPED_STORAGE_LOW)
                RecordingStopReason.MICROPHONE_UNAVAILABLE -> if (!recorder.startFailed) add(EvidenceHint.STOPPED_MICROPHONE_LOST)
                RecordingStopReason.SAVE_FAILED -> if (!recorder.startFailed) add(EvidenceHint.STOPPED_SAVE_FAILED)
                else -> Unit
            }
        }
        if (waiting > 0 && !sync.isOnline) add(EvidenceHint.OFFLINE_WAITING)
        if (sync.vaultKeyAvailable == false && live.isNotEmpty()) add(EvidenceHint.NO_ESCROW)
    }
    val started = recorder.startedElapsedMillis
    return EvidenceCardUi(
        isRecording = recorder.isRecording,
        elapsedSeconds = if (recorder.isRecording && started != null) ((nowElapsedMillis - started) / 1000).coerceAtLeast(0) else 0,
        partsSealed = recorder.partsSealed,
        sealedOnPhone = live.count { it.onPhone },
        verifiedInVault = live.count { it.cloud == CloudState.SEALED },
        waitingForUpload = waiting,
        uploading = sync.uploadingRef != null && live.any { it.clientRef == sync.uploadingRef },
        hints = hints
    )
}

/** "m:ss" or "h:mm:ss" for the recording timer. */
fun formatElapsed(totalSeconds: Long): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}
