package com.safesphere.app.feature.rise.ui

import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.safesphere.app.R
import com.safesphere.app.databinding.FragmentRiseGoalBinding
import com.safesphere.app.feature.rise.domain.Outcome
import com.safesphere.app.feature.rise.domain.ProofPolicy
import com.safesphere.app.feature.rise.domain.RiseJournal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** One goal: its steps in the person's own order, each with "Prepare and try" or "How did it go?". */
class RiseGoalFragment : RiseScreenFragment<FragmentRiseGoalBinding>(FragmentRiseGoalBinding::inflate) {

    private val goalId: Long get() = requireArguments().getLong(ARG_GOAL_ID)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.deleteGoalButton.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.rise_goal_delete)
                .setMessage(R.string.rise_goal_delete_confirm)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.rise_delete) { _, _ ->
                    viewModel.deleteGoal(goalId)
                    findNavController().navigateUp()
                }
                .show()
        }
        collectReady(::render)
    }

    private fun render(journal: RiseJournal) {
        val goal = journal.goal(goalId) ?: return backToRise()
        // On the goal's own screen the person's words are shown (quiet mode hides them in lists only).
        binding.goalTitle.text = RiseTexts.goalTitle(resources, goal, ProofPolicy.numbersOf(journal).getValue(goal.id), quiet = false)
        binding.goalDomain.text = getString(RiseTexts.domain(goal.domain))
        val summary = RiseTexts.summary(resources, ProofPolicy.summary(goal))
        binding.goalSummary.isVisible = summary != null
        binding.goalSummary.text = summary

        binding.stepsContainer.removeAllViews()
        goal.steps.forEach { step ->
            val history = step.attempts.filter { !it.open }.joinToString("\n") { attempt ->
                val outcome = attempt.outcome!!
                if (outcome == Outcome.NOT_OK) {
                    getString(R.string.rise_step_history, day(attempt.endedOn ?: attempt.startedOn), getString(R.string.rise_history_not_ok))
                } else {
                    getString(R.string.rise_step_history, day(attempt.endedOn ?: attempt.startedOn), getString(RiseTexts.outcome(outcome)))
                }
            }.ifEmpty { null }
            val trying = step.openAttempt != null
            binding.stepsContainer.addCard(
                title = step.words,
                subtitle = if (trying) getString(R.string.rise_step_trying) else null,
                body = history,
                action = getString(if (trying) R.string.rise_step_how else R.string.rise_step_prepare),
                onAction = {
                    findNavController().navigate(R.id.riseStepFragment,
                        bundleOf(RiseStepFragment.ARG_GOAL_ID to goal.id, RiseStepFragment.ARG_STEP_ID to step.id))
                }
            )
        }

        binding.asideButton.setText(if (goal.aside) R.string.rise_goal_bring_back else R.string.rise_goal_set_aside)
        binding.asideButton.setOnClickListener { viewModel.setAside(goal.id, !goal.aside) }
    }

    private fun day(date: LocalDate): String = date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

    companion object {
        const val ARG_GOAL_ID = "goalId"
    }
}
