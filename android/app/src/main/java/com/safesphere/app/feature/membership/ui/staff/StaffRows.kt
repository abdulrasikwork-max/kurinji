package com.safesphere.app.feature.membership.ui.staff

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.safesphere.app.R
import com.safesphere.app.databinding.ItemStaffRowBinding

/** One row of a staff list: a title, a detail line, an optional action button and an optional tap on the row. */
data class StaffRow(
    val key: String,
    val title: String,
    val detail: String,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null,
    val onOpen: (() -> Unit)? = null
)

class StaffRowAdapter : RecyclerView.Adapter<StaffRowAdapter.Holder>() {

    private var rows: List<StaffRow> = emptyList()

    fun submit(newRows: List<StaffRow>) {
        rows = newRows
        @Suppress("NotifyDataSetChanged") // short lists (≤ a few pages), replaced as a whole
        notifyDataSetChanged()
    }

    override fun getItemCount() = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemStaffRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = rows[position]
        with(holder.binding) {
            rowTitle.text = row.title
            rowDetail.text = row.detail
            rowDetail.isVisible = row.detail.isNotEmpty()
            rowAction.isVisible = row.actionLabel != null
            rowAction.text = row.actionLabel
            rowAction.contentDescription = row.actionLabel?.let { "$it: ${row.title}" }
            rowAction.setOnClickListener { row.onAction?.invoke() }
            root.isClickable = row.onOpen != null
            root.setOnClickListener(row.onOpen?.let { open -> { _: android.view.View -> open() } })
        }
    }

    class Holder(val binding: ItemStaffRowBinding) : RecyclerView.ViewHolder(binding.root)
}

/** The staff-facing words for log actions and reasons (codes the app does not know are shown as they are). */
object StaffLabels {

    @StringRes
    fun action(action: String): Int? = when (action) {
        "VERIFIED" -> R.string.staff_log_verified
        "REFUSED" -> R.string.staff_log_refused
        "NAME_CORRECTED" -> R.string.staff_log_name_corrected
        "VERIFIER_ADDED" -> R.string.staff_log_verifier_added
        "VERIFIER_REMOVED" -> R.string.staff_log_verifier_removed
        "ENDED" -> R.string.staff_log_ended
        "BANNED" -> R.string.staff_log_banned
        "BAN_LIFTED" -> R.string.staff_log_ban_lifted
        "RELINKED" -> R.string.staff_log_relinked
        else -> null
    }

    /** A reason code of the log in words (refusals, ends, bans); null for a code the app does not know. */
    @StringRes
    fun reason(code: String): Int? = when (code) {
        "GRADUATED" -> R.string.staff_reason_graduated
        "REMOVED" -> R.string.staff_reason_removed
        "NAME_MISMATCH", "ROLL_MISMATCH", "NOT_THE_PERSON", "NOT_MIET_ID", "UNREADABLE", "ALREADY_MEMBER", "BANNED" ->
            com.safesphere.app.feature.membership.ui.MembershipLabels.refusal(code)
        "HARASSMENT", "HATE", "THREATS", "SEXUAL_CONTENT", "IMPERSONATION", "FALSE_ID", "PRIVACY", "REPEATED", "OTHER" ->
            com.safesphere.app.feature.membership.ui.MembershipLabels.ban(code)
        else -> null
    }

    @StringRes
    fun refusal(reason: com.safesphere.app.feature.membership.domain.RefusalReason): Int = when (reason) {
        com.safesphere.app.feature.membership.domain.RefusalReason.NAME_MISMATCH -> R.string.staff_refuse_name
        com.safesphere.app.feature.membership.domain.RefusalReason.ROLL_MISMATCH -> R.string.staff_refuse_roll
        com.safesphere.app.feature.membership.domain.RefusalReason.NOT_THE_PERSON -> R.string.staff_refuse_person
        com.safesphere.app.feature.membership.domain.RefusalReason.NOT_MIET_ID -> R.string.staff_refuse_not_miet
        com.safesphere.app.feature.membership.domain.RefusalReason.UNREADABLE -> R.string.staff_refuse_unreadable
        com.safesphere.app.feature.membership.domain.RefusalReason.OTHER -> R.string.staff_refuse_other
    }
}
