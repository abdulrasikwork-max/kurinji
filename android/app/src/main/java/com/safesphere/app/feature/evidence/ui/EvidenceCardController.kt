package com.safesphere.app.feature.evidence.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.safesphere.app.R
import com.safesphere.app.core.tracking.SafetyTrackingService
import com.safesphere.app.databinding.ViewEvidenceCardBinding
import com.safesphere.app.feature.evidence.domain.EvidenceKind
import com.safesphere.app.feature.evidence.domain.RecordingStopReason
import kotlinx.coroutines.launch

/**
 * Wires the Evidence card of the SOS screen (Phase 5). Create it as a Fragment PROPERTY (it registers activity-result
 * launchers, which must happen before the fragment starts), then call [bind] from onViewCreated.
 *
 * Permissions in context: RECORD_AUDIO is asked only when the person taps "Record audio", with the card's text as the
 * reason; a refusal is respected (a later tap after "Don't ask again" opens the app settings). Photo and video use the
 * phone's camera app, so no camera permission exists to ask for.
 */
class EvidenceCardController(
    private val fragment: Fragment,
    private val viewModel: () -> EvidenceCaptureViewModel
) {
    private var binding: ViewEvidenceCardBinding? = null
    private var askedForMicrophone = false

    private val microphoneLauncher = fragment.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            startRecording()
        } else if (!fragment.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) && askedForMicrophone) {
            toast(R.string.evidence_microphone_blocked)
        } else {
            toast(R.string.evidence_microphone_denied)
        }
    }

    private val photoLauncher = fragment.registerForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        viewModel().onCameraResult(saved)
        if (saved) toast(R.string.evidence_photo_sealed)
    }

    private val videoLauncher = fragment.registerForActivityResult(CaptureVideoWithLimits()) { saved ->
        viewModel().onCameraResult(saved)
        if (saved) toast(R.string.evidence_video_sealed)
    }

    fun bind(card: ViewEvidenceCardBinding, openVault: () -> Unit) {
        binding = card
        card.evidenceRecordButton.setOnClickListener { onRecordTapped() }
        card.evidencePhotoButton.setOnClickListener { capture(EvidenceKind.PHOTO) }
        card.evidenceVideoButton.setOnClickListener { capture(EvidenceKind.VIDEO) }
        card.evidenceOpenVaultButton.setOnClickListener { openVault() }
    }

    fun unbind() {
        binding = null
    }

    fun render(ui: EvidenceCardUi) {
        val card = binding ?: return
        val context = fragment.requireContext()
        card.evidenceRecordButton.setText(if (ui.isRecording) R.string.action_stop_recording else R.string.action_record_audio)
        card.evidenceRecordingStatus.isVisible = ui.isRecording
        if (ui.isRecording) {
            card.evidenceRecordingStatus.text = context.resources.getQuantityString(
                R.plurals.evidence_recording_status, ui.partsSealed, formatElapsed(ui.elapsedSeconds), ui.partsSealed
            )
        }
        card.evidenceNotice.setWarning(ui.isRecording)
        card.evidenceNotice.setMessage(context.getString(if (ui.isRecording) R.string.evidence_card_recording else R.string.evidence_card_intro))

        val hints = ui.hints.map { context.getString(hintText(it)) }
        card.evidenceHints.isVisible = hints.isNotEmpty()
        card.evidenceHints.text = hints.joinToString("\n")

        card.evidenceSummary.text = when {
            ui.sealedOnPhone == 0 && ui.verifiedInVault == 0 -> context.getString(R.string.evidence_summary_none)
            else -> context.getString(
                R.string.evidence_summary, ui.sealedOnPhone, ui.verifiedInVault, ui.waitingForUpload
            ) + if (ui.uploading) " " + context.getString(R.string.evidence_summary_uploading) else ""
        }
    }

    private fun onRecordTapped() {
        val vm = viewModel()
        if (vm.card.value.isRecording) {
            vm.stopRecording()
            return
        }
        val context = fragment.requireContext()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startRecording()
        } else if (askedForMicrophone && !fragment.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            openAppSettings()
        } else {
            askedForMicrophone = true
            microphoneLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startRecording() {
        when (val refusal = viewModel().startRecording()) {
            null -> {
                // From this visible screen: the one tracking service adds the microphone type so recording continues
                // with the screen off. If Android refuses, the card says "keep SafeSphere open".
                SafetyTrackingService.start(fragment.requireContext())
                toast(R.string.evidence_recording_started)
            }
            else -> toast(refusalText(refusal))
        }
    }

    private fun capture(kind: EvidenceKind) {
        val context = fragment.requireContext()
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            viewModel().prepareCameraCapture(kind)
                .onSuccess { file ->
                    val uri = FileProvider.getUriForFile(context, context.packageName + ".evidence", file)
                    try {
                        if (kind == EvidenceKind.PHOTO) photoLauncher.launch(uri) else videoLauncher.launch(uri)
                    } catch (e: ActivityNotFoundException) {
                        viewModel().onCameraUnavailable()
                        toast(R.string.evidence_camera_unavailable)
                    }
                }
                .onFailure { error ->
                    val reason = (error as? EvidenceCaptureViewModel.CaptureRefused)?.reason
                    toast(if (reason != null) refusalText(reason) else R.string.evidence_camera_unavailable)
                }
        }
    }

    private fun openAppSettings() {
        val context = fragment.requireContext()
        try {
            fragment.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
        } catch (e: ActivityNotFoundException) {
            toast(R.string.evidence_microphone_blocked)
        }
    }

    private fun toast(text: Int) {
        if (fragment.isAdded) Toast.makeText(fragment.requireContext(), text, Toast.LENGTH_LONG).show()
    }

    /**
     * Asks the camera app to stop at 10 minutes / 190 MB, so a video always fits the vault's 200 MB item limit (most
     * camera apps honour these standard extras; if one does not, the item stays on the phone and the vault says so).
     */
    private class CaptureVideoWithLimits : ActivityResultContracts.CaptureVideo() {
        override fun createIntent(context: android.content.Context, input: Uri): Intent =
            super.createIntent(context, input)
                .putExtra(android.provider.MediaStore.EXTRA_DURATION_LIMIT, MAX_VIDEO_SECONDS)
                .putExtra(android.provider.MediaStore.EXTRA_SIZE_LIMIT, MAX_VIDEO_BYTES)
    }

    companion object {
        private const val MAX_VIDEO_SECONDS = 600
        private const val MAX_VIDEO_BYTES = 190L * 1024 * 1024

        fun hintText(hint: EvidenceHint): Int = when (hint) {
            EvidenceHint.KEEP_APP_OPEN -> R.string.evidence_hint_keep_open
            EvidenceHint.START_FAILED -> R.string.evidence_hint_start_failed
            EvidenceHint.STOPPED_MAX_DURATION -> R.string.evidence_hint_max_duration
            EvidenceHint.STOPPED_STORAGE_LOW -> R.string.evidence_hint_storage_low
            EvidenceHint.STOPPED_MICROPHONE_LOST -> R.string.evidence_hint_microphone_lost
            EvidenceHint.STOPPED_SAVE_FAILED -> R.string.evidence_hint_save_failed
            EvidenceHint.OFFLINE_WAITING -> R.string.evidence_hint_offline
            EvidenceHint.NO_ESCROW -> R.string.evidence_hint_no_escrow
        }

        fun refusalText(reason: RecordingStopReason): Int = when (reason) {
            RecordingStopReason.STORAGE_LOW -> R.string.evidence_hint_storage_low
            RecordingStopReason.SIGNED_OUT -> R.string.evidence_refused_signed_out
            RecordingStopReason.EMERGENCY_ENDED -> R.string.evidence_refused_no_emergency
            else -> R.string.evidence_hint_start_failed
        }
    }
}
