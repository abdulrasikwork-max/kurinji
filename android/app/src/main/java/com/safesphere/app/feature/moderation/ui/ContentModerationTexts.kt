package com.safesphere.app.feature.moderation.ui

import androidx.annotation.StringRes
import com.safesphere.app.R
import com.safesphere.app.feature.moderation.domain.ContentAction
import com.safesphere.app.feature.moderation.domain.ContentDecisionReason
import com.safesphere.app.feature.moderation.domain.ContentTarget

/** Phase 13D: the words of the moderators' content screens (codes stay codes; no accusation of a person). */
object ContentModerationTexts {

    @StringRes
    fun of(target: ContentTarget?): Int = when (target) {
        ContentTarget.POST -> R.string.content_target_post
        ContentTarget.COMMENT -> R.string.content_target_comment
        ContentTarget.PERSON, null -> R.string.content_target_person
    }

    @StringRes
    fun of(action: ContentAction): Int = when (action) {
        ContentAction.CONTENT_KEEP -> R.string.content_action_keep
        ContentAction.CONTENT_HOLD -> R.string.content_action_hold
        ContentAction.CONTENT_COLLAPSE -> R.string.content_action_collapse
        ContentAction.CONTENT_REMOVE -> R.string.content_action_remove
        ContentAction.PERSON_KEEP -> R.string.content_action_person_keep
        ContentAction.PERSON_WARN -> R.string.content_action_warn
        ContentAction.PERSON_PAUSE_COMMENTS -> R.string.content_action_pause_comments
        ContentAction.PERSON_PAUSE_POSTING -> R.string.content_action_pause_posting
        ContentAction.PERSON_SUSPEND -> R.string.content_action_suspend
    }

    @StringRes
    fun of(reason: ContentDecisionReason?): Int = when (reason) {
        null -> R.string.moderation_reason_unknown
        ContentDecisionReason.NO_VIOLATION -> R.string.content_reason_no_violation
        ContentDecisionReason.NEEDS_REVIEW -> R.string.moderation_reason_needs_review
        ContentDecisionReason.HARASSMENT -> R.string.report_reason_harassment
        ContentDecisionReason.SEXUAL_HARASSMENT -> R.string.report_reason_sexual_harassment
        ContentDecisionReason.HATE -> R.string.report_reason_hate
        ContentDecisionReason.THREAT -> R.string.report_reason_threat
        ContentDecisionReason.DOXXING -> R.string.report_reason_doxxing
        ContentDecisionReason.SPAM -> R.string.report_reason_spam
        ContentDecisionReason.NUDITY_OR_VIOLENCE -> R.string.report_reason_nudity
        ContentDecisionReason.IMPERSONATION -> R.string.report_reason_impersonation
        ContentDecisionReason.OTHER -> R.string.report_reason_other
    }

    @StringRes
    fun state(code: String?): Int = when (code) {
        "COLLAPSED" -> R.string.content_state_collapsed
        "HELD" -> R.string.content_state_held
        "REMOVED" -> R.string.content_state_removed
        else -> R.string.content_state_none
    }
}
