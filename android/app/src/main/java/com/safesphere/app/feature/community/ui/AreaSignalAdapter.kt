package com.safesphere.app.feature.community.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.safesphere.app.R
import com.safesphere.app.databinding.ItemAreaSignalBinding
import com.safesphere.app.feature.community.domain.AreaSignal
import com.safesphere.app.feature.community.domain.CommunityPolicy
import com.safesphere.app.feature.community.domain.CommunityPolicy.PlacedSignal
import com.safesphere.app.feature.community.domain.ContributionKind
import com.safesphere.app.feature.community.domain.SignalGroup

/**
 * Nearby signals, nearest first. No author, no time, no count: only what the place is like and how sure the system
 * is (Phase 7 confidence, explained under "How sure is this?"). A flag ("Report a problem") goes to moderators.
 */
class AreaSignalAdapter(
    private val onAnswer: (AreaSignal, ContributionKind) -> Unit,
    private val onWhy: (AreaSignal) -> Unit,
    private val onFlag: (AreaSignal) -> Unit
) : ListAdapter<PlacedSignal, AreaSignalAdapter.Holder>(Diff) {

    class Holder(val binding: ItemAreaSignalBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemAreaSignalBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val placed = getItem(position)
        val signal = placed.signal
        val context = holder.itemView.context
        val title = context.getString(CommunityTexts.category(signal.category))
        val details = CommunityTexts.details(context, placed)
        val (badge, badgeLabel) = CommunityTexts.badge(signal.category.group)

        with(holder.binding) {
            signalTitle.text = title
            signalDetails.text = details
            signalBadge.setStatus(badge, badgeLabel)
            signalIcon.setImageResource(
                when (signal.category.group) {
                    SignalGroup.HAVEN -> R.drawable.ic_shield
                    SignalGroup.CONDITION -> R.drawable.ic_place
                    SignalGroup.INCIDENT -> R.drawable.ic_warning
                }
            )
            signalDisputed.isVisible = signal.disputed

            val canAnswer = CommunityPolicy.canAnswer(signal)
            answerButtons.isVisible = canAnswer
            yourAnswer.isVisible = !canAnswer
            when {
                signal.yourContribution != null -> yourAnswer.setText(CommunityTexts.yourKind(signal.yourContribution))
                signal.youFlagged -> yourAnswer.setText(R.string.your_flag)
            }
            confirmButton.setOnClickListener { onAnswer(signal, ContributionKind.CONFIRM) }
            noLongerButton.setOnClickListener { onAnswer(signal, ContributionKind.NO_LONGER) }
            whyButton.setOnClickListener { onWhy(signal) }
            flagButton.isVisible = CommunityPolicy.canFlag(signal)
            flagButton.setOnClickListener { onFlag(signal) }

            signalSummary.contentDescription = context.getString(R.string.cd_area_signal, title, context.getString(badgeLabel), details)
        }
    }

    private object Diff : DiffUtil.ItemCallback<PlacedSignal>() {
        override fun areItemsTheSame(oldItem: PlacedSignal, newItem: PlacedSignal) = oldItem.signal.id == newItem.signal.id
        override fun areContentsTheSame(oldItem: PlacedSignal, newItem: PlacedSignal) = oldItem == newItem
    }
}
