package com.safesphere.app.feature.rise.ui

import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.safesphere.app.R
import com.safesphere.app.core.ui.ScreenLockGate
import com.safesphere.app.core.ui.collectWhenStarted
import com.safesphere.app.databinding.FragmentRiseBinding
import com.safesphere.app.feature.rise.domain.GoalPolicy
import com.safesphere.app.feature.rise.domain.ProofPolicy
import com.safesphere.app.feature.rise.domain.RiseJournal

/**
 * The Rise tab (Phase 10B). Before anything personal is drawn: the intro on first use, then (if the person chose it)
 * the phone's screen lock. Goals in use, goals put aside, "Your proof", and Rise's own settings.
 */
class RiseFragment : RiseScreenFragment<FragmentRiseBinding>(FragmentRiseBinding::inflate) {

    private val screenLock = ScreenLockGate(this, R.string.rise_unlock_title, R.string.rise_unlock_subtitle) {
        viewModel.onScreenLockResult(it)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.introStartButton.setOnClickListener { viewModel.finishIntro(binding.introLockSwitch.isChecked) }
        binding.unlockButton.setOnClickListener { screenLock.ask() }
        binding.startFreshButton.setOnClickListener { confirmDeleteAll() }
        binding.newGoalButton.setOnClickListener { findNavController().navigate(R.id.riseGoalEditorFragment) }
        binding.proofButton.setOnClickListener { findNavController().navigate(R.id.riseProofFragment) }
        binding.deleteAllButton.setOnClickListener { confirmDeleteAll() }

        collectWhenStarted(viewModel.screen) { screen ->
            binding.riseProgress.isVisible = screen == RiseScreen.Loading
            binding.introPanel.isVisible = screen == RiseScreen.Intro
            binding.lockedPanel.isVisible = screen == RiseScreen.Locked
            binding.unreadablePanel.isVisible = screen == RiseScreen.Unreadable
            binding.readyPanel.isVisible = screen is RiseScreen.Ready
            if (screen is RiseScreen.Ready) render(screen.journal)
        }
        collectWhenStarted(viewModel.events) { event ->
            when (event) {
                RiseEvent.NoScreenLock -> toast(R.string.rise_no_screen_lock)
                is RiseEvent.Refused -> toast(messageFor(event.reason))
                is RiseEvent.GoalSaved -> Unit
            }
        }
    }

    override fun onStop() {
        screenLock.cancel()
        super.onStop()
    }

    private fun render(journal: RiseJournal) {
        binding.emptyText.isVisible = journal.goals.isEmpty()
        binding.proofButton.isVisible = journal.goals.isNotEmpty()

        val numbers = ProofPolicy.numbersOf(journal)
        val ordered = GoalPolicy.ordered(journal)
        binding.goalsContainer.removeAllViews()
        binding.asideContainer.removeAllViews()
        ordered.forEach { goal ->
            val done = goal.steps.sumOf { it.timesDone }
            val progress = if (done == 0) getString(R.string.rise_goal_not_done_yet)
            else resources.getQuantityString(R.plurals.rise_goal_done_count, done, done)
            val container = if (goal.aside) binding.asideContainer else binding.goalsContainer
            container.addCard(
                title = RiseTexts.goalTitle(resources, goal, numbers.getValue(goal.id), journal.quietMode),
                subtitle = if (journal.quietMode) null else getString(RiseTexts.domain(goal.domain)),
                body = progress,
                onClick = { findNavController().navigate(R.id.riseGoalFragment, bundleOf(RiseGoalFragment.ARG_GOAL_ID to goal.id)) }
            )
        }
        binding.goalsHeader.isVisible = ordered.any { !it.aside }
        binding.asideHeader.isVisible = ordered.any { it.aside }

        // Switches: set without firing their listeners.
        binding.quietSwitch.setOnCheckedChangeListener(null)
        binding.quietSwitch.isChecked = journal.quietMode
        binding.quietSwitch.setOnCheckedChangeListener { _, checked -> viewModel.setQuietMode(checked) }
        binding.lockSwitch.setOnCheckedChangeListener(null)
        binding.lockSwitch.isChecked = journal.lockWithScreenLock
        binding.lockSwitch.setOnCheckedChangeListener { _, checked -> viewModel.setLockWithScreenLock(checked) }
    }

    private fun confirmDeleteAll() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.rise_delete_all_confirm_title)
            .setMessage(R.string.rise_delete_all_confirm)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.rise_delete) { _, _ -> viewModel.deleteAll() }
            .show()
    }
}
