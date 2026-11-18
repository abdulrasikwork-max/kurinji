package com.safesphere.app.feature.safewalk.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
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
import com.safesphere.app.core.notifications.NotificationPermission
import com.safesphere.app.core.ui.ViewBindingFragment
import com.safesphere.app.core.ui.collectWhenStarted
import com.safesphere.app.core.ui.showSessionExpiredDialog
import com.safesphere.app.core.ui.toMessage
import com.safesphere.app.databinding.FragmentSafeWalkBinding
import com.safesphere.app.feature.emergency.ui.EmergencyTimeFormatter
import com.safesphere.app.feature.safewalk.domain.CheckInPhase
import com.safesphere.app.feature.safewalk.domain.CheckInPolicy
import com.safesphere.app.feature.safewalk.domain.JourneyStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * "On my way" (N6, D22; code name Safe Walk): "remind me in 10 / 20 / 30 / 45 / 60 minutes" and an optional label.
 * At the time the phone asks "There yet?" [I'm there] [More time]; 5 minutes later one more nudge with SOS / Call 112;
 * then nothing. No location, no tracking, no foreground service, nobody is told anything by IRL. "Let someone know"
 * opens the share sheet with a plain note (the label and the time; no location, no link).
 */
class SafeWalkFragment : ViewBindingFragment<FragmentSafeWalkBinding>(FragmentSafeWalkBinding::inflate) {

    private val viewModel by viewModels<SafeWalkViewModel> { SafeWalkViewModel.factory(appContainer) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Filled in by a plan ("On my way", D24) or "Going there?"; the person still presses Start and can change it.
        val prefill = arguments?.getString(ARG_DESTINATION_LABEL)
        if (savedInstanceState == null && !prefill.isNullOrBlank()) binding.labelInput.setText(prefill.take(100))

        CheckInPolicy.CHOICES.forEach { minutes ->
            val chip = Chip(requireContext()).apply {
                id = View.generateViewId()
                tag = minutes
                text = getString(R.string.omw_minutes, minutes)
                isCheckable = true
                isChecked = minutes == SafeWalkViewModel.DEFAULT_MINUTES
            }
            binding.minutesGroup.addView(chip)
        }

        binding.startButton.setOnClickListener {
            val chip = binding.minutesGroup.findViewById<Chip>(binding.minutesGroup.checkedChipId)
            val minutes = chip?.tag as? Int ?: SafeWalkViewModel.DEFAULT_MINUTES
            viewModel.onStartClicked(binding.labelInput.text?.toString(), minutes)
        }
        binding.thereButton.setOnClickListener { viewModel.onThereClicked() }
        binding.moreTimeButton.setOnClickListener { viewModel.onMoreTimeClicked() }
        binding.tellButton.setOnClickListener { shareNote(arrived = false) }
        binding.endedTellButton.setOnClickListener { shareNote(arrived = true) }
        binding.cancelButton.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.omw_cancel_title)
                .setNegativeButton(R.string.omw_keep, null)
                .setPositiveButton(R.string.omw_cancel) { _, _ -> viewModel.onCancelConfirmed() }
                .show()
        }
        binding.doneButton.setOnClickListener {
            viewModel.onDoneClicked()
            findNavController().navigateUp()
        }

        collectWhenStarted(viewModel.uiState, ::render)
        collectWhenStarted(viewModel.sessionExpired) { expired ->
            if (expired) {
                viewModel.onSessionExpiredHandled()
                showSessionExpiredDialog()
            }
        }
        // The reminder line counts down while the screen is open (the reminders themselves come from the alarm).
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    render(viewModel.uiState.value)
                    delay(REFRESH_MILLIS)
                }
            }
        }
    }

    private fun render(state: SafeWalkUiState) {
        binding.loadingPanel.isVisible = state == SafeWalkUiState.Loading
        binding.setupPanel.isVisible = state is SafeWalkUiState.Setup
        binding.activePanel.isVisible = state is SafeWalkUiState.Active
        binding.endedPanel.isVisible = state is SafeWalkUiState.Ended
        when (state) {
            is SafeWalkUiState.Setup -> {
                binding.startButton.isEnabled = !state.isStarting
                val failure = state.failure
                binding.setupError.isVisible = failure != null
                if (failure != null) binding.setupError.setMessage(failure.toMessage(requireContext()))
            }
            is SafeWalkUiState.Active -> renderActive(state)
            is SafeWalkUiState.Ended -> binding.endedTitle.setText(when (state.status) {
                JourneyStatus.COMPLETED -> R.string.omw_ended_there
                JourneyStatus.EMERGENCY -> R.string.omw_ended_sos
                else -> R.string.omw_ended_cancelled
            })
            SafeWalkUiState.Loading -> Unit
        }
        binding.endedTellButton.isVisible = state is SafeWalkUiState.Ended && state.status == JourneyStatus.COMPLETED
    }

    private fun renderActive(state: SafeWalkUiState.Active) {
        val walk = state.walk
        val label = walk.journey?.destinationLabel
        binding.activeTitle.text = if (label != null) getString(R.string.omw_active_to, label) else getString(R.string.omw_active_title)
        val due = walk.localDueAt
        binding.reminderLine.text = when {
            due == null -> ""
            walk.phase == CheckInPhase.MISSED || walk.phase == CheckInPhase.NO_RESPONSE -> getString(R.string.omw_there_yet)
            else -> getString(R.string.omw_reminder_at, EmergencyTimeFormatter.formatTime(due))
        }
        binding.syncLine.isVisible = walk.pendingSync > 0 || walk.restoredOffline
        binding.syncLine.setText(R.string.omw_not_synced)
        binding.notificationsNotice.isVisible = !NotificationPermission.isGranted(requireContext())
    }

    /**
     * "Let someone know": the person picks the app and the people. The note holds only the label they typed and the
     * time; never a location, a link or anyone else's name (D22, D24).
     */
    private fun shareNote(arrived: Boolean) {
        val walk = appContainer.safeWalk.state.value
        val label = if (arrived) viewModel.lastLabel else walk.journey?.destinationLabel
        val note = when {
            arrived && label != null -> getString(R.string.omw_note_there_to, label)
            arrived -> getString(R.string.omw_note_there)
            else -> {
                val due = walk.localDueAt?.let { EmergencyTimeFormatter.formatTime(it) }
                if (label != null) getString(R.string.omw_note_going_to, label, due ?: "")
                else getString(R.string.omw_note_going, due ?: "")
            }
        }
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, note)
        startActivity(Intent.createChooser(send, getString(R.string.omw_let_someone_know)))
    }

    companion object {
        const val ARG_DESTINATION_LABEL = "destinationLabel"
        private const val REFRESH_MILLIS = 15_000L
    }
}
