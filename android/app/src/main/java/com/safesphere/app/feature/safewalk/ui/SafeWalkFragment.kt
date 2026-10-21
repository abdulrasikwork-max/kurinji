package com.safesphere.app.feature.safewalk.ui

import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.View
import androidx.core.widget.doAfterTextChanged
import com.safesphere.app.core.network.isSessionExpired
import com.safesphere.app.feature.intelligence.ui.IntelligenceTexts
import com.safesphere.app.feature.intelligence.ui.WayCheckState
import com.safesphere.app.feature.intelligence.ui.WayCheckViewModel
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.safesphere.app.R
import com.safesphere.app.appContainer
import com.safesphere.app.core.location.LocationAccess
import com.safesphere.app.core.location.LocationPermissions
import com.safesphere.app.core.notifications.NotificationPermission
import com.safesphere.app.core.tracking.TrackingServiceStarter
import com.safesphere.app.core.ui.ViewBindingFragment
import com.safesphere.app.core.ui.collectWhenStarted
import com.safesphere.app.core.ui.showSessionExpiredDialog
import com.safesphere.app.core.ui.toMessage
import com.safesphere.app.databinding.FragmentSafeWalkBinding
import com.safesphere.app.feature.emergency.ui.EmergencyTimeFormatter
import android.widget.Toast
import com.safesphere.app.feature.circlealert.domain.AlertTarget
import com.safesphere.app.feature.circlealert.ui.openSmsComposer
import com.safesphere.app.feature.circlealert.ui.walkCircleLineFor
import com.safesphere.app.feature.safewalk.domain.CheckInPhase
import com.safesphere.app.feature.safewalk.domain.ConcernKind
import com.safesphere.app.feature.safewalk.domain.JourneyStatus
import com.safesphere.app.feature.safewalk.session.SafeWalkState
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Safe Walk: set up a walk with regular check-ins, then check in, finish, cancel or start SOS.
 * Location and notifications are asked when the person taps "Start" (the moment of need), never at launch.
 * SafeSphere does not contact anyone in this version; the screen says so.
 */
class SafeWalkFragment : ViewBindingFragment<FragmentSafeWalkBinding>(FragmentSafeWalkBinding::inflate) {

    private val viewModel by viewModels<SafeWalkViewModel> { SafeWalkViewModel.factory(appContainer) }

    private val serviceStarter = TrackingServiceStarter(this) { false } // notifications were asked before starting

    /** The walk the person asked to start (label, interval, movement checks), while the permission dialogs are open. */
    private var pendingStart: Triple<String?, Int, Boolean>? = null

    private val locationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { askNotificationsThenStart() }

    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { startPendingWalk() }

    /** Phase 8D: "Check the way" lives in its own ViewModel, so it can never touch the walk itself. */
    private val wayCheck by viewModels<WayCheckViewModel> { WayCheckViewModel.factory(appContainer) }

    /** Location asked for "Check the way" (moment of need); the check runs whatever the answer. */
    private val wayLocationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            wayCheck.onCheck(binding.destinationInput.text?.toString())
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        buildIntervalChips()

        binding.startWalkButton.setOnClickListener { onStartTapped() }
        binding.checkWayButton.setOnClickListener {
            if (hasLocationPermission()) wayCheck.onCheck(binding.destinationInput.text?.toString())
            else wayLocationLauncher.launch(LocationPermissions.REQUESTED)
        }
        binding.destinationInput.doAfterTextChanged { wayCheck.onDestinationChanged() }
        collectWhenStarted(wayCheck.state, ::renderWayCheck)
        binding.checkInButton.setOnClickListener { viewModel.onCheckInClicked() }
        binding.arrivedButton.setOnClickListener { confirm(R.string.safe_walk_confirm_arrived, R.string.action_arrived) { viewModel.onArrivedConfirmed() } }
        binding.cancelWalkButton.setOnClickListener { confirm(R.string.safe_walk_confirm_cancel, R.string.action_cancel_walk) { viewModel.onCancelConfirmed() } }
        // Opens the normal SOS screen (with its countdown). The server links the emergency to this walk.
        binding.walkSosButton.setOnClickListener { findNavController().navigate(R.id.emergencyFragment) }
        binding.walkLocationButton.setOnClickListener { locationLauncher.launch(LocationPermissions.REQUESTED) }
        binding.movementChecksButton.setOnClickListener { viewModel.onMovementChecksToggled() }
        binding.textCircleButton.setOnClickListener { chooseContactToText() }
        binding.walkDoneButton.setOnClickListener {
            viewModel.onDoneClicked()
            findNavController().navigateUp()
        }

        collectWhenStarted(viewModel.uiState, ::render)
        collectWhenStarted(viewModel.trackingServiceRunning) { render(viewModel.uiState.value) }
        collectWhenStarted(viewModel.circleAlertState) { render(viewModel.uiState.value) }
        collectWhenStarted(viewModel.sessionExpired) { expired ->
            if (expired) {
                viewModel.onSessionExpiredHandled()
                showSessionExpiredDialog()
            }
        }
        // Countdown ticks once a second while visible; the deadline itself comes from the shared holder.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    (viewModel.uiState.value as? SafeWalkUiState.Active)?.let {
                        renderCountdown(it)
                        renderMovement(it.walk) // "weak GPS" depends on time, not only on new state
                    }
                    delay(1_000)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.onScreenVisible()
    }

    override fun onStop() {
        super.onStop()
        serviceStarter.reset()
    }

    // ---- start flow: location -> notifications -> start --------------------------------------------

    private fun onStartTapped() {
        val label = binding.destinationInput.text?.toString()
        val interval = selectedInterval()
        pendingStart = Triple(label, interval, binding.movementChecksSwitch.isChecked)
        if (!hasLocationPermission() && viewModel.consumeLocationPrompt()) {
            locationLauncher.launch(LocationPermissions.REQUESTED) // the walk also works without it
        } else {
            askNotificationsThenStart()
        }
    }

    private fun askNotificationsThenStart() {
        viewModel.onScreenVisible() // a grant during an active walk restarts location updates
        if (pendingStart == null) return
        if (NotificationPermission.isRuntimePermission() && !NotificationPermission.isGranted(requireContext()) &&
            viewModel.consumeNotificationPrompt()
        ) {
            notificationLauncher.launch(NotificationPermission.PERMISSION)
        } else {
            startPendingWalk()
        }
    }

    private fun startPendingWalk() {
        val (label, interval, movementChecks) = pendingStart ?: return
        pendingStart = null
        viewModel.onStartClicked(label, interval, movementChecks)
    }

    private fun hasLocationPermission(): Boolean = LocationPermissions.REQUESTED.any {
        ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
    }

    // ---- rendering ------------------------------------------------------------------------------

    private fun render(state: SafeWalkUiState) {
        binding.loadingPanel.isVisible = state is SafeWalkUiState.Loading
        binding.setupPanel.isVisible = state is SafeWalkUiState.Setup
        binding.activePanel.isVisible = state is SafeWalkUiState.Active
        binding.endedPanel.isVisible = state is SafeWalkUiState.Ended
        when (state) {
            is SafeWalkUiState.Setup -> renderSetup(state)
            is SafeWalkUiState.Active -> renderActive(state)
            is SafeWalkUiState.Ended -> renderEnded(state.status)
            SafeWalkUiState.Loading -> Unit
        }
    }

    /** Phase 8D: the brief, or why there is none. Never blocks or delays "Start". */
    private fun renderWayCheck(state: WayCheckState) {
        binding.checkWayButton.isEnabled = state != WayCheckState.Checking
        binding.wayBlock.isVisible = state != WayCheckState.Idle
        binding.wayFooter.isVisible = state is WayCheckState.Ready
        when (state) {
            WayCheckState.Idle -> return
            is WayCheckState.Ready -> {
                val brief = state.brief
                val lines = IntelligenceTexts.wayLines(requireContext(), brief, state.destinationDescription)
                val text = SpannableStringBuilder(lines.first()).apply {
                    setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    lines.drop(1).forEach { append("\n").append(it) }
                }
                binding.wayNotice.setWarning(brief.hasConfirmedConcerns)
                binding.wayNotice.setMessage(text)
                binding.wayBlock.contentDescription = (lines + getString(R.string.way_footer)).joinToString(" ")
            }
            is WayCheckState.Failed -> {
                if (state.failure.isSessionExpired()) showSessionExpiredDialog()
                showWayMessage(state.failure.toMessage(requireContext()))
            }
            else -> showWayMessage(getString(IntelligenceTexts.wayMessage(state) ?: return))
        }
    }

    private fun showWayMessage(text: String) {
        binding.wayNotice.setWarning(false)
        binding.wayNotice.setMessage(text)
        binding.wayBlock.contentDescription = text
    }

    private fun renderSetup(state: SafeWalkUiState.Setup) {
        binding.startWalkButton.isEnabled = !state.isStarting
        binding.startProgress.isVisible = state.isStarting
        val failure = state.failure
        binding.setupErrorNotice.isVisible = failure != null
        if (failure != null) binding.setupErrorNotice.setMessage(failure.toMessage(requireContext()))
    }

    private fun renderActive(state: SafeWalkUiState.Active) {
        val walk = state.walk
        val journey = walk.journey ?: return
        binding.activeDestination.text = journey.destinationLabel?.let { getString(R.string.safe_walk_to, it) }
            ?: getString(R.string.safe_walk_every, journey.checkInIntervalMinutes)
        binding.activeDestination.contentDescription = binding.activeDestination.text

        val phase = walk.phase
        val concern = walk.checkIn?.concern
        binding.noResponseCard.isVisible = phase == CheckInPhase.NO_RESPONSE
        binding.noResponseTitle.setText(
            if (concern != null) R.string.safe_walk_no_response_concern_title else R.string.safe_walk_no_response_title
        )
        binding.phaseNotice.isVisible = phase == CheckInPhase.DUE_SOON || phase == CheckInPhase.MISSED
        if (phase == CheckInPhase.DUE_SOON) {
            binding.phaseNotice.setWarning(false)
            binding.phaseNotice.setMessage(getString(R.string.safe_walk_due_soon))
        } else if (phase == CheckInPhase.MISSED) {
            binding.phaseNotice.setWarning(true)
            binding.phaseNotice.setMessage(
                getString(
                    when (concern) {
                        ConcernKind.ROUTE_DEVIATION -> R.string.safe_walk_off_route
                        ConcernKind.UNEXPECTED_STOP -> R.string.safe_walk_stopped
                        null -> R.string.safe_walk_missed
                    }
                )
            )
        }
        binding.arrivalNotice.isVisible = walk.looksArrived
        renderCountdown(state)
        renderMovement(walk)
        renderCircle(journey.id, phase)

        val lines = buildList {
            val fix = walk.lastFix
            when {
                walk.access == LocationAccess.NONE -> Unit
                walk.lastLocationSavedAt != null ->
                    add(getString(R.string.safe_walk_location_saved, EmergencyTimeFormatter.formatTime(walk.lastLocationSavedAt)))
                fix == null -> add(getString(R.string.location_card_searching))
                else -> add(getString(R.string.location_not_saved_yet))
            }
            walkHintsFor(walk, viewModel.trackingServiceRunning.value, NotificationPermission.isGranted(requireContext()))
                .forEach { add(getString(it.text)) }
        }
        binding.walkStatusLines.text = lines.joinToString("\n")
        binding.walkStatusLines.isVisible = lines.isNotEmpty()
        binding.walkLocationButton.isVisible = walk.access == LocationAccess.NONE

        // Location updates need the foreground service to continue with the screen off.
        serviceStarter.startIfNeeded(journey.id, walk.isTracking, viewModel.trackingServiceRunning.value)
    }

    private fun renderCountdown(state: SafeWalkUiState.Active) {
        val dueAt = state.walk.localDueAt ?: return
        val seconds = Duration.between(Instant.now(), dueAt).seconds
        binding.countdownText.text = formatCountdown(seconds)
        binding.countdownLabel.setText(if (seconds >= 0) R.string.safe_walk_until_check_in else R.string.safe_walk_overdue)
        binding.countdownText.contentDescription = getString(
            if (seconds >= 0) R.string.cd_safe_walk_countdown else R.string.cd_safe_walk_overdue,
            kotlin.math.abs(seconds) / 60, kotlin.math.abs(seconds) % 60
        )
    }

    /** Phase 4C: stop / route checks and battery, in plain words (mapping is pure: [movementLinesFor]). */
    private fun renderMovement(walk: SafeWalkState) {
        val lines = movementLinesFor(walk, viewModel.gpsQuality(walk, Instant.now())).map { line ->
            when (line) {
                MovementLine.Off -> getString(R.string.safe_walk_movement_off)
                MovementLine.NeedsLocation -> getString(R.string.safe_walk_movement_needs_location)
                MovementLine.WaitingForGps -> getString(R.string.safe_walk_movement_waiting_gps)
                MovementLine.WeakGps -> getString(R.string.safe_walk_movement_weak_gps)
                is MovementLine.Destination -> getString(R.string.safe_walk_movement_destination, line.description)
                is MovementLine.RouteOn -> line.distance?.let { getString(R.string.safe_walk_movement_route_on_distance, distance(it)) }
                    ?: getString(R.string.safe_walk_movement_route_on)
                MovementLine.StopsOnly -> getString(R.string.safe_walk_movement_stops_only)
                MovementLine.DestinationNotFound -> getString(R.string.safe_walk_movement_not_found)
                MovementLine.LookupUnavailable -> getString(R.string.safe_walk_movement_lookup_unavailable)
                MovementLine.RouteResumesOnline -> getString(R.string.safe_walk_movement_resumes_online)
                is MovementLine.BatteryLow -> getString(R.string.safe_walk_battery_low, percent(line.percent))
                is MovementLine.BatteryCritical -> getString(R.string.safe_walk_battery_critical, percent(line.percent))
            }
        }
        val text = lines.joinToString("\n")
        if (binding.movementLines.text.toString() != text) binding.movementLines.text = text
        binding.movementLines.isVisible = lines.isNotEmpty()
        binding.movementChecksButton.setText(
            if (walk.movementChecks) R.string.action_movement_checks_off else R.string.action_movement_checks_on
        )
    }

    /** Phase 4D: what the Safety Circle was (or will be) told about this walk, and the "Text" fallback. */
    private fun renderCircle(journeyId: Long, phase: CheckInPhase?) {
        val alerts = viewModel.circleAlertState.value
        val alerting = phase == CheckInPhase.MISSED || phase == CheckInPhase.NO_RESPONSE
        binding.noResponseBody.setText(
            if (alerts.autoActive) R.string.safe_walk_no_response_body_auto else R.string.safe_walk_no_response_body
        )
        val line = walkCircleLineFor(alerts, AlertTarget.Walk(journeyId), alerting)
        binding.walkCircleStatus.isVisible = line != null
        if (line != null) {
            binding.walkCircleStatus.text = when (line.text) {
                R.string.circle_walk_sent, R.string.circle_walk_failed, R.string.circle_walk_delivered ->
                    getString(line.text, line.count)
                else -> getString(line.text)
            }
        }
        binding.textCircleButton.isVisible = alerting && alerts.recipients.isNotEmpty()
    }

    /** The person picks a contact; the SMS app opens with a ready message. The person decides to send it. */
    private fun chooseContactToText() {
        val recipients = viewModel.circleAlertState.value.recipients
        if (recipients.isEmpty()) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.text_my_circle_title)
            .setItems(recipients.map { it.name }.toTypedArray()) { _, which ->
                val recipient = recipients[which]
                val text = viewModel.composerText() ?: return@setItems
                if (openSmsComposer(requireContext(), recipient.phoneNumber, text)) {
                    viewModel.onComposerOpened(recipient)
                } else {
                    Toast.makeText(requireContext(), R.string.sms_app_unavailable, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun distance(text: DistanceText): String =
        if (text.kilometres) getString(R.string.distance_km, text.value) else getString(R.string.distance_m, text.value.toInt())

    private fun percent(value: Int?): String = value?.let { getString(R.string.battery_percent, it) } ?: "?"

    private fun renderEnded(status: JourneyStatus) {
        val (title, message) = when (status) {
            JourneyStatus.COMPLETED -> R.string.safe_walk_ended_completed_title to R.string.safe_walk_ended_completed_message
            JourneyStatus.EMERGENCY -> R.string.safe_walk_ended_emergency_title to R.string.safe_walk_ended_emergency_message
            else -> R.string.safe_walk_ended_cancelled_title to R.string.safe_walk_ended_cancelled_message
        }
        binding.endedTitle.setText(title)
        binding.endedMessage.setText(message)
    }

    // ---- helpers --------------------------------------------------------------------------------

    private fun buildIntervalChips() {
        if (binding.intervalChips.childCount > 0) return
        SafeWalkViewModel.INTERVAL_OPTIONS_MINUTES.forEach { minutes ->
            val chip = Chip(requireContext(), null, com.google.android.material.R.attr.chipStyle).apply {
                id = View.generateViewId()
                tag = minutes
                text = getString(R.string.safe_walk_interval_option, minutes)
                isCheckable = true
                isChecked = minutes == SafeWalkViewModel.DEFAULT_INTERVAL_MINUTES
            }
            binding.intervalChips.addView(chip)
        }
    }

    private fun selectedInterval(): Int {
        val chip = binding.intervalChips.findViewById<Chip>(binding.intervalChips.checkedChipId)
        return chip?.tag as? Int ?: SafeWalkViewModel.DEFAULT_INTERVAL_MINUTES
    }

    private fun confirm(message: Int, positive: Int, onConfirmed: () -> Unit) {
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(message)
            .setPositiveButton(positive) { _, _ -> onConfirmed() }
            .setNegativeButton(R.string.action_keep_walking, null)
            .show()
    }
}
