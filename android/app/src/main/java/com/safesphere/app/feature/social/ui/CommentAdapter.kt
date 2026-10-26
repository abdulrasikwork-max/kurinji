package com.safesphere.app.feature.social.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.core.view.updatePaddingRelative
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.safesphere.app.R
import com.safesphere.app.databinding.ItemCommentBinding

/**
 * Phase 16: one comment as the post screen shows it. [onReply] null = no Reply button; [onMore] null = no More button.
 * [spoken] is the whole comment in one sentence for TalkBack.
 */
data class CommentRow(
    val key: String,
    val name: String?,
    val handle: String?,
    val header: String,
    val body: String,
    val notes: String,
    val isReply: Boolean,
    val spoken: String,
    val replyDescription: String?,
    val onReply: (() -> Unit)?,
    val onMore: (() -> Unit)?
)

class CommentAdapter : ListAdapter<CommentRow, CommentAdapter.Holder>(Diff) {

    class Holder(val binding: ItemCommentBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemCommentBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = getItem(position)
        val b = holder.binding
        val indent = if (row.isReply) b.root.resources.getDimensionPixelSize(R.dimen.reply_indent) else 0
        b.row.updatePaddingRelative(start = indent)
        b.avatar.set(row.name, row.handle)
        b.header.text = row.header
        b.body.text = row.body
        b.notes.text = row.notes
        b.notes.isVisible = row.notes.isNotEmpty()
        b.readable.contentDescription = row.spoken
        b.replyButton.isVisible = row.onReply != null
        b.replyButton.contentDescription = row.replyDescription
        b.replyButton.setOnClickListener { row.onReply?.invoke() }
        b.moreButton.isVisible = row.onMore != null
        b.moreButton.setOnClickListener { row.onMore?.invoke() }
    }

    private object Diff : DiffUtil.ItemCallback<CommentRow>() {
        override fun areItemsTheSame(old: CommentRow, new: CommentRow) = old.key == new.key
        override fun areContentsTheSame(old: CommentRow, new: CommentRow) =
            old.copy(onReply = null, onMore = null) == new.copy(onReply = null, onMore = null) &&
                (old.onReply == null) == (new.onReply == null) && (old.onMore == null) == (new.onMore == null)
    }
}
