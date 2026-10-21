package com.safesphere.app.feature.moderation.ui

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.safesphere.app.R
import com.safesphere.app.appContainer
import com.safesphere.app.core.ui.ViewBindingFragment
import com.safesphere.app.core.ui.collectWhenStarted
import com.safesphere.app.core.ui.showSessionExpiredDialog
import com.safesphere.app.core.ui.toMessage
import com.safesphere.app.databinding.FragmentModerationCaseBinding
import com.safesphere.app.feature.community.domain.ModerationReason
import com.safesphere.app.feature.community.ui.CommunityTexts
import com.safesphere.app.feature.moderation.domain.CaseAction
import com.safesphere.app.feature.moderation.domain.CaseDetail
import com.safesphere.app.feature.moderation.domain.CaseOrigin
import com.safesphere.app.feature.moderation.domain.CaseOutcome
import com.safesphere.app.feature.moderation.domain.CaseParticipant
import com.safesphere.app.feature.moderation.domain.CaseStatus
import com.safesphere.app.feature.moderation.domain.ModerationPolicy
import com.safesphere.app.feature.moderation.domain.SignalModeration

/**
 * One moderation case (Phase 7): the place (block cell, no coordinates), the categories of flags, the confidence and
 * its reasons, and the people involved by case pseudonym only. Every decision needs a reason code and a confirmation.
 * FLAG_SECURE while open.
 */
class ModerationCaseFragment : ViewBindingFragment<FragmentModerationCaseBinding>(FragmentModerationCaseBinding::inflate) {

    private val caseId: String by lazy { requireArguments().getString(ARG_CASE_ID).orEmpty() }

    private val viewModel by viewModels<ModerationCaseViewModel> {
        ModerationCaseViewModel.factory(appContainer, caseId)
    }

    private val participantAdapter = ParticipantAdapter(::chooseRestriction)
    private val groupMemberAdapter = ParticipantAdapter(::chooseRestriction)

    /** The shown case's origin: keeping a COORDINATION case's signal has its own confirmation (Phase 9C.1). */
    private var currentOrigin: CaseOrigin? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.participantList.adapter = participantAdapter
        binding.groupMemberList.adapter = groupMemberAdapter
        binding.quarantineButton.setOnClickListener { chooseReason(CaseAction.QUARANTINE) }
        binding.dismissButton.setOnClickListener { chooseReason(CaseAction.DISMISS) }
        binding.removeButton.setOnClickListener { chooseReason(CaseAction.REMOVE) }
        binding.pauseGroupButton.setOnClickListener { chooseGroupRestriction() }
        collectWhenStarted(viewModel.ui, ::render)
        if (savedInstanceState == null) viewModel.load()
    }

    override fun onResume() {
        super.onResume()
        requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun onPause() {
        requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        super.onPause()
    }

    private fun render(ui: CaseUiState) {
        val state = ui.state
        binding.progress.isVisible = state is CaseState.Loading || ui.busy
        binding.content.isVisible = state is CaseState.Ready
        binding.statusNotice.isVisible = false
        when (state) {
            is CaseState.NotAllowed -> showNotice(state.message ?: getString(R.string.moderation_not_allowed))
            is CaseState.Failed -> showNotice(state.failure.toMessage(requireContext()))
            is CaseState.Ready -> renderCase(state.detail, state.actions)
            CaseState.Loading -> Unit
        }
        val enabled = !ui.busy
        binding.quarantineButton.isEnabled = enabled
        binding.dismissButton.isEnabled = enabled
        binding.removeButton.isEnabled = enabled
        binding.pauseGroupButton.isEnabled = enabled
        ui.event?.let {
            val text = when (it) {
                is CaseEvent.Decided -> getString(R.string.moderation_decided)
                is CaseEvent.Failed -> it.failure.toMessage(requireContext())
            }
            Toast.makeText(requireContext(), text, Toast.LENGTH_LONG).show()
            viewModel.onEventShown()
        }
        if (ui.sessionExpired) {
            viewModel.onSessionExpiredHandled()
            showSessionExpiredDialog()
        }
    }

    private fun renderCase(detail: CaseDetail, actions: List<CaseAction>) {
        val summary = detail.summary
        binding.caseTitle.setText(CommunityTexts.category(summary.category))
        binding.caseOrigin.setText(ModerationTexts.origin(summary.origin))
        val facts = mutableListOf(
            getString(R.string.case_place, detail.cell, detail.areaCell),
            getString(
                when {
                    summary.moderation == SignalModeration.QUARANTINED -> R.string.case_hidden
                    summary.moderation == SignalModeration.REMOVED -> R.string.case_removed
                    detail.publiclyVisible -> R.string.case_visible
                    else -> R.string.my_status_not_shown
                }
            ),
            getString(CommunityTexts.confidence(summary.confidence)),
            getString(R.string.case_counts, summary.independentSupporters, detail.independentDisputers)
        )
        if (summary.flags.isNotEmpty()) facts += getString(R.string.case_flag_counts, ModerationTexts.flagCounts(requireContext(), summary))
        if (detail.reasons.isNotEmpty()) {
            facts += getString(R.string.moderation_signal_reasons,
                detail.reasons.joinToString(" ") { getString(CommunityTexts.reason(it)) })
        }
        ModerationTexts.coordinationLines(detail).forEach { facts += getString(it) }
        binding.caseFacts.text = facts.joinToString("\n")

        participantAdapter.canRestrict = { ModerationPolicy.canRestrict(detail, it) }
        participantAdapter.submitList(detail.participants)
        participantAdapter.notifyItemRangeChanged(0, detail.participants.size)

        // Phase 9C: the rest of a coordination group, and one decision that pauses all of it.
        binding.groupMembersHeader.isVisible = detail.groupMembers.isNotEmpty()
        binding.groupMemberList.isVisible = detail.groupMembers.isNotEmpty()
        groupMemberAdapter.canRestrict = { ModerationPolicy.canRestrict(detail, it) }
        groupMemberAdapter.submitList(detail.groupMembers)
        groupMemberAdapter.notifyItemRangeChanged(0, detail.groupMembers.size)
        binding.pauseGroupButton.isVisible = ModerationPolicy.canRestrictGroup(detail)
        currentOrigin = summary.origin

        binding.actionButtons.isVisible = actions.isNotEmpty()
        binding.quarantineButton.isVisible = CaseAction.QUARANTINE in actions
        binding.dismissButton.isVisible = CaseAction.DISMISS in actions
        binding.removeButton.isVisible = CaseAction.REMOVE in actions
        when {
            detail.youTookPart || summary.youTookPart -> showNotice(getString(R.string.case_you_took_part))
            summary.status == CaseStatus.CLOSED -> showNotice(getString(R.string.case_closed, getString(
                if (summary.outcome == CaseOutcome.REMOVED) R.string.case_outcome_removed else R.string.case_outcome_dismissed)))
        }
    }

    private fun showNotice(text: String) {
        binding.statusNotice.isVisible = true
        binding.statusNotice.setMessage(text)
    }

    private fun chooseReason(action: CaseAction, onChosen: ((ModerationReason) -> Unit)? = null) {
        val reasons = ModerationPolicy.reasonsFor(action)
        val message = getString(ModerationTexts.confirmation(action, currentOrigin))
        if (reasons.size == 1 && onChosen == null) {
            confirm(action, message) { viewModel.onDecide(action, reasons[0]) }
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.moderation_choose_reason)
            .setItems(reasons.map { getString(ModerationTexts.reason(it)) }.toTypedArray()) { _, which ->
                val reason = reasons[which]
                if (onChosen != null) onChosen(reason)
                else confirm(action, message) { viewModel.onDecide(action, reason) }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /** Pause one participant: reason, then length, then confirmation. */
    private fun chooseRestriction(participant: CaseParticipant) {
        chooseRestrictionLength(R.string.moderation_confirm_restrict) { reason, days ->
            viewModel.onRestrict(participant, reason, days)
        }
    }

    /** Phase 9C: pause the whole coordination group in one decision: reason, then length, then confirmation. */
    private fun chooseGroupRestriction() {
        chooseRestrictionLength(R.string.coordination_confirm_pause_group) { reason, days ->
            viewModel.onRestrictGroup(reason, days)
        }
    }

    private fun chooseRestrictionLength(@StringRes confirmation: Int, onConfirm: (ModerationReason, Int) -> Unit) {
        chooseReason(CaseAction.RESTRICT) { reason ->
            val days = ModerationPolicy.RESTRICT_DAYS
            val labels = days.map { resources.getQuantityString(R.plurals.moderation_days, it, it) }
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.moderation_choose_days)
                .setItems(labels.toTypedArray()) { _, which ->
                    confirm(CaseAction.RESTRICT, getString(confirmation, labels[which])) {
                        onConfirm(reason, days[which])
                    }
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }
    }

    private fun confirm(action: CaseAction, message: String, onConfirm: () -> Unit) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(ModerationTexts.action(action)))
            .setMessage(message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_record) { _, _ -> onConfirm() }
            .show()
    }

    companion object {
        const val ARG_CASE_ID = "caseId"
    }
}
