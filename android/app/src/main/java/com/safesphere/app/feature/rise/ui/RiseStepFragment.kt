package com.safesphere.app.feature.rise.ui

import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import com.safesphere.app.R
import com.safesphere.app.databinding.FragmentRiseStepBinding
import com.safesphere.app.feature.rise.domain.Expectation
import com.safesphere.app.feature.rise.domain.Goal
import com.safesphere.app.feature.rise.domain.GoalPolicy
import com.safesphere.app.feature.rise.domain.Outcome
import com.safesphere.app.feature.rise.domain.Preparation
import com.safesphere.app.feature.rise.domain.RiseDomain
import com.safesphere.app.feature.rise.domain.RiseJournal
import com.safesphere.app.feature.rise.domain.SelfDoubt
import com.safesphere.app.feature.rise.domain.Step
import com.safesphere.app.feature.rise.domain.Support

/**
 * One step of Doubt -> Prepare -> Try -> Proof -> Next.
 * - Prepare: expectation, self-doubts (with the person's own replay), the safety worry (respected: it opens SAFE
 *   preparation, never a replay), unranked supports. Nothing is required.
 * - Trying: Rise does nothing (no timer, no reminder). "How did it go?" whenever the person comes back.
 * - Saved: the proof card and four equal next choices; "Something wasn't OK" gets "That wasn't your fault" and real help.
 * The SAFE bridge is navigation only: Rise reads nothing from SAFE, and SAFE learns nothing from Rise.
 */
class RiseStepFragment : RiseScreenFragment<FragmentRiseStepBinding>(FragmentRiseStepBinding::inflate) {

    private val goalId: Long get() = requireArguments().getLong(ARG_GOAL_ID)
    private val stepId: Long get() = requireArguments().getLong(ARG_STEP_ID)

    private var chipsDrawnFor: String? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        chipsDrawnFor = null
        binding.tryButton.setOnClickListener { viewModel.startTry(goalId, stepId, viewModel.draft) }
        binding.notNowButton.setOnClickListener { findNavController().navigateUp() }
        binding.tellBeforeButton.setOnClickListener { tellSomeone(R.string.rise_sms_before) }
        binding.tellAfterButton.setOnClickListener { tellSomeone(R.string.rise_sms_after) }
        binding.planWayButton.setOnClickListener { findNavController().navigate(R.id.safeWalkFragment) }
        binding.sosSetupButton.setOnClickListener { findNavController().navigate(R.id.safetyCircleFragment) }
        binding.concernChip.setOnCheckedChangeListener { _, checked ->
            viewModel.draft = viewModel.draft.copy(safetyConcern = checked)
            renderSafePanel()
        }
        binding.saveOutcomeButton.setOnClickListener {
            val outcome = viewModel.draftOutcome ?: return@setOnClickListener
            viewModel.savedOutcome = outcome
            viewModel.recordOutcome(goalId, stepId, outcome, viewModel.draftFirst)
        }
        binding.againButton.setOnClickListener { resetDraft() ; redraw() }
        binding.restButton.setOnClickListener { backToRise() }
        binding.somethingNewButton.setOnClickListener {
            findNavController().navigate(R.id.riseGoalEditorFragment, null,
                NavOptions.Builder().setPopUpTo(R.id.riseFragment, false).build())
        }
        binding.notOkSosButton.setOnClickListener { findNavController().navigate(R.id.emergencyFragment) }
        binding.notOkTellButton.setOnClickListener { tellSomeone(null) }
        binding.notOkReportButton.setOnClickListener { findNavController().navigate(R.id.communityFragment) }
        binding.notOkAsideButton.setOnClickListener {
            viewModel.setAside(goalId, true)
            backToRise()
        }
        binding.notOkCloseButton.setOnClickListener { backToRise() }
        collectReady(::render)
    }

    private var lastJournal: RiseJournal? = null

    private fun redraw() = lastJournal?.let(::render)

    private fun render(journal: RiseJournal) {
        lastJournal = journal
        val goal = journal.goal(goalId) ?: return backToRise()
        val step = goal.steps.firstOrNull { it.id == stepId } ?: return backToRise()
        binding.stepTitle.text = step.words

        val saved = viewModel.savedOutcome
        val trying = step.openAttempt != null
        val mode = when {
            saved == Outcome.NOT_OK && !trying -> Mode.NOT_OK
            saved != null && !trying -> Mode.AFTER
            trying -> Mode.TRYING
            else -> Mode.PREPARE
        }
        binding.preparePanel.isVisible = mode == Mode.PREPARE
        binding.tryingPanel.isVisible = mode == Mode.TRYING
        binding.afterPanel.isVisible = mode == Mode.AFTER
        binding.notOkPanel.isVisible = mode == Mode.NOT_OK

        // Chips are drawn once per mode, so a save (which redraws the screen) never clears what the person picked.
        val key = "$mode:${step.attempts.size}"
        if (chipsDrawnFor != key) {
            chipsDrawnFor = key
            when (mode) {
                Mode.PREPARE -> drawPrepare(goal)
                Mode.TRYING -> drawTrying()
                else -> Unit
            }
        }
        if (mode == Mode.AFTER) drawAfter(goal, step, saved!!)
    }

    private fun drawPrepare(goal: Goal) {
        val draft = viewModel.draft
        binding.expectChips.fill(Expectation.entries, RiseTexts::expectation, setOfNotNull(draft.expectation)) { item, checked ->
            viewModel.draft = viewModel.draft.copy(expectation = if (checked) item else viewModel.draft.expectation.takeIf { it != item })
        }
        binding.doubtChips.fill(SelfDoubt.entries, RiseTexts::doubt, draft.doubts) { item, checked ->
            val doubts = if (checked) viewModel.draft.doubts + item else viewModel.draft.doubts - item
            viewModel.draft = viewModel.draft.copy(doubts = doubts)
            renderReplay()
        }
        binding.supportChips.fill(Support.entries, RiseTexts::support, draft.supports) { item, checked ->
            val supports = if (checked) viewModel.draft.supports + item else viewModel.draft.supports - item
            viewModel.draft = viewModel.draft.copy(supports = supports)
        }
        binding.concernChip.isChecked = draft.safetyConcern
        this.goalDomain = goal.domain
        renderSafePanel()
        renderReplay()
    }

    private var goalDomain: RiseDomain? = null

    /** SAFE preparation is offered for going somewhere, and for ANY step once the safety worry is picked. */
    private fun renderSafePanel() {
        val concern = viewModel.draft.safetyConcern
        binding.safePanel.isVisible = concern || goalDomain == RiseDomain.GO
        binding.concernNote.isVisible = concern
    }

    private fun renderReplay() {
        val replay = viewModel.replayFor(viewModel.draft.doubts)
        binding.replayNotice.isVisible = replay != null
        if (replay != null) binding.replayNotice.setMessage(RiseTexts.replay(resources, replay))
    }

    private fun drawTrying() {
        binding.outcomeChips.fill(Outcome.entries, RiseTexts::outcome, setOfNotNull(viewModel.draftOutcome)) { item, checked ->
            if (checked) viewModel.draftOutcome = item else if (viewModel.draftOutcome == item) viewModel.draftOutcome = null
            renderOutcomeChoice()
        }
        binding.firstCheck.setOnCheckedChangeListener(null)
        binding.firstCheck.isChecked = viewModel.draftFirst
        binding.firstCheck.setOnCheckedChangeListener { _, checked -> viewModel.draftFirst = checked }
        renderOutcomeChoice()
    }

    /** "A first" only makes sense for a step that was done. */
    private fun renderOutcomeChoice() {
        val outcome = viewModel.draftOutcome
        binding.saveOutcomeButton.isEnabled = outcome != null
        binding.firstCheck.isVisible = outcome?.didIt == true
    }

    private fun drawAfter(goal: Goal, step: Step, saved: Outcome) {
        binding.afterText.setText(RiseTexts.afterOutcome(saved))
        val next = GoalPolicy.nextStep(goal, step.id)
        binding.nextStepButton.isVisible = next != null
        binding.nextStepButton.setOnClickListener {
            if (next == null) return@setOnClickListener
            findNavController().navigate(R.id.riseStepFragment, bundleOf(ARG_GOAL_ID to goal.id, ARG_STEP_ID to next.id),
                NavOptions.Builder().setPopUpTo(R.id.riseStepFragment, true).build())
        }
        binding.tellAfterButton.isVisible = saved.didIt
    }

    private fun resetDraft() {
        viewModel.draft = Preparation()
        viewModel.draftOutcome = null
        viewModel.draftFirst = false
        viewModel.savedOutcome = null
        chipsDrawnFor = null
    }

    private enum class Mode { PREPARE, TRYING, AFTER, NOT_OK }

    companion object {
        const val ARG_GOAL_ID = "goalId"
        const val ARG_STEP_ID = "stepId"
    }
}
