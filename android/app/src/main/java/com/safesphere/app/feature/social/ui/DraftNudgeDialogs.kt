package com.safesphere.app.feature.social.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.safesphere.app.R
import com.safesphere.app.appContainer
import com.safesphere.app.core.textcheck.DraftNudge
import com.safesphere.app.core.textcheck.DraftNudge.Nudge
import com.safesphere.app.core.textcheck.TextSpans
import com.safesphere.app.feature.social.domain.ReportReason

/**
 * Phase 13E: the private nudge before a post or a perspective is shared (design §6.2: "Edit / Post anyway"). The draft
 * is checked on the phone only; nothing is sent or stored for it, and the person can always share. A self-harm signal
 * shows a support line instead (never a block). [onlyMe]: a private note is never nudged about others' feelings.
 */
object DraftNudgeDialogs {

    fun beforeSharing(fragment: Fragment, text: String, onlyMe: Boolean, share: () -> Unit) {
        val check = fragment.appContainer.textCheck
        val nudge = check?.let { DraftNudge.of(it.check(text)) } ?: Nudge.None
        val context = fragment.requireContext()
        when {
            nudge is Nudge.Support -> MaterialAlertDialogBuilder(context)
                .setTitle(R.string.nudge_support_title)
                .setMessage(R.string.nudge_support_message)
                .setNeutralButton(R.string.nudge_support_call) { _, _ -> dialHelpline(fragment) }
                .setNegativeButton(R.string.nudge_support_back, null)
                .setPositiveButton(R.string.nudge_support_share) { _, _ -> share() }
                .show()
            onlyMe || nudge is Nudge.None -> share()
            nudge is Nudge.Care -> MaterialAlertDialogBuilder(context)
                .setTitle(R.string.nudge_care_title)
                .setMessage(context.getString(R.string.nudge_care_message, context.getString(categoryLabel(nudge.category)),
                    phrases(text, nudge.spans)))
                .setNegativeButton(R.string.nudge_edit, null)
                .setPositiveButton(R.string.nudge_share_anyway) { _, _ -> share() }
                .show()
            nudge is Nudge.Contact -> MaterialAlertDialogBuilder(context)
                .setTitle(R.string.nudge_contact_title)
                .setMessage(context.getString(R.string.nudge_contact_message, phrases(text, nudge.spans)))
                .setNegativeButton(R.string.nudge_edit, null)
                .setPositiveButton(R.string.nudge_share_anyway) { _, _ -> share() }
                .show()
            else -> share()
        }
    }

    /** The marked words of [text] (code-point ranges), joined; at most three, each at most 60 characters. */
    fun phrases(text: String, spans: List<IntRange>): String = TextSpans.phrases(text, spans)

    /** A server reason code as the person reads it (the report-reason words). */
    fun categoryLabel(category: String): Int =
        ModerationLabels.of(ReportReason.entries.firstOrNull { it.name == category } ?: ReportReason.OTHER)

    /** The phone's dialler with the national helpline filled in; the person decides whether to call (no CALL_PHONE). */
    private fun dialHelpline(fragment: Fragment) {
        try {
            fragment.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + HELPLINE)))
        } catch (e: ActivityNotFoundException) {
            // No dialler (a tablet): the number is in the message the person just read.
        }
    }

    /** Tele-MANAS, India's national mental-health helpline (free, all day and night, Tamil among its languages). */
    const val HELPLINE = "14416"
}
