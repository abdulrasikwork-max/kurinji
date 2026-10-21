package com.safesphere.app.feature.emergency.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.safesphere.app.R
import com.safesphere.app.appContainer
import com.safesphere.app.core.location.LocationPermissions
import com.safesphere.app.core.tracking.TrackingServiceStarter
import com.safesphere.app.core.ui.ViewBindingFragment
import com.safesphere.app.core.ui.collectWhenStarted
import com.safesphere.app.core.ui.showSessionExpiredDialog
import com.safesphere.app.core.ui.toMessage
import com.safesphere.app.databinding.FragmentEmergencyBinding
import com.safesphere.app.feature.emergency.domain.EmergencyStatus
import com.safesphere.app.feature.emergency.location.LocationSharingStatus
import android.widget.Toast
import com.safesphere.app.feature.circlealert.domain.AlertTarget
import com.safesphere.app.feature.circlealert.ui.deliveryLabelFor
import com.safesphere.app.feature.circlealert.ui.openEmergencyDialer
import com.safesphere.app.feature.circlealert.ui.openSmsComposer
import com.safesphere.app.feature.safetycircle.domain.SafetyContact
import androidx.core.os.bundleOf
import com.safesphere.app.feature.evidence.ui.EvidenceCaptureViewModel
import com.safesphere.app.feature.evidence.ui.EvidenceCardController
import com.safesphere.app.feature.evidence.ui.EvidenceVaultFragment
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Manual SOS screen. It draws the state held by [EmergencyViewModel] and forwards button taps.
 *
 * Phase 4A: while an emergency is active it asks for location (in context, once automatically, then via a
 * button), then for notifications (Android 13+), and starts the tracking foreground service so location
 * keeps being saved with the screen off.
 * Phase 4D: "Call 112" opens the dialer (the person presses call); each Safety Circle contact shows what they were
 * told and a "Text" button that opens the SMS app. Automatic SMS only if the person opted in.
 * Phase 5: an Evidence card (audio recorded only while the person keeps it on; photo / video through the camera app),
 * everything encrypted on the phone at once ([EvidenceCardController]).
 */
class EmergencyFragment : ViewBindingFragment<FragmentEmergencyBinding>(FragmentEmergencyBinding::inflate) {

    private val viewModel by viewModels<EmergencyViewModel> { EmergencyViewModel.factory(appContainer) }

    private val serviceStarter = TrackingServiceStarter(this) { viewModel.consumeNotificationPrompt() }

    /** Phase 5: audio / photo / video evidence for the active emergency. */
    private val evidenceViewModel by viewModels<EvidenceCaptureViewModel> { EvidenceCaptureViewModel.factory(appContainer) }
    private val evidenceCard = EvidenceCardController(this) { evidenceViewModel }

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val status = LocationPermissions.statusAfterRequest(
                fineGranted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true,
                coarseGranted = result[Manifest.permission.ACCESS_COARSE_LOCATION] == true,
                shouldShowRationale = shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)
            )
            viewModel.onLocationPermissionResult(status)
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.startButton.setOnClickListener { viewModel.onStartSosClicked() }
        binding.cancelCountdownButton.setOnClickListener { viewModel.onCancelCountdownClicked() }
        binding.safeButton.setOnClickListener { viewModel.onImSafeClicked() }
        binding.falseAlarmButton.setOnClickListener { viewModel.onFalseAlarmClicked() }
        binding.retryButton.setOnClickListener { viewModel.onRetryStartClicked() }
        binding.dismissFailureButton.setOnClickListener { viewModel.onDismissStartFailureClicked() }
        binding.doneButton.setOnClickListener { findNavController().navigateUp() }
        binding.historyButton.setOnClickListener { findNavController().navigate(R.id.emergencyHistoryFragment) }
        binding.callEmergencyButton.setOnClickListener {
            viewModel.onCallEmergencyNumberClicked()
            openDialer()
        }
        binding.failedCallButton.setOnClickListener { openDialer() }

        evidenceCard.bind(binding.evidenceCard) {
            val active = viewModel.uiState.value as? EmergencyUiState.Active
            findNavController().navigate(
                R.id.evidenceVaultFragment, bundleOf(EvidenceVaultFragment.ARG_EMERGENCY_ID to (active?.event?.id ?: -1L))
            )
        }
        collectWhenStarted(evidenceViewModel.card, evidenceCard::render)

        collectWhenStarted(viewModel.uiState) { state ->
            evidenceViewModel.setEmergency((state as? EmergencyUiState.Active)?.event?.id)
            render(state)
            renderLocation(viewModel.locationUi.value)
        }
        collectWhenStarted(viewModel.circleAlertState) {
            (viewModel.uiState.value as? EmergencyUiState.Active)?.let(::renderCircle)
        }
        collectWhenStarted(viewModel.locationUi, ::renderLocation)
        collectWhenStarted(viewModel.sessionExpired) { expired ->
            if (expired) {
                viewModel.onSessionExpiredHandled()
                showSessionExpiredDialog()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Back from Settings or the permission dialog: re-check permission and the location setting.
        viewModel.onScreenVisible()
    }

    override fun onStop() {
        super.onStop()
        // Next time the screen is visible, make sure the service is (still) running.
        serviceStarter.reset()
    }

    override fun onDestroyView() {
        evidenceCard.unbind()
        super.onDestroyView()
    }

    private fun render(state: EmergencyUiState) {
        showOnly(
            when (state) {
                EmergencyUiState.Loading -> binding.loadingPanel
                EmergencyUiState.Idle -> binding.idlePanel
                is EmergencyUiState.Countdown -> binding.countdownPanel
                EmergencyUiState.Starting -> binding.startingPanel
                is EmergencyUiState.Active -> binding.activePanel
                is EmergencyUiState.Ended -> binding.endedPanel
                is EmergencyUiState.StartFailed -> binding.failedPanel
            }
        )
        // Keep the screen awake while a countdown or an emergency is running. (No permission needed.)
        binding.root.keepScreenOn = state is EmergencyUiState.Countdown || state is EmergencyUiState.Active

        when (state) {
            is EmergencyUiState.Countdown -> renderCountdown(state)
            is EmergencyUiState.Active -> renderActive(state)
            is EmergencyUiState.Ended -> renderEnded(state)
            is EmergencyUiState.StartFailed ->
                binding.failedMessage.text = state.failure.toMessage(requireContext())
            else -> Unit
        }
    }

    private fun renderCountdown(state: EmergencyUiState.Countdown) {
        binding.countdownNumber.text = state.secondsRemaining.toString()
        binding.countdownNumber.contentDescription =
            getString(R.string.cd_countdown_seconds, state.secondsRemaining)
    }

    private fun renderActive(state: EmergencyUiState.Active) {
        binding.activeStartedAt.text = getString(
            R.string.emergency_started_at, EmergencyTimeFormatter.format(state.event.startedAt)
        )
        binding.restoredOfflineNotice.isVisible = state.restoredOffline
        renderCircle(state)

        binding.endProgress.isVisible = state.isEnding
        binding.safeButton.isEnabled = !state.isEnding
        binding.falseAlarmButton.isEnabled = !state.isEnding

        val failure = state.failure
        binding.activeErrorNotice.isVisible = failure != null
        if (failure != null) binding.activeErrorNotice.setMessage(failure.toMessage(requireContext()))

        if (state.passwordNeededFor != null && endPasswordDialog == null) askPasswordToEnd()
    }

    private var endPasswordDialog: androidx.appcompat.app.AlertDialog? = null

    /** Phase 10A.4: this SOS was started on another phone; ending it here needs the account password. */
    private fun askPasswordToEnd() {
        val context = requireContext()
        val field = com.google.android.material.textfield.TextInputEditText(context).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val layout = com.google.android.material.textfield.TextInputLayout(context).apply {
            hint = getString(R.string.label_password)
            endIconMode = com.google.android.material.textfield.TextInputLayout.END_ICON_PASSWORD_TOGGLE
            addView(field)
            val padding = resources.getDimensionPixelSize(R.dimen.space_lg)
            setPadding(padding, 0, padding, 0)
        }
        endPasswordDialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
            .setTitle(R.string.emergency_end_elsewhere_title)
            .setMessage(R.string.emergency_end_elsewhere_message)
            .setView(layout)
            .setNegativeButton(R.string.action_cancel) { _, _ -> viewModel.onEndPasswordDismissed() }
            .setPositiveButton(R.string.emergency_end_elsewhere_action) { _, _ ->
                val password = field.text?.toString().orEmpty()
                field.setText("")
                if (password.isEmpty()) viewModel.onEndPasswordDismissed() else viewModel.onEndWithPassword(password)
            }
            .setOnCancelListener { viewModel.onEndPasswordDismissed() }
            .setOnDismissListener { endPasswordDialog = null }
            .create()
            .also {
                it.window?.setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE,
                    android.view.WindowManager.LayoutParams.FLAG_SECURE)
                it.show()
            }
    }

    /** Phase 4D: who has been told what, honestly, and "Text" per contact (the person sends it from the SMS app). */
    private fun renderCircle(state: EmergencyUiState.Active) {
        val alerts = viewModel.circleAlertState.value
        val target = AlertTarget.Emergency(state.event.id)
        binding.circleNotice.setMessage(getString(
            if (alerts.autoActive) R.string.emergency_circle_auto_notice else R.string.emergency_circle_manual_notice
        ))
        binding.circleList.adapter = CircleMemberAdapter(
            members = state.circle,
            statusFor = { contact -> deliveryLabelFor(alerts, target, contact.id) },
            onText = ::textContact
        )
        binding.circleEmpty.isVisible = state.circle.isEmpty()
        binding.circleList.isVisible = state.circle.isNotEmpty()
    }

    private fun openDialer() {
        if (!openEmergencyDialer(requireContext())) {
            Toast.makeText(requireContext(), R.string.dialer_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    private fun textContact(contact: SafetyContact) {
        val text = viewModel.composerTextFor() ?: return
        if (openSmsComposer(requireContext(), contact.phoneNumber, text)) {
            viewModel.onComposerOpened(contact)
        } else {
            Toast.makeText(requireContext(), R.string.sms_app_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    private fun renderEnded(state: EmergencyUiState.Ended) {
        val resolved = state.event.status == EmergencyStatus.RESOLVED
        binding.endedTitle.setText(if (resolved) R.string.emergency_ended_resolved_title else R.string.emergency_ended_cancelled_title)
        binding.endedMessage.setText(when {
            // Phase 10A.4: ended from another phone (with the account password); this phone did not say "I'm safe".
            state.event.endedElsewhere -> R.string.emergency_ended_elsewhere_message
            resolved -> R.string.emergency_ended_resolved_message
            else -> R.string.emergency_ended_cancelled_message
        })
    }

    // ---- location -------------------------------------------------------------------------------

    private fun renderLocation(ui: EmergencyLocationUi) {
        if (viewModel.uiState.value !is EmergencyUiState.Active) return
        val card = locationCardFor(ui)

        binding.locationNotice.setWarning(card.isWarning)
        binding.locationNotice.setMessage(card.serverMessage ?: getString(card.message))

        val fix = ui.sharing.lastFix
        binding.locationDetails.isVisible = card.showDetails && fix != null
        if (fix != null) {
            val latitude = String.format(Locale.ROOT, "%.5f", fix.latitude)
            val longitude = String.format(Locale.ROOT, "%.5f", fix.longitude)
            val accuracy = fix.accuracyMeters
            binding.locationDetails.text = if (accuracy != null) {
                getString(R.string.location_details, latitude, longitude, accuracy.roundToInt())
            } else {
                getString(R.string.location_details_no_accuracy, latitude, longitude)
            }
        }

        val savedLines = buildList {
            if (ui.sharing.isTracking || ui.sharing.lastSavedAt != null) {
                val savedAt = ui.sharing.lastSavedAt
                add(if (savedAt != null) getString(R.string.location_saved_at, EmergencyTimeFormatter.formatTime(savedAt)) else getString(R.string.location_not_saved_yet))
            }
            if (ui.sharing.pendingCount > 0) {
                add(resources.getQuantityString(R.plurals.location_pending_count, ui.sharing.pendingCount, ui.sharing.pendingCount))
            }
        }
        binding.locationSavedAt.isVisible = savedLines.isNotEmpty() && ui.sharing.status != LocationSharingStatus.PERMISSION_NEEDED
        binding.locationSavedAt.text = savedLines.joinToString(" ")

        val hints = buildList {
            if (card.showKeepAppOpenHint) add(getString(R.string.location_hint_keep_app_open))
            if (card.showApproximateHint) add(getString(R.string.location_hint_approximate))
            if (card.showLowAccuracyHint) add(getString(R.string.location_hint_low_accuracy))
            if (card.showRejectedHint) add(getString(R.string.location_hint_rejected))
        }
        binding.locationHints.isVisible = hints.isNotEmpty()
        binding.locationHints.text = hints.joinToString("\n")

        binding.locationActionButton.isVisible = card.action != null
        when (card.action) {
            LocationCardAction.ALLOW_LOCATION -> {
                binding.locationActionButton.setText(R.string.action_allow_location)
                binding.locationActionButton.setOnClickListener { requestLocationPermission() }
            }
            LocationCardAction.OPEN_APP_SETTINGS -> {
                binding.locationActionButton.setText(R.string.action_open_settings)
                binding.locationActionButton.setOnClickListener { openAppSettings() }
            }
            LocationCardAction.OPEN_LOCATION_SETTINGS -> {
                binding.locationActionButton.setText(R.string.action_turn_on_location)
                binding.locationActionButton.setOnClickListener { openLocationSettings() }
            }
            null -> binding.locationActionButton.setOnClickListener(null)
        }

        // Ask once automatically, right when the emergency is active: that is the moment of need, and the
        // reason is on screen. Later attempts only happen when the person taps "Allow location".
        if (ui.sharing.status == LocationSharingStatus.PERMISSION_NEEDED && !ui.permissionBlocked &&
            !ui.sharing.signedOut && viewModel.consumeAutoLocationRequest()
        ) {
            requestLocationPermission()
        }
        // Only while location is tracked (permission granted) and this screen is in front (Android 12+ rule).
        serviceStarter.startIfNeeded(ui.sharing.emergencyId, ui.sharing.isTracking, ui.backgroundTrackingActive)
    }

    private fun requestLocationPermission() {
        locationPermissionLauncher.launch(LocationPermissions.REQUESTED)
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", requireContext().packageName, null))
        startSafely(intent)
    }

    private fun openLocationSettings() {
        startSafely(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
    }

    private fun startSafely(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun showOnly(visiblePanel: View) {
        listOf(
            binding.loadingPanel, binding.idlePanel, binding.countdownPanel, binding.startingPanel,
            binding.activePanel, binding.endedPanel, binding.failedPanel
        ).forEach { it.isVisible = it === visiblePanel }
    }
}
