package com.safesphere.app.feature.community.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.safesphere.app.databinding.ItemMyContributionBinding
import com.safesphere.app.feature.community.domain.MyContribution
import com.safesphere.app.feature.community.domain.MyContributionStatus

/** The person's own reports and answers (only they see these), each with "Withdraw". */
class MyContributionAdapter(
    private val onWithdraw: (MyContribution) -> Unit
) : ListAdapter<MyContribution, MyContributionAdapter.Holder>(Diff) {

    class Holder(val binding: ItemMyContributionBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemMyContributionBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = getItem(position)
        val context = holder.itemView.context
        with(holder.binding) {
            myTitle.text = context.getString(CommunityTexts.category(item.category))
            myStatus.text = context.getString(
                com.safesphere.app.R.string.my_contribution_line,
                context.getString(CommunityTexts.yourKind(item.kind)),
                context.getString(CommunityTexts.myStatus(item.status))
            )
            // Phase 7: while moderators review it the server refuses a withdrawal, so it is not offered.
            withdrawButton.isVisible = item.status != MyContributionStatus.UNDER_REVIEW
            withdrawButton.setOnClickListener { onWithdraw(item) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<MyContribution>() {
        override fun areItemsTheSame(oldItem: MyContribution, newItem: MyContribution) = oldItem.signalId == newItem.signalId
        override fun areContentsTheSame(oldItem: MyContribution, newItem: MyContribution) = oldItem == newItem
    }
}
