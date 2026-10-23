package com.safesphere.app.feature.social.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.safesphere.app.databinding.ItemPersonRowBinding

/** One button of a row: its text, what TalkBack says ("Accept the request from Asha"), and what it does. */
data class RowAction(val label: String, val contentDescription: String, val onClick: () -> Unit)

/**
 * One row of the social lists (requests, connections, invite codes, blocks, mutes): a title, a second line, up to two
 * buttons, and optionally a tap on the text (opens the person). No counts, no pictures.
 */
data class PersonRow(
    val key: String,
    val title: String,
    val subtitle: String,
    val primary: RowAction? = null,
    val secondary: RowAction? = null,
    val onOpen: (() -> Unit)? = null,
    val openDescription: String? = null
)

class PersonRowAdapter : ListAdapter<PersonRow, PersonRowAdapter.Holder>(Diff) {

    class Holder(val binding: ItemPersonRowBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemPersonRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = getItem(position)
        with(holder.binding) {
            title.text = row.title
            subtitle.text = row.subtitle
            textBlock.isClickable = row.onOpen != null
            textBlock.isFocusable = row.onOpen != null
            textBlock.contentDescription = row.openDescription
            textBlock.setOnClickListener(row.onOpen?.let { open -> { _: android.view.View -> open() } })
            bind(primaryButton, row.primary)
            bind(secondaryButton, row.secondary)
            buttons.isVisible = row.primary != null || row.secondary != null
        }
    }

    private fun bind(button: com.google.android.material.button.MaterialButton, action: RowAction?) {
        button.isVisible = action != null
        if (action == null) return
        button.text = action.label
        button.contentDescription = action.contentDescription
        button.setOnClickListener { action.onClick() }
    }

    private object Diff : DiffUtil.ItemCallback<PersonRow>() {
        override fun areItemsTheSame(old: PersonRow, new: PersonRow) = old.key == new.key

        // Lambdas are not compared; the visible text decides whether the row is redrawn.
        override fun areContentsTheSame(old: PersonRow, new: PersonRow) =
            old.title == new.title && old.subtitle == new.subtitle &&
                old.primary?.label == new.primary?.label && old.secondary?.label == new.secondary?.label
    }
}
