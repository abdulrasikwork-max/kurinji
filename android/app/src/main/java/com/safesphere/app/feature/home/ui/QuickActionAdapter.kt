package com.safesphere.app.feature.home.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.safesphere.app.R
import com.safesphere.app.core.ui.components.BadgeStatus
import com.safesphere.app.databinding.ItemQuickActionBinding

class QuickActionAdapter(
    private val actions: List<QuickAction>,
    private val onActionClick: (QuickAction) -> Unit
) : RecyclerView.Adapter<QuickActionAdapter.TileViewHolder>() {

    class TileViewHolder(val binding: ItemQuickActionBinding) : RecyclerView.ViewHolder(binding.root)

    override fun getItemCount() = actions.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = TileViewHolder(
        ItemQuickActionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: TileViewHolder, position: Int) {
        val action = actions[position]
        val title = holder.itemView.context.getString(action.titleRes)

        with(holder.binding) {
            tileIcon.setImageResource(action.iconRes)
            tileTitle.text = title
            // Short labels so they fit the narrow tile; the full meaning is in the content description.
            tileBadge.setStatus(BadgeStatus.AVAILABLE, R.string.badge_open)
            root.contentDescription = root.context.getString(R.string.cd_quick_action_open, title)
            root.setOnClickListener { onActionClick(action) }
        }
    }
}
