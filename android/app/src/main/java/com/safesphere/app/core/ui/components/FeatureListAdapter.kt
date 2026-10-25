package com.safesphere.app.core.ui.components

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.safesphere.app.R
import com.safesphere.app.databinding.ItemFeatureRowBinding
import com.google.android.material.R as MaterialR

/**
 * One card row: icon, title, description and an optional status badge. Used by the Safety hub and You. Phase 13F: no
 * badge by default ([status] null): every row shown opens a working screen (Phase 10A), so "Available" said nothing.
 */
data class FeatureItem(
    @DrawableRes val iconRes: Int,
    @StringRes val titleRes: Int,
    @StringRes val descriptionRes: Int,
    val status: BadgeStatus? = null,
    /** Only emergency features use red, so the colour keeps its meaning. */
    val isEmergency: Boolean = false,
    val onClick: () -> Unit
)

class FeatureListAdapter(
    private val items: List<FeatureItem>
) : RecyclerView.Adapter<FeatureListAdapter.RowViewHolder>() {

    class RowViewHolder(val binding: ItemFeatureRowBinding) : RecyclerView.ViewHolder(binding.root)

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = RowViewHolder(
        ItemFeatureRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: RowViewHolder, position: Int) {
        val item = items[position]
        val context = holder.itemView.context
        val title = context.getString(item.titleRes)
        val description = context.getString(item.descriptionRes)

        with(holder.binding) {
            val containerColor = root.themeColor(
                if (item.isEmergency) MaterialR.attr.colorErrorContainer else MaterialR.attr.colorPrimaryContainer
            )
            val iconColor = root.themeColor(
                if (item.isEmergency) MaterialR.attr.colorError else MaterialR.attr.colorOnPrimaryContainer
            )

            iconContainer.setImageResource(item.iconRes)
            iconContainer.backgroundTintList = ColorStateList.valueOf(containerColor)
            iconContainer.imageTintList = ColorStateList.valueOf(iconColor)

            rowTitle.text = title
            rowDescription.text = description
            val status = item.status
            statusBadge.isVisible = status != null
            if (status != null) statusBadge.setStatus(status)
            // Only rows that really open something show an arrow.
            chevron.isVisible = status == null || status == BadgeStatus.AVAILABLE

            root.setOnClickListener { item.onClick() }
            root.contentDescription = if (status == null) context.getString(R.string.cd_feature_row_plain, title, description)
            else context.getString(R.string.cd_feature_row, title, description, context.getString(status.labelRes))
        }
    }
}
