package com.safesphere.app.feature.social.ui

import android.text.InputFilter
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.safesphere.app.R
import com.safesphere.app.feature.social.domain.AppealState
import com.safesphere.app.feature.social.domain.Collapsed
import com.safesphere.app.feature.social.domain.ModerationReason
import com.safesphere.app.feature.social.domain.ModerationState
import com.safesphere.app.feature.social.domain.OwnModeration
import com.safesphere.app.feature.social.domain.ReportReason
import com.safesphere.app.feature.social.domain.ReportTarget

/**
 * Phase 13D: the words of moderation for members (design §9.9): calm, amber, never red, never "you are …". Reports are
 * never mentioned to the person concerned; a reporter is told only that a moderator will look and that nobody is told
 * who reported.
 */
object ModerationLabels {

    @StringRes
    fun of(reason: ReportReason): Int = when (reason) {
        ReportReason.HARASSMENT -> R.string.report_reason_harassment
        ReportReason.SEXUAL_HARASSMENT -> R.string.report_reason_sexual_harassment
        ReportReason.HATE -> R.string.report_reason_hate
        ReportReason.THREAT -> R.string.report_reason_threat
        ReportReason.DOXXING -> R.string.report_reason_doxxing
        ReportReason.SPAM -> R.string.report_reason_spam
        ReportReason.NUDITY_OR_VIOLENCE -> R.string.report_reason_nudity
        ReportReason.IMPERSONATION -> R.string.report_reason_impersonation
        ReportReason.OTHER -> R.string.report_reason_other
    }

    /** A moderator's reason, as the person concerned reads it (null: no reason given yet). */
    @StringRes
    fun of(reason: ModerationReason?): Int = when (reason) {
        null -> R.string.moderation_reason_unknown
        ModerationReason.NEEDS_REVIEW -> R.string.moderation_reason_needs_review
        ModerationReason.AWAITING_CHECK -> R.string.moderation_reason_awaiting_check
        else -> of(ReportReason.valueOf(reason.name))
    }

    /**
     * The amber notice on one's own post or perspective: %1$s = the reason, %2$s = the marked phrase line (empty when
     * none). Phase 13E: the automatic check speaks for itself ("Our system thinks…"), never as a moderator.
     */
    @StringRes
    fun ownNotice(moderation: OwnModeration): Int = when {
        moderation.bySystem && moderation.state == ModerationState.HELD &&
            moderation.reason == ModerationReason.AWAITING_CHECK -> R.string.own_waiting_check
        moderation.bySystem && moderation.state == ModerationState.HELD &&
            moderation.reason == ModerationReason.NEEDS_REVIEW -> R.string.own_held_new_photos
        moderation.bySystem && moderation.state == ModerationState.HELD -> R.string.own_held_by_system
        moderation.bySystem -> R.string.own_collapsed_by_system
        moderation.state == ModerationState.COLLAPSED -> if (moderation.decisionId == null)
            R.string.own_collapsed_while_reviewed else R.string.own_collapsed_by_moderator
        moderation.state == ModerationState.HELD -> R.string.own_held
        else -> R.string.own_removed
    }

    /** What others read on a collapsed post or perspective (never who, never why in detail). */
    @StringRes
    fun collapsedLine(collapsed: Collapsed?): Int = when (collapsed) {
        Collapsed.BY_MODERATOR -> R.string.collapsed_by_moderator
        Collapsed.BY_SYSTEM -> R.string.collapsed_by_system
        Collapsed.UNDER_REVIEW -> R.string.collapsed_under_review
        null -> R.string.collapsed_strong_language
    }

    @StringRes
    fun appealLine(state: AppealState): Int? = when (state) {
        AppealState.NONE -> null
        AppealState.OPEN -> R.string.review_waiting
        AppealState.UPHELD -> R.string.review_upheld
        AppealState.OVERTURNED -> R.string.review_overturned
    }
}

/** The report dialogs: a reason (the closed list), then a short note for "Something else". */
object ReportDialogs {

    fun show(fragment: Fragment, target: ReportTarget, onReport: (ReportReason, String?) -> Unit) {
        val context = fragment.requireContext()
        val reasons = ReportReason.forTarget(target)
        var chosen = -1
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.report_title)
            .setSingleChoiceItems(reasons.map { context.getString(ModerationLabels.of(it)) }.toTypedArray(), -1) { _, which ->
                chosen = which
            }
            .setNeutralButton(R.string.guidelines_title) { _, _ -> fragment.findNavController().navigate(R.id.guidelinesFragment) }
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.report_send) { _, _ ->
                val reason = reasons.getOrNull(chosen) ?: return@setPositiveButton
                if (reason == ReportReason.OTHER) askNote(fragment, target, onReport) else onReport(reason, null)
            }
            .show()
    }

    private fun askNote(fragment: Fragment, target: ReportTarget, onReport: (ReportReason, String?) -> Unit) {
        val context = fragment.requireContext()
        val input = EditText(context).apply {
            setHint(R.string.report_note_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf(InputFilter.LengthFilter(ReportReason.NOTE_MAX))
        }
        val padding = context.resources.getDimensionPixelSize(R.dimen.screen_padding)
        val frame = FrameLayout(context).apply {
            setPadding(padding, 0, padding, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.report_note_title)
            .setView(frame)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.report_send) { _, _ ->
                val note = input.text?.toString().orEmpty()
                if (ReportReason.accepts(target, ReportReason.OTHER, note)) onReport(ReportReason.OTHER, note)
            }
            .show()
    }
}
