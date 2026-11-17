package com.safesphere.app.feature.replies.ui

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.safesphere.app.R
import com.safesphere.app.appContainer
import com.safesphere.app.core.ui.ViewBindingFragment
import com.safesphere.app.core.ui.collectWhenStarted
import com.safesphere.app.core.ui.showSessionExpiredDialog
import com.safesphere.app.core.ui.toMessage
import com.safesphere.app.databinding.FragmentConversationBinding
import com.safesphere.app.databinding.ItemMessageBinding
import com.safesphere.app.feature.moments.ui.MomentCardBinder
import com.safesphere.app.feature.moments.ui.MomentFragment
import com.safesphere.app.feature.moments.ui.ResponseLabels
import com.safesphere.app.feature.moments.ui.secure
import com.safesphere.app.feature.replies.domain.Conversation
import com.safesphere.app.feature.replies.domain.ConversationKind
import com.safesphere.app.feature.replies.domain.Message
import com.safesphere.app.feature.replies.domain.MessageKind
import com.safesphere.app.feature.social.ui.DraftNudgeDialogs
import com.safesphere.app.feature.social.domain.ReportTarget
import com.safesphere.app.feature.social.ui.ModerationLabels
import com.safesphere.app.feature.social.ui.ReportDialogs
import com.safesphere.app.feature.social.ui.ReportMessage
import com.safesphere.app.feature.social.ui.ReportUiState
import com.safesphere.app.feature.social.ui.ReportViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Phase N5 (D17, D18): a plan or a private reply (a task screen: toolbar and back). Screenshots are blocked. It reads
 * again when it comes back and every [REFRESH_MS] while it is on screen (push arrives in N7); nothing tells anyone that
 * it was opened. Every message is checked on the phone first (the private nudge) and again by the server.
 */
class ConversationFragment : ViewBindingFragment<FragmentConversationBinding>(FragmentConversationBinding::inflate) {

    private val viewModel by viewModels<ConversationViewModel> { ConversationViewModel.factory(appContainer) }
    private val reports by viewModels<ReportViewModel> { ReportViewModel.factory(appContainer) }
    private lateinit var adapter: MessageAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = MessageAdapter(onUncover = { viewModel.onUncover(it.id) }, onOwn = ::ownMessage, onReport = ::reportMessage)
        binding.messages.layoutManager = LinearLayoutManager(requireContext()).apply { stackFromEnd = true }
        binding.messages.adapter = adapter
        binding.retryButton.setOnClickListener { viewModel.retry() }
        binding.sendButton.setOnClickListener { send() }
        binding.endButton.setOnClickListener { confirmEnd() }
        // N-92: This happened opens Create in its own mode (a photo taken now, the plan's audience).
        binding.happenedButton.setOnClickListener {
            val moment = viewModel.uiState.value.conversation?.moment ?: return@setOnClickListener
            findNavController().navigate(R.id.createFragment, bundleOf(
                com.safesphere.app.feature.moments.ui.CreateFragment.ARG_HAPPENED_OF to moment.id,
                com.safesphere.app.feature.moments.ui.CreateFragment.ARG_HAPPENED_TEXT to moment.text))
        }
        binding.openMomentButton.setOnClickListener {
            val momentId = viewModel.uiState.value.conversation?.moment?.id ?: viewModel.uiState.value.startFor ?: return@setOnClickListener
            findNavController().navigate(R.id.momentFragment, bundleOf(MomentFragment.ARG_ID to momentId))
        }
        collectWhenStarted(viewModel.uiState, ::render)
        collectWhenStarted(reports.uiState, ::renderReports)

        val args = requireArguments()
        val conversationId = args.getString(ARG_ID)
        val momentId = args.getString(ARG_MOMENT)
        if (conversationId != null) viewModel.load(conversationId)
        else if (momentId != null) viewModel.start(momentId, args.getString(ARG_NAME).orEmpty())

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    viewModel.refresh()
                    delay(REFRESH_MS)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        secure(true)
    }

    override fun onPause() {
        super.onPause()
        secure(false)
    }

    private fun send() {
        val words = binding.input.text?.toString()?.trim().orEmpty()
        if (words.isEmpty()) return
        DraftNudgeDialogs.beforeSharing(this, words, onlyMe = false) { viewModel.send(words) }
    }

    /** Phase 13D for messages (N5-A3): the reporter is told only that a moderator will look; the writer is never told. */
    private fun reportMessage(message: Message) {
        if (message.mine || message.kind != MessageKind.TEXT) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.message_actions_title)
            .setItems(arrayOf(getString(R.string.message_report))) { _, _ ->
                ReportDialogs.show(this, ReportTarget.MESSAGE) { reason, note -> reports.report(ReportTarget.MESSAGE, message.id, reason, note) }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /**
     * The writer's own held or covered message: ask a moderator to look at what a rule did, or ask for a review of a
     * moderator's decision (only when the decision can still be appealed). No shadow states: the notice already says it.
     */
    private fun ownMessage(message: Message) {
        val notice = message.notice ?: return
        val options = ArrayList<Pair<String, () -> Unit>>()
        val state = reports.uiState.value
        if (notice.reviewable && message.id !in state.checkReviewAsked) {
            options += getString(R.string.check_review_ask) to { reports.askCheckReview(ReportTarget.MESSAGE, message.id) }
        }
        val decision = state.standing?.decisionFor(notice.decisionId)
        if (decision?.canAppeal == true) options += getString(R.string.review_ask) to { reports.appeal(decision.decisionId) }
        val line = decision?.let { ModerationLabels.appealLine(it.appeal) }?.let(::getString)
            ?: if (message.id in state.checkReviewAsked) getString(R.string.check_review_asked) else null
        val builder = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.message_actions_title)
            .setNegativeButton(R.string.action_cancel, null)
        if (options.isEmpty()) builder.setMessage(line ?: MomentCardBinder.noticeText(requireContext(), notice))
        else builder.setItems(options.map { it.first }.toTypedArray()) { _, which -> options[which].second() }
        builder.show()
    }

    private fun renderReports(state: ReportUiState) {
        if (view == null) return
        when (state.message) {
            ReportMessage.REPORT_SENT -> toast(R.string.report_sent)
            ReportMessage.CHECK_REVIEW_ASKED -> toast(R.string.check_review_asked)
            ReportMessage.REVIEW_ASKED -> toast(R.string.review_asked)
            else -> Unit
        }
        if (state.message != null) reports.onMessageShown()
        state.failure?.let {
            Toast.makeText(requireContext(), it.toMessage(requireContext()), Toast.LENGTH_LONG).show()
            reports.onFailureShown()
        }
        if (state.sessionExpired) {
            reports.onSessionExpiredHandled()
            showSessionExpiredDialog()
        }
    }

    private fun toast(text: Int) = Toast.makeText(requireContext(), text, Toast.LENGTH_LONG).show()

    private fun confirmEnd() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.conversation_end_title)
            .setMessage(R.string.conversation_end_message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.conversation_end) { _, _ -> viewModel.end() }
            .show()
    }

    private fun render(state: ConversationUiState) {
        if (view == null) return
        val context = requireContext()
        val conversation = state.conversation
        binding.progress.isVisible = state.loading || state.sending
        binding.goneBlock.isVisible = state.gone
        binding.head.isVisible = conversation != null || state.startFor != null
        if (conversation != null) renderHead(conversation)
        else if (state.startFor != null) {
            binding.heading.text = getString(R.string.reply_sheet_title)
            binding.about.text = state.startName.orEmpty()
            binding.planLine.isVisible = false
            binding.peopleLine.text = getString(R.string.reply_sheet_message, state.startName.orEmpty())
            binding.closesLine.isVisible = false
            binding.endButton.isVisible = false
        }
        adapter.uncovered = state.uncovered
        adapter.submitList(conversation?.messages.orEmpty()) {
            if (adapter.itemCount > 0) binding.messages.scrollToPosition(adapter.itemCount - 1)
        }

        val writing = !state.gone && state.canWrite
        binding.composer.isVisible = writing
        binding.sendButton.isEnabled = !state.sending
        binding.composerHint.isVisible = writing && (conversation?.kind ?: ConversationKind.REPLY) == ConversationKind.REPLY
        binding.composerHint.setText(R.string.conversation_turns_hint)
        val status = if (conversation != null && !writing && !state.gone) RepliesLabels.cannotSend(conversation) else null
        binding.statusLine.isVisible = status != null
        if (status != null) binding.statusLine.setText(status)

        if (state.sent) {
            binding.input.text = null
            viewModel.onSentHandled()
        }
        if (state.support) {
            viewModel.onSupportShown()
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.nudge_support_title)
                .setMessage(R.string.nudge_support_message)
                .setNeutralButton(R.string.nudge_support_call) { _, _ -> DraftNudgeDialogs.dialHelpline(this) }
                .setPositiveButton(R.string.nudge_support_back, null)
                .show()
        }
        val failure = state.failure
        val blocking = failure != null && conversation == null && state.startFor == null
        binding.errorNotice.isVisible = blocking
        binding.retryButton.isVisible = blocking
        if (failure != null) {
            if (blocking) binding.errorNotice.setMessage(failure.toMessage(context))
            else {
                // Our own words for a refusal ("Wait for an answer before you write again", a closed one, a pause).
                Toast.makeText(context, failure.toMessage(context), Toast.LENGTH_LONG).show()
                viewModel.onFailureShown()
            }
        }
        if (state.sessionExpired) {
            viewModel.onSessionExpiredHandled()
            showSessionExpiredDialog()
        }
    }

    private fun renderHead(conversation: Conversation) {
        val context = requireContext()
        val moment = conversation.moment
        binding.heading.text = if (conversation.kind == ConversationKind.REPLY && !moment.mine)
            getString(RepliesLabels.heading(conversation), moment.authorName) else getString(RepliesLabels.heading(conversation))
        binding.about.text = moment.text ?: getString(RepliesLabels.momentFallback(moment.kind))
        val plan = moment.plan
        val planWords = listOfNotNull(plan?.at?.let { MomentCardBinder.planTime(context, it) }, plan?.where,
            if (moment.calledOff) getString(R.string.response_plan_called_off) else null)
        binding.planLine.isVisible = planWords.isNotEmpty()
        binding.planLine.text = planWords.joinToString(" · ")
        val names = conversation.people.map { it.name }
        binding.peopleLine.isVisible = names.isNotEmpty()
        binding.peopleLine.text = if (names.isEmpty()) "" else getString(
            if (conversation.kind == ConversationKind.PLAN) R.string.conversation_people_plan else R.string.conversation_people_reply,
            ResponseLabels.joined(context, names, false))
        val closes = conversation.closesAt
        binding.closesLine.isVisible = closes != null && conversation.open
        if (closes != null) binding.closesLine.text = getString(R.string.conversation_closes, ConversationTime.short(context, closes))
        binding.endButton.isVisible = RepliesLabels.canEnd(conversation)
        binding.happenedButton.isVisible = RepliesLabels.canShareHappened(conversation, java.time.Instant.now())
        // The decision behind a moderator's notice on one's own message, to know whether a review can be asked (read once).
        if (conversation.messages.any { it.mine && it.notice?.decisionId != null } && reports.uiState.value.standing == null &&
            !reports.uiState.value.isBusy) reports.loadStanding()
        binding.holdHint.isVisible = conversation.messages.any { !it.mine && it.kind == MessageKind.TEXT }
    }

    companion object {
        const val ARG_ID = "conversationId"
        /** Starting a new private reply: the moment and its author's name. */
        const val ARG_MOMENT = "replyToMoment"
        const val ARG_NAME = "replyToName"
        const val REFRESH_MS = 30_000L
    }
}

/** The messages, oldest first. A collapsed message waits behind a tap; the writer's own notice says its state. */
private class MessageAdapter(
    private val onUncover: (Message) -> Unit,
    private val onOwn: (Message) -> Unit,
    private val onReport: (Message) -> Unit
) :
    ListAdapter<Message, MessageAdapter.Holder>(Diff) {

    var uncovered: Set<String> = emptySet()
        set(value) {
            if (field != value) {
                field = value
                notifyItemRangeChanged(0, itemCount)
            }
        }

    class Holder(val binding: ItemMessageBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val b = holder.binding
        val message = getItem(position)
        val context = b.root.context
        val time = message.at?.let { ConversationTime.short(context, it) }
        val name = message.by?.name ?: context.getString(R.string.conversation_someone)
        if (message.kind == MessageKind.SYSTEM) {
            b.systemLine.isVisible = true
            b.textBlock.isVisible = false
            val event = message.event ?: return
            val words = context.getString(RepliesLabels.event(event, message.mine), name)
            b.systemLine.text = listOfNotNull(words, time).joinToString(" · ")
            b.row.contentDescription = b.systemLine.text
            b.row.setOnClickListener(null)
            b.row.isClickable = false
            return
        }
        b.systemLine.isVisible = false
        b.textBlock.isVisible = true
        (b.textBlock.layoutParams as LinearLayout.LayoutParams).gravity = if (message.mine) Gravity.END else Gravity.START
        b.textBlock.gravity = if (message.mine) Gravity.END else Gravity.START
        b.writer.isVisible = !message.mine
        b.writer.text = name
        val covered = message.collapsed && message.id !in uncovered
        b.bubble.setBackgroundResource(if (message.mine) R.drawable.bg_message_mine else R.drawable.bg_message_theirs)
        b.bubble.text = if (covered) context.getString(R.string.conversation_cover) else message.text
        b.time.isVisible = time != null
        b.time.text = time.orEmpty()
        // The writer's own message: its state in the same words as a moment (amber; the reason when there is one).
        val notice = message.notice
        val noticeText = notice?.let { MomentCardBinder.noticeText(context, it) }
        b.notice.isVisible = noticeText != null
        if (noticeText != null) b.notice.setMessage(noticeText)
        b.row.contentDescription = context.getString(R.string.conversation_message_spoken,
            if (message.mine) context.getString(R.string.conversation_you) else name,
            listOfNotNull(b.bubble.text.toString(), time, noticeText).joinToString(". "))
        // A covered message opens on a tap; one's own with a notice offers a review; someone else's can be reported
        // (touch and hold, also a TalkBack action).
        when {
            covered -> b.row.setOnClickListener { onUncover(message) }
            message.mine && notice != null -> b.row.setOnClickListener { onOwn(message) }
            else -> b.row.setOnClickListener(null)
        }
        b.row.isClickable = covered || (message.mine && notice != null)
        if (!message.mine) {
            b.row.setOnLongClickListener { onReport(message); true }
        } else {
            b.row.setOnLongClickListener(null)
            b.row.isLongClickable = false
        }
    }

    private object Diff : DiffUtil.ItemCallback<Message>() {
        override fun areItemsTheSame(old: Message, new: Message) = old.id == new.id
        override fun areContentsTheSame(old: Message, new: Message) = old == new
    }
}
