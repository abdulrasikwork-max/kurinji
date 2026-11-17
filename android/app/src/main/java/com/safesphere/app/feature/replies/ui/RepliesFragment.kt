package com.safesphere.app.feature.replies.ui

import android.content.Context
import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.safesphere.app.R
import com.safesphere.app.appContainer
import com.safesphere.app.core.ui.ViewBindingFragment
import com.safesphere.app.core.ui.collectWhenStarted
import com.safesphere.app.core.ui.showSessionExpiredDialog
import com.safesphere.app.core.ui.toMessage
import com.safesphere.app.databinding.FragmentRepliesBinding
import com.safesphere.app.databinding.ItemMomentSectionBinding
import com.safesphere.app.databinding.ItemReplyLineBinding
import com.safesphere.app.databinding.ItemTodayMessageBinding
import com.safesphere.app.feature.moments.ui.MomentFragment
import com.safesphere.app.feature.moments.ui.MomentImages
import com.safesphere.app.feature.moments.ui.ResponseLabels
import com.safesphere.app.feature.moments.ui.momentImages
import com.safesphere.app.feature.moments.ui.secure
import com.safesphere.app.feature.replies.domain.ReplyItem
import com.safesphere.app.navigation.TabReselect
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Phase N5 (D19): the Replies tab. A line opens its conversation (a plan or a private reply) or, without one, the
 * moment. Screenshots are blocked (it names people and holds private words). Nothing is stored.
 */
class RepliesFragment : ViewBindingFragment<FragmentRepliesBinding>(FragmentRepliesBinding::inflate), TabReselect {

    private val viewModel by viewModels<RepliesViewModel> { RepliesViewModel.factory(appContainer) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val adapter = RepliesAdapter(momentImages(), ::open)
        binding.list.adapter = adapter
        binding.retryButton.setOnClickListener { viewModel.onShown() }
        binding.getVerifiedButton.setOnClickListener { findNavController().navigate(R.id.membershipFragment) }
        collectWhenStarted(viewModel.uiState) { state ->
            adapter.submitList(state.rows)
            binding.membershipBlock.isVisible = state.needsMembership
            val failure = state.failure
            val blocking = failure != null && state.page == null
            binding.errorNotice.isVisible = blocking
            binding.retryButton.isVisible = blocking
            if (failure != null) {
                if (blocking) binding.errorNotice.setMessage(failure.toMessage(requireContext()))
                else android.widget.Toast.makeText(requireContext(), failure.toMessage(requireContext()), android.widget.Toast.LENGTH_LONG).show()
                if (!blocking) viewModel.onFailureShown()
            }
            if (state.sessionExpired) {
                viewModel.onSessionExpiredHandled()
                showSessionExpiredDialog()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        secure(true)
        viewModel.onShown()
    }

    override fun onPause() {
        super.onPause()
        secure(false)
    }

    override fun onTabReselected() {
        binding.list.scrollToPosition(0)
        viewModel.onShown()
    }

    private fun open(item: ReplyItem) {
        val conversation = item.conversationId
        if (conversation != null) {
            findNavController().navigate(R.id.conversationFragment, bundleOf(ConversationFragment.ARG_ID to conversation))
        } else {
            findNavController().navigate(R.id.momentFragment, bundleOf(MomentFragment.ARG_ID to item.moment.id))
        }
    }
}

/** An exact time inside a conversation (members only, N-94): a clock today, otherwise the date too. */
object ConversationTime {
    fun short(context: Context, at: Instant, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String {
        val local = at.atZone(zone)
        val clock = DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a", Locale.getDefault())
            .format(local)
        if (local.toLocalDate() == now.atZone(zone).toLocalDate()) return clock
        val date = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault()).format(local)
        return context.getString(R.string.moment_meta_two, date, clock)
    }
}

private class RepliesAdapter(
    private val images: MomentImages,
    private val onOpen: (ReplyItem) -> Unit
) : ListAdapter<RepliesRow, RecyclerView.ViewHolder>(Diff) {

    private class LineHolder(val binding: ItemReplyLineBinding) : RecyclerView.ViewHolder(binding.root)
    private class SectionHolder(val binding: ItemMomentSectionBinding) : RecyclerView.ViewHolder(binding.root)
    private class MessageHolder(val binding: ItemTodayMessageBinding) : RecyclerView.ViewHolder(binding.root)

    override fun getItemViewType(position: Int) = when (getItem(position)) {
        is RepliesRow.Line -> LINE
        is RepliesRow.Section -> SECTION
        else -> MESSAGE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            LINE -> LineHolder(ItemReplyLineBinding.inflate(inflater, parent, false))
            SECTION -> SectionHolder(ItemMomentSectionBinding.inflate(inflater, parent, false))
            else -> MessageHolder(ItemTodayMessageBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is RepliesRow.Line -> bindLine((holder as LineHolder).binding, row.item)
            is RepliesRow.Section -> (holder as SectionHolder).binding.sectionTitle.setText(row.title)
            RepliesRow.Loading -> message(holder, true, R.string.today_loading, null)
            RepliesRow.Empty -> message(holder, false, R.string.replies_empty_title, R.string.replies_empty_message)
            RepliesRow.End -> message(holder, false, R.string.replies_end_title, R.string.replies_end_message)
        }
    }

    private fun bindLine(b: ItemReplyLineBinding, item: ReplyItem) {
        val context = b.root.context
        val first = item.people.firstOrNull()
        val photo = if (first?.profileId != null && first.photo != null) images.personPhotoUrl(first.profileId, first.photo) else null
        val face = first?.name ?: item.moment.authorName
        b.avatar.set(face, face, photo, images.loader)
        b.title.text = ResponseLabels.text(context, RepliesLabels.title(item))
        b.about.text = item.moment.text ?: context.getString(RepliesLabels.momentFallback(item.moment.kind))
        val meta = listOfNotNull(RepliesLabels.turn(item.turn)?.let(context::getString),
            item.at?.let { ConversationTime.short(context, it) })
        b.meta.isVisible = meta.isNotEmpty()
        b.meta.text = meta.joinToString(" · ")
        b.newTag.isVisible = item.fresh
        val spoken = listOfNotNull(b.title.text.toString(), b.about.text.toString(), b.meta.text.toString().takeIf { meta.isNotEmpty() },
            if (item.fresh) context.getString(R.string.replies_new) else null)
        b.content.contentDescription = spoken.joinToString(". ")
        b.content.setOnClickListener { onOpen(item) }
    }

    private fun message(holder: RecyclerView.ViewHolder, loading: Boolean, title: Int, message: Int?) {
        val b = (holder as MessageHolder).binding
        b.progress.isVisible = loading
        b.icon.isVisible = !loading
        b.title.setText(title)
        b.message.isVisible = message != null
        if (message != null) b.message.setText(message)
    }

    private object Diff : DiffUtil.ItemCallback<RepliesRow>() {
        override fun areItemsTheSame(old: RepliesRow, new: RepliesRow) = when {
            old is RepliesRow.Line && new is RepliesRow.Line ->
                old.item.type == new.item.type && old.item.moment.id == new.item.moment.id &&
                    old.item.conversationId == new.item.conversationId
            old is RepliesRow.Section && new is RepliesRow.Section -> old.title == new.title
            else -> old::class == new::class
        }

        override fun areContentsTheSame(old: RepliesRow, new: RepliesRow) = old == new
    }

    private companion object {
        const val LINE = 0
        const val SECTION = 1
        const val MESSAGE = 2
    }
}
