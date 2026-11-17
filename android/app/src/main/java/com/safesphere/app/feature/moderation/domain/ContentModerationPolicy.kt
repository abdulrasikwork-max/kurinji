package com.safesphere.app.feature.moderation.domain

/**
 * Phase 13D (pure; ContentModerationPolicyTest): the phone's mirror of the server's rules for content decisions, so
 * the case screen offers only what the server would accept. The server checks everything again.
 */
object ContentModerationPolicy {

    /** The rule-breaking reasons (also the strike and pause reasons). */
    val VIOLATIONS: List<ContentDecisionReason> = ContentDecisionReason.entries
        .filter { it != ContentDecisionReason.NO_VIOLATION && it != ContentDecisionReason.NEEDS_REVIEW }

    /** The reasons a moderator may give for [action]. */
    fun reasonsFor(action: ContentAction): List<ContentDecisionReason> = when (action) {
        ContentAction.CONTENT_KEEP, ContentAction.PERSON_KEEP -> listOf(ContentDecisionReason.NO_VIOLATION)
        ContentAction.CONTENT_HOLD -> listOf(ContentDecisionReason.NEEDS_REVIEW) + VIOLATIONS
        else -> VIOLATIONS
    }

    /** The actions offered: the server's list, none for a case the moderator is part of. */
    fun actionsFor(detail: ContentCaseDetail): List<ContentAction> =
        if (detail.summary.youTookPart) emptyList() else detail.possibleActions

    /** The reason preselected for a decision: the most serious reason reported, when it fits. */
    fun defaultReason(action: ContentAction, detail: ContentCaseDetail): ContentDecisionReason {
        val options = reasonsFor(action)
        val top = detail.summary.topReason
        return if (top != null && top in options) top else options.first()
    }

    /** Decisions that change what other people can see or do (asked to confirm first). */
    fun needsConfirmation(action: ContentAction): Boolean =
        action != ContentAction.CONTENT_KEEP && action != ContentAction.PERSON_KEEP
}

/** N4-A3 (pure, tested): how a moment case reads for a moderator. */
object MomentCaseText {

    /** The text the automatic check read (the server's `MomentShared.words`): the words, then the "where". */
    fun checkedText(content: CaseContent): String = when {
        content.body == null -> content.planWhere.orEmpty()
        content.planWhere == null -> content.body
        else -> content.body + "\n" + content.planWhere
    }

    /**
     * The headline reason as a code: the decision reason when it is one, else the server's own code (ABOUT_ME,
     * NEW_MEMBER_PHOTO, AWAITING_CHECK), else ABOUT_ME for a case "This is about me" opened; null = unknown.
     */
    fun headlineReason(summary: ContentCaseSummary): String? =
        summary.topReason?.name ?: summary.topReasonCode ?: if (summary.openedBy == "ABOUT_ME") "ABOUT_ME" else null
}
