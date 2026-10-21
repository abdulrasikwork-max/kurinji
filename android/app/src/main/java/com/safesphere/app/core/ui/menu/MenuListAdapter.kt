package com.safesphere.app.core.ui.menu

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.safesphere.app.R
import com.safesphere.app.databinding.ItemMenuHeaderBinding
import com.safesphere.app.databinding.ItemMenuRowBinding

/** One entry in a settings-style list. Shared by the Profile and Settings screens. */
sealed interface MenuListItem {

    data class Header(@StringRes val titleRes: Int) : MenuListItem

    /** A row is either actionable, or marked [isComingSoon] and then not clickable. */
    data class Row(
        @StringRes val titleRes: Int,
        @DrawableRes val iconRes: Int? = null,
        val isComingSoon: Boolean = false,
        val onClick: () -> Unit = {}
    ) : MenuListItem
}

class MenuListAdapter(
    private val items: List<MenuListItem>
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    override fun getItemCount() = items.size

    override fun getItemViewType(position: Int) = when (items[position]) {
        is MenuListItem.Header -> TYPE_HEADER
        is MenuListItem.Row -> TYPE_ROW
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderViewHolder(ItemMenuHeaderBinding.inflate(inflater, parent, false))
            else -> RowViewHolder(ItemMenuRowBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is MenuListItem.Header -> (holder as HeaderViewHolder).bind(item)
            is MenuListItem.Row -> (holder as RowViewHolder).bind(item)
        }
    }

    private class HeaderViewHolder(private val binding: ItemMenuHeaderBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(header: MenuListItem.Header) {
            binding.headerTitle.setText(header.titleRes)
        }
    }

    private class RowViewHolder(private val binding: ItemMenuRowBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(row: MenuListItem.Row) = with(binding) {
            val context = root.context
            val title = context.getString(row.titleRes)

            rowTitle.text = title
            rowIcon.isVisible = row.iconRes != null
            row.iconRes?.let { rowIcon.setImageResource(it) }
            comingSoonLabel.isVisible = row.isComingSoon
            chevron.isVisible = !row.isComingSoon

            // Set the listener first: setOnClickListener makes a view clickable.
            root.setOnClickListener(if (row.isComingSoon) null else View.OnClickListener { row.onClick() })
            root.isClickable = !row.isComingSoon
            root.contentDescription =
                if (row.isComingSoon) context.getString(R.string.cd_menu_item_coming_soon, title) else title
        }
    }

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_ROW = 1
    }
}
