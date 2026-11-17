package com.safesphere.app.feature.moments.ui

import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.text.format.DateFormat
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.safesphere.app.R
import com.safesphere.app.appContainer
import com.safesphere.app.core.ui.ViewBindingFragment
import com.safesphere.app.core.ui.collectWhenStarted
import com.safesphere.app.core.ui.showSessionExpiredDialog
import com.safesphere.app.core.ui.toMessage
import com.safesphere.app.databinding.FragmentMomentBinding
import com.safesphere.app.feature.moments.domain.Moment
import com.safesphere.app.feature.moments.domain.MomentStatus
import com.safesphere.app.feature.moments.domain.PlanPerson
import com.safesphere.app.feature.social.domain.ReportTarget
import com.safesphere.app.feature.replies.ui.ConversationFragment
import com.safesphere.app.feature.social.ui.DraftNudgeDialogs
import com.safesphere.app.feature.social.ui.ModerationLabels
import com.safesphere.app.feature.social.ui.ReportDialogs
import com.safesphere.app.feature.social.ui.ReportMessage
import com.safesphere.app.feature.social.ui.ReportUiState
import com.safesphere.app.feature.social.ui.ReportViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Phase N4: a moment on its own page (a reading screen: the bottom bar stays). The author sees when it leaves Today
 * and can keep, un-keep or delete it; everyone else can open the author's profile. Screenshots are blocked (D12).
 * A3: others can report it or say "This is about me" (hidden at once, a moderator looks); the author can ask a
 * moderator to look at what a rule did, or ask for a review of a moderator's decision. Nobody learns who asked.
 */
class MomentFragment : ViewBindingFragment<FragmentMomentBinding>(FragmentMomentBinding::inflate) {

    private val viewModel by viewModels<MomentViewModel> { MomentViewModel.factory(appContainer) }
    private val reports by viewModels<ReportViewModel> { ReportViewModel.factory(appContainer) }
    private var peopleSheet: MomentPeopleSheet? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.retryButton.setOnClickListener { viewModel.retry() }
        binding.keepButton.setOnClickListener {
            val state = viewModel.uiState.value
            if (state.canUnkeep) viewModel.onUnkeep() else viewModel.onKeep()
        }
        binding.deleteButton.setOnClickListener { confirmDelete() }
        binding.momentCard.content.setOnClickListener { viewModel.onUncover() }
        binding.reportButton.setOnClickListener { report() }
        binding.aboutMeButton.setOnClickListener { confirmAboutMe() }
        binding.checkReviewButton.setOnClickListener { viewModel.uiState.value.moment?.let { reports.askCheckReview(ReportTarget.MOMENT, it.id) } }
        binding.appealButton.setOnClickListener { viewModel.uiState.value.moment?.notice?.decisionId?.let(reports::appeal) }
        // N5: responses
        binding.sameButton.setOnClickListener { viewModel.onSame() }
        binding.sayMoreButton.setOnClickListener { viewModel.onSayMore() }
        binding.inButton.setOnClickListener { viewModel.onAskIn() }
        binding.withdrawButton.setOnClickListener { viewModel.onWithdraw() }
        binding.addMoreButton.setOnClickListener { askMore() }
        binding.callOffButton.setOnClickListener { confirmCallOff() }
        // N5 (A2): one's own private reply opens; otherwise a new one starts on the conversation screen.
        binding.replyButton.setOnClickListener {
            val moment = viewModel.uiState.value.moment ?: return@setOnClickListener
            val own = moment.responses.replyId
            findNavController().navigate(R.id.conversationFragment, if (own != null) bundleOf(ConversationFragment.ARG_ID to own)
                else bundleOf(ConversationFragment.ARG_MOMENT to moment.id, ConversationFragment.ARG_NAME to moment.author.name))
        }
        binding.planButton.setOnClickListener {
            val plan = viewModel.uiState.value.moment?.responses?.plan?.planId ?: return@setOnClickListener
            findNavController().navigate(R.id.conversationFragment, bundleOf(ConversationFragment.ARG_ID to plan))
        }
        peopleSheet = MomentPeopleSheet(this, momentImages(), ::onPersonAction) { viewModel.onPeopleClosed() }
        binding.peopleButton.setOnClickListener {
            peopleSheet?.show()
            viewModel.loadPeople()
            renderPeople(viewModel.uiState.value)
        }
        collectWhenStarted(reports.uiState, ::renderReports)
        collectWhenStarted(viewModel.uiState, ::render)
        viewModel.load(requireArguments().getString(ARG_ID).orEmpty())
    }

    override fun onResume() {
        super.onResume()
        secure(true)
        viewModel.onShownAgain()
    }

    override fun onPause() {
        super.onPause()
        secure(false)
    }

    override fun onDestroyView() {
        peopleSheet?.dismiss()
        peopleSheet = null
        super.onDestroyView()
    }

    // ---- N5: responses ----------------------------------------------------------------------------------------------

    /** D15: the author's one "say more", checked on the phone first (the private nudge), then by the server. */
    private fun askMore() {
        val context = requireContext()
        val input = EditText(context).apply {
            setHint(R.string.response_more_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            filters = arrayOf(InputFilter.LengthFilter(MORE_MAX))
            minLines = 2
        }
        val padding = context.resources.getDimensionPixelSize(R.dimen.screen_padding)
        val frame = FrameLayout(context).apply {
            setPadding(padding, 0, padding, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.response_more_title)
            .setMessage(R.string.response_more_message)
            .setView(frame)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.response_more_add) { _, _ ->
                val words = input.text?.toString()?.trim().orEmpty()
                if (words.isNotEmpty()) DraftNudgeDialogs.beforeSharing(this, words, onlyMe = false) { viewModel.onAddMore(words) }
            }
            .show()
    }

    private fun confirmCallOff() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.response_call_off_title)
            .setMessage(R.string.response_call_off_message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.response_action_call_off) { _, _ -> viewModel.onCallOff() }
            .show()
    }

    private fun onPersonAction(action: PersonAction, person: PlanPerson) {
        val ref = person.ref ?: return
        when (action) {
            PersonAction.ADD -> viewModel.onAdd(ref)
            PersonAction.SET_ASIDE -> viewModel.onSetAside(ref, aside = true)
            PersonAction.BRING_BACK -> viewModel.onSetAside(ref, aside = false)
            PersonAction.REMOVE -> viewModel.onRemove(ref)
        }
    }

    private fun renderPeople(state: MomentUiState) {
        val sheet = peopleSheet ?: return
        val moment = state.moment
        if (moment == null) {
            sheet.dismiss()
            return
        }
        sheet.render(state.people, state.peopleLoading, state.busy, author = moment.mine,
            open = moment.responses.plan?.open == true)
    }

    private fun renderResponses(state: MomentUiState, moment: Moment) {
        val actions = state.actions
        val context = requireContext()
        val responses = moment.responses
        val lines = if (moment.mine) ResponseLabels.author(moment) else listOfNotNull(ResponseLabels.same(responses.same)?.let { ResponseWords(it) })
        val standing = if (moment.mine) null else ResponseLabels.standing(responses.plan, moment.author.name, onCard = false)
        binding.responseBlock.isVisible = actions.any || lines.isNotEmpty() || standing != null

        binding.signalRow.isVisible = actions.same || actions.sayMore
        binding.sameButton.isVisible = actions.same
        bindToggle(binding.sameButton, responses.same?.mine == true)
        binding.sayMoreButton.isVisible = actions.sayMore
        bindToggle(binding.sayMoreButton, responses.sayMore)
        binding.inButton.isVisible = actions.askIn
        binding.standingLine.isVisible = standing != null
        binding.standingLine.text = standing?.let { ResponseLabels.text(context, it) }.orEmpty()
        binding.responseLines.isVisible = lines.isNotEmpty()
        binding.responseLines.text = lines.joinToString("\n") { ResponseLabels.text(context, it) }
        binding.withdrawButton.isVisible = actions.withdraw
        binding.withdrawButton.setText(ResponseLabels.withdrawLabel(responses.plan))
        binding.addMoreButton.isVisible = actions.addMore
        binding.peopleButton.isVisible = actions.people
        binding.callOffButton.isVisible = actions.callOff
        binding.replyButton.isVisible = actions.reply
        binding.replyButton.setText(if (responses.replyId != null) R.string.response_action_your_reply else R.string.response_action_reply)
        binding.planButton.isVisible = actions.openPlan
        for (button in listOf(binding.sameButton, binding.sayMoreButton, binding.inButton, binding.withdrawButton,
                binding.addMoreButton, binding.peopleButton, binding.callOffButton)) {
            button.isEnabled = !state.busy
        }
    }

    /** A given response: checked (spoken as such), a tick, the same word. Never colour alone. */
    private fun bindToggle(button: com.google.android.material.button.MaterialButton, on: Boolean) {
        button.isChecked = on
        button.icon = if (on) androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.ic_check) else null
    }

    private fun report() {
        val moment = viewModel.uiState.value.moment ?: return
        ReportDialogs.show(this, ReportTarget.MOMENT) { reason, note -> reports.report(ReportTarget.MOMENT, moment.id, reason, note) }
    }

    /** D11: said once, plainly, before it hides someone's moment: what happens, and that nobody is told who asked. */
    private fun confirmAboutMe() {
        val moment = viewModel.uiState.value.moment ?: return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.moment_about_me_title)
            .setMessage(R.string.moment_about_me_message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.moment_about_me_confirm) { _, _ -> reports.aboutMe(moment.id) }
            .show()
    }

    private fun renderReports(state: ReportUiState) {
        if (view == null) return
        binding.reportButton.isEnabled = !state.isBusy
        binding.aboutMeButton.isEnabled = !state.isBusy
        when (state.message) {
            ReportMessage.REPORT_SENT -> toast(R.string.report_sent)
            ReportMessage.CHECK_REVIEW_ASKED -> toast(R.string.check_review_asked)
            ReportMessage.REVIEW_ASKED -> toast(R.string.review_asked)
            ReportMessage.ABOUT_ME_SENT -> {
                toast(R.string.moment_about_me_sent)
                // Hidden from everyone but its author now (also from the person who asked): it leaves the wall.
                viewModel.uiState.value.moment?.let { findNavController().previousBackStackEntry?.savedStateHandle?.set(RESULT_REMOVED, it.id) }
                reports.onMessageShown()
                findNavController().popBackStack()
                return
            }
            null -> Unit
        }
        if (state.message != null) reports.onMessageShown()
        state.failure?.let {
            toast(it.toMessage(requireContext()))
            reports.onFailureShown()
        }
        if (state.sessionExpired) {
            reports.onSessionExpiredHandled()
            showSessionExpiredDialog()
        }
        viewModel.uiState.value.moment?.let { if (it.mine) renderReview(it, state) }
    }

    /** The author's ways to ask a person to look (no shadow states: the notice already says what happened). */
    private fun renderReview(moment: Moment, reportState: ReportUiState) {
        val notice = moment.notice
        val asked = moment.id in reportState.checkReviewAsked
        binding.checkReviewButton.isVisible = notice != null && notice.reviewable && !asked
        val decision = reportState.standing?.decisionFor(notice?.decisionId)
        binding.appealButton.isVisible = decision?.canAppeal == true
        val line = decision?.let { ModerationLabels.appealLine(it.appeal) }
        binding.reviewLine.isVisible = line != null || asked
        binding.reviewLine.text = when {
            line != null -> getString(line)
            asked -> getString(R.string.check_review_asked)
            else -> ""
        }
        binding.checkReviewButton.isEnabled = !reportState.isBusy
        binding.appealButton.isEnabled = !reportState.isBusy
    }

    private fun toast(text: Int) = Toast.makeText(requireContext(), text, Toast.LENGTH_LONG).show()
    private fun toast(text: String) = Toast.makeText(requireContext(), text, Toast.LENGTH_LONG).show()

    private fun confirmDelete() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.moment_delete_title)
            .setMessage(R.string.moment_delete_message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.moment_action_delete) { _, _ -> viewModel.onDelete() }
            .show()
    }

    private fun render(state: MomentUiState) {
        if (view == null) return
        if (state.removed) {
            val nav = findNavController()
            state.moment?.let { nav.previousBackStackEntry?.savedStateHandle?.set(RESULT_REMOVED, it.id) }
            nav.popBackStack()
            return
        }
        if (state.changed) {
            // The wall behind reads this moment again (or drops it when it went).
            requireArguments().getString(ARG_ID)?.let { findNavController().previousBackStackEntry?.savedStateHandle?.set(RESULT_CHANGED, it) }
            viewModel.onChangedHandled()
        }
        if (state.support) {
            viewModel.onSupportShown()
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.nudge_support_title)
                .setMessage(R.string.nudge_support_message)
                .setNeutralButton(R.string.nudge_support_call) { _, _ -> DraftNudgeDialogs.dialHelpline(this) }
                .setPositiveButton(R.string.nudge_support_back, null)
                .show()
        }
        val moment = state.moment
        binding.progress.isVisible = state.loading || state.busy
        binding.goneBlock.isVisible = state.gone
        binding.momentCard.root.isVisible = moment != null
        binding.responseBlock.isVisible = false
        renderPeople(state)
        binding.ownBlock.isVisible = moment != null && moment.mine
        binding.authorButton.isVisible = false
        binding.othersBlock.isVisible = moment != null && !moment.mine
        if (moment != null) {
            MomentCardBinder.bind(binding.momentCard, moment, state.open, momentImages(), responseLine = false)
            binding.momentCard.content.isClickable = MomentCardBinder.covered(moment, state.open)
            // A covered moment is answered only after it was read.
            if (!MomentCardBinder.covered(moment, state.open)) renderResponses(state, moment)
            if (moment.mine) {
                renderOwn(state, moment)
                renderReview(moment, reports.uiState.value)
                // The decision behind a moderator's notice, to know whether a review can be asked (read once).
                if (moment.notice?.decisionId != null && reports.uiState.value.standing == null) reports.loadStanding()
            } else {
                renderAuthor(moment)
            }
        }

        val failure = state.failure
        if (failure != null && moment != null) {
            // Our own words for a refused keep ("You can keep 6 moments…") or a lost connection, once.
            Toast.makeText(requireContext(), failure.toMessage(requireContext()), Toast.LENGTH_LONG).show()
            viewModel.onFailureShown()
        }
        binding.errorNotice.isVisible = failure != null && moment == null
        binding.retryButton.isVisible = failure != null && moment == null
        if (failure != null && moment == null) binding.errorNotice.setMessage(failure.toMessage(requireContext()))
        if (state.sessionExpired) {
            viewModel.onSessionExpiredHandled()
            showSessionExpiredDialog()
        }
    }

    private fun renderOwn(state: MomentUiState, moment: Moment) {
        val ends = moment.expiresAt
        binding.lifeLine.isVisible = true
        binding.lifeLine.text = when {
            moment.status == MomentStatus.WAITING -> getString(R.string.moment_life_waiting)
            moment.status == MomentStatus.ENDED -> getString(R.string.moment_life_ended)
            ends != null -> getString(R.string.moment_life_until, clock(ends))
            else -> getString(R.string.moment_life_live)
        }
        val keepable = state.canKeep || state.canUnkeep
        binding.keepButton.isVisible = keepable
        binding.keepHint.isVisible = state.canKeep
        binding.keepButton.setText(if (state.canUnkeep) R.string.moment_action_unkeep else R.string.moment_action_keep)
        binding.keepButton.isEnabled = !state.busy
        binding.deleteButton.isEnabled = !state.busy
    }

    private fun renderAuthor(moment: Moment) {
        val profileId = moment.author.profileId ?: return
        binding.authorButton.isVisible = true
        binding.authorButton.text = getString(R.string.moment_open_author, moment.author.name)
        binding.authorButton.setOnClickListener {
            findNavController().navigate(R.id.personProfileFragment, bundleOf("profileId" to profileId))
        }
    }

    /** The author's own exact time, in their phone's zone and clock style. */
    private fun clock(at: Instant): String {
        val pattern = if (DateFormat.is24HourFormat(requireContext())) "EEE HH:mm" else "EEE h:mm a"
        return DateTimeFormatter.ofPattern(pattern, Locale.getDefault()).format(at.atZone(ZoneId.systemDefault()))
    }

    companion object {
        const val ARG_ID = "momentId"
        /** Set on the previous screen's saved state when the author deleted the moment. */
        const val RESULT_REMOVED = "moment_removed"
        /** N5: set on the previous screen's saved state when a response changed the moment (its id). */
        const val RESULT_CHANGED = "moment_changed"
        /** D15: the follow-up's limit (the server counts characters the same way and has the last word). */
        const val MORE_MAX = 500
    }
}
