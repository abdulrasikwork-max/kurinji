package com.safesphere.app.feature.moderation.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.safesphere.app.R
import com.safesphere.app.databinding.ItemModerationAppealBinding
import com.safesphere.app.databinding.ItemModerationCaseBinding
import com.safesphere.app.databinding.ItemModerationParticipantBinding
import com.safesphere.app.feature.community.ui.CommunityTexts
import com.safesphere.app.feature.moderation.domain.AppealItem
import com.safesphere.app.feature.moderation.domain.CaseParticipant
import com.safesphere.app.feature.moderation.domain.CaseSummary
import com.safesphere.app.feature.moderation.domain.ModerationPolicy
import com.safesphere.app.feature.moderation.domain.SignalModeration

/** Open cases, oldest first. A case the moderator took part in is labelled and not opened. */
class CaseAdapter(private val onOpen: (CaseSummary) -> Unit) :
    ListAdapter<CaseSummary, CaseAdapter.Holder>(CaseDiff) {

    class Holder(val binding: ItemModerationCaseBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemModerationCaseBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val case = getItem(position)
        val context = holder.itemView.context
        with(holder.binding) {
            caseTitle.setText(CommunityTexts.category(case.category))
            caseLine.text = ModerationTexts.caseLine(context, case)
            caseState.text = context.getString(
                when {
                    case.youTookPart -> R.string.case_you_took_part
                    case.moderation == SignalModeration.QUARANTINED -> R.string.case_hidden
                    case.moderation == SignalModeration.REMOVED -> R.string.case_removed
                    else -> R.string.case_visible
                }
            )
            caseCard.isEnabled = !case.youTookPart
            caseCard.setOnClickListener { if (!case.youTookPart) onOpen(case) }
        }
    }
}

/** Open appeals. Uphold / lift are not offered for the moderator's own decision (separation of duties). */
class AppealAdapter(private val onDecide: (AppealItem, Boolean) -> Unit) :
    ListAdapter<AppealItem, AppealAdapter.Holder>(AppealDiff) {

    class Holder(val binding: ItemModerationAppealBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemModerationAppealBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val appeal = getItem(position)
        val context = holder.itemView.context
        with(holder.binding) {
            appealTitle.text = context.getString(R.string.appeal_line,
                context.getString(ModerationTexts.reason(appeal.restrictionReason)), appeal.restrictedUntil ?: "?")
            val category = appeal.category?.let { context.getString(CommunityTexts.category(it)) } ?: ""
            appealDetails.text = context.getString(R.string.appeal_details, category,
                context.getString(ModerationTexts.tier(appeal.currentTier)), appeal.strikes)
            val canDecide = ModerationPolicy.canDecide(appeal)
            appealButtons.isVisible = canDecide
            appealOwn.isVisible = !canDecide
            upholdButton.setOnClickListener { onDecide(appeal, false) }
            overturnButton.setOnClickListener { onDecide(appeal, true) }
        }
    }
}

/** People in one case, by case pseudonym only. */
class ParticipantAdapter(private val onRestrict: (CaseParticipant) -> Unit) :
    ListAdapter<CaseParticipant, ParticipantAdapter.Holder>(ParticipantDiff) {

    /** Set by the screen: whether "Pause" is offered at all on this case. */
    var canRestrict: (CaseParticipant) -> Boolean = { false }

    class Holder(val binding: ItemModerationParticipantBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemModerationParticipantBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val participant = getItem(position)
        val context = holder.itemView.context
        with(holder.binding) {
            participantRef.text = context.getString(R.string.participant_ref, participant.ref.take(6))
            participantLine.text = ModerationTexts.participant(context, participant)
            restrictButton.isVisible = canRestrict(participant)
            // TalkBack: "Pause Person 70a0a6", not the same bare "Pause" for every row.
            restrictButton.contentDescription = context.getString(R.string.cd_pause_participant, participantRef.text)
            restrictButton.setOnClickListener { onRestrict(participant) }
        }
    }
}

private object CaseDiff : DiffUtil.ItemCallback<CaseSummary>() {
    override fun areItemsTheSame(oldItem: CaseSummary, newItem: CaseSummary) = oldItem.id == newItem.id
    override fun areContentsTheSame(oldItem: CaseSummary, newItem: CaseSummary) = oldItem == newItem
}

private object AppealDiff : DiffUtil.ItemCallback<AppealItem>() {
    override fun areItemsTheSame(oldItem: AppealItem, newItem: AppealItem) = oldItem.id == newItem.id
    override fun areContentsTheSame(oldItem: AppealItem, newItem: AppealItem) = oldItem == newItem
}

private object ParticipantDiff : DiffUtil.ItemCallback<CaseParticipant>() {
    override fun areItemsTheSame(oldItem: CaseParticipant, newItem: CaseParticipant) =
        oldItem.ref == newItem.ref && oldItem.role == newItem.role && oldItem.flag == newItem.flag
    override fun areContentsTheSame(oldItem: CaseParticipant, newItem: CaseParticipant) = oldItem == newItem
}
