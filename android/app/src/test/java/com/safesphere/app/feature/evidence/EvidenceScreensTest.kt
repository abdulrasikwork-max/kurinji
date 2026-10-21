package com.safesphere.app.feature.evidence

import com.safesphere.app.feature.evidence.capture.RecorderState
import com.safesphere.app.feature.evidence.domain.CloudState
import com.safesphere.app.feature.evidence.domain.EvidenceKind
import com.safesphere.app.feature.evidence.domain.RecordingStopReason
import com.safesphere.app.feature.evidence.domain.RemoteEvidence
import com.safesphere.app.feature.evidence.domain.VaultItem
import com.safesphere.app.feature.evidence.session.EvidenceSyncState
import com.safesphere.app.feature.evidence.ui.EvidenceHint
import com.safesphere.app.feature.evidence.ui.EvidenceVaultViewModel
import com.safesphere.app.feature.evidence.ui.RowStatus
import com.safesphere.app.feature.evidence.ui.evidenceCardFor
import com.safesphere.app.feature.evidence.ui.formatElapsed
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 5: what the SOS Evidence card and the vault screen say, from pure functions. */
class EvidenceScreensTest {

    private val t0 = Instant.parse("2026-10-20T09:00:00Z")

    private fun item(ref: String, cloud: CloudState = CloudState.NOT_UPLOADED, onPhone: Boolean = true, refused: Boolean = false) = VaultItem(
        clientRef = ref, userId = 1, emergencyId = 2, kind = EvidenceKind.AUDIO, sequenceNo = 0, recordingRef = null,
        capturedStartedAt = t0, capturedEndedAt = t0.plusSeconds(30), plaintextBytes = 1000, ciphertextBytes = 1100,
        plaintextSha256 = "a".repeat(64), ciphertextSha256 = "b".repeat(64),
        wrappedKey = if (cloud == CloudState.DELETED) null else "key", onPhone = onPhone, cloud = cloud, uploadRefused = refused
    )

    @Test
    fun `the card counts what is sealed, verified and waiting, and shows the timer`() {
        val ui = evidenceCardFor(
            RecorderState(isRecording = true, emergencyId = 2, startedElapsedMillis = 1_000, partsSealed = 3),
            listOf(item("1"), item("2", CloudState.SEALED), item("3", CloudState.SEALED, onPhone = false), item("4", CloudState.DELETED, onPhone = false)),
            EvidenceSyncState(isOnline = true),
            microphoneInForeground = true,
            nowElapsedMillis = 96_000
        )
        assertTrue(ui.isRecording)
        assertEquals(95, ui.elapsedSeconds)
        assertEquals("1:35", formatElapsed(ui.elapsedSeconds))
        assertEquals(2, ui.sealedOnPhone)
        assertEquals(2, ui.verifiedInVault)
        assertEquals(1, ui.waitingForUpload)
        assertTrue(ui.hints.isEmpty())
    }

    @Test
    fun `the card warns honestly`() {
        val recordingWithoutService = evidenceCardFor(
            RecorderState(isRecording = true, startedElapsedMillis = 0), emptyList(), EvidenceSyncState(), false, 0
        )
        assertEquals(listOf(EvidenceHint.KEEP_APP_OPEN), recordingWithoutService.hints)

        val offline = evidenceCardFor(RecorderState(), listOf(item("1")), EvidenceSyncState(isOnline = false, vaultKeyAvailable = false), true, 0)
        assertEquals(listOf(EvidenceHint.OFFLINE_WAITING, EvidenceHint.NO_ESCROW), offline.hints)

        val stopped = evidenceCardFor(RecorderState(lastStopReason = RecordingStopReason.MAX_DURATION), emptyList(), EvidenceSyncState(), true, 0)
        assertEquals(listOf(EvidenceHint.STOPPED_MAX_DURATION), stopped.hints)

        val failed = evidenceCardFor(
            RecorderState(startFailed = true, lastStopReason = RecordingStopReason.MICROPHONE_UNAVAILABLE), emptyList(), EvidenceSyncState(), true, 0
        )
        assertEquals(listOf(EvidenceHint.START_FAILED), failed.hints)
        assertEquals("1:00:05", formatElapsed(3605))
    }

    @Test
    fun `vault rows say where each item is and offer only the actions that make sense`() {
        val remoteOnly = RemoteEvidence(99, "other-phone", 2, EvidenceKind.VIDEO, "SEALED", 5000, 5000, "c".repeat(64), true,
            t0.minusSeconds(60), t0.minusSeconds(30), t0, null, 1024)
        val rows = EvidenceVaultViewModel.rowsFor(
            listOf(item("phone"), item("verified", CloudState.SEALED), item("away", CloudState.SEALED, onPhone = false),
                item("refused", refused = true), item("gone", CloudState.DELETED, onPhone = false)),
            listOf(remoteOnly),
            uploadingRef = "phone"
        )
        val byRef = rows.associateBy { it.clientRef }

        assertEquals("other-phone", rows.first().clientRef) // ordered by capture time
        assertEquals(RowStatus.OTHER_PHONE, byRef.getValue("other-phone").status)
        assertFalse(byRef.getValue("other-phone").canOpen)
        assertEquals(RowStatus.UPLOADING, byRef.getValue("phone").status)
        assertFalse(byRef.getValue("phone").canRemoveFromPhone)
        assertEquals(RowStatus.VERIFIED_IN_VAULT, byRef.getValue("verified").status)
        assertTrue(byRef.getValue("verified").canRemoveFromPhone)
        assertEquals(RowStatus.ONLY_IN_VAULT, byRef.getValue("away").status)
        assertTrue(byRef.getValue("away").canDownload)
        assertFalse(byRef.getValue("away").canOpen)
        assertEquals(RowStatus.UPLOAD_REFUSED, byRef.getValue("refused").status)
        assertTrue(byRef.getValue("refused").canRetryUpload)
        assertFalse(byRef.getValue("verified").canRetryUpload)
        assertTrue(byRef.getValue("away").canVerify) // the vault copy can still be re-checked by the server
        assertEquals(RowStatus.DELETED, byRef.getValue("gone").status)
        assertFalse(byRef.getValue("gone").canDelete)
        assertFalse(byRef.getValue("gone").canOpen)
    }

    @Test
    fun `a vault copy found damaged after the phone copy was removed is shown as such, not as uploading`() {
        val rows = EvidenceVaultViewModel.rowsFor(
            listOf(item("damaged", CloudState.UPLOADING, onPhone = false), item("going-up", CloudState.UPLOADING)),
            emptyList(),
            uploadingRef = null
        ).associateBy { it.clientRef }

        assertEquals(RowStatus.DAMAGED_IN_VAULT, rows.getValue("damaged").status)
        assertFalse(rows.getValue("damaged").canOpen)
        assertEquals(RowStatus.UPLOADING, rows.getValue("going-up").status)
    }
}
