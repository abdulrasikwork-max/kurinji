package com.safesphere.app.feature.emergency.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.safesphere.app.R
import com.safesphere.app.core.ui.components.BadgeStatus
import com.safesphere.app.databinding.ItemCircleMemberBinding
import com.safesphere.app.feature.circlealert.ui.DeliveryLabel
import com.safesphere.app.feature.safetycircle.domain.SafetyContact

/**
 * The person's Safety Circle on the active-emergency screen. Phase 4D: each row says honestly what that contact has
 * been told ([statusFor]) and offers "Text" ([onText]) to open the SMS app with a ready message.
 */
class CircleMemberAdapter(
    private val members: List<SafetyContact>,
    private val statusFor: (SafetyContact) -> DeliveryLabel? = { null },
    private val onText: ((SafetyContact) -> Unit)? = null
) : RecyclerView.Adapter<CircleMemberAdapter.MemberViewHolder>() {

    class MemberViewHolder(val binding: ItemCircleMemberBinding) : RecyclerView.ViewHolder(binding.root)

    override fun getItemCount() = members.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = MemberViewHolder(
        ItemCircleMemberBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: MemberViewHolder, position: Int) {
        val member = members[position]
        val context = holder.itemView.context
        with(holder.binding) {
            memberName.text = member.name
            memberDetails.text = context.getString(
                R.string.contact_details_format, context.getString(member.relationship.labelRes), member.phoneNumber
            )
            memberPrimary.isVisible = member.isPrimary
            if (member.isPrimary) memberPrimary.setStatus(BadgeStatus.PRIMARY)

            val label = statusFor(member)
            memberAlertStatus.isVisible = label != null
            if (label != null) {
                memberAlertStatus.text = label.at?.let { context.getString(label.text, EmergencyTimeFormatter.formatTime(it)) }
                    ?: context.getString(label.text)
            }
            memberTextButton.isVisible = onText != null
            memberTextButton.contentDescription = context.getString(R.string.cd_text_contact, member.name)
            memberTextButton.setOnClickListener { onText?.invoke(member) }
        }
    }
}
