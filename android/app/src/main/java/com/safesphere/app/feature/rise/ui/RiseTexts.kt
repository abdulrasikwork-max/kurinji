package com.safesphere.app.feature.rise.ui

import android.content.res.Resources
import androidx.annotation.StringRes
import com.safesphere.app.R
import com.safesphere.app.feature.rise.domain.Expectation
import com.safesphere.app.feature.rise.domain.Goal
import com.safesphere.app.feature.rise.domain.GoalSummary
import com.safesphere.app.feature.rise.domain.Outcome
import com.safesphere.app.feature.rise.domain.Replay
import com.safesphere.app.feature.rise.domain.RiseDomain
import com.safesphere.app.feature.rise.domain.SelfDoubt
import com.safesphere.app.feature.rise.domain.Support

/**
 * Rise's words. The codes come from the domain; every sentence is a string resource scanned by RiseTextsTest.
 * Sentences are built so that TalkBack reads each card as ONE sentence.
 */
object RiseTexts {

    @StringRes
    fun domain(domain: RiseDomain): Int = when (domain) {
        RiseDomain.GO -> R.string.rise_domain_go
        RiseDomain.LEARN -> R.string.rise_domain_learn
        RiseDomain.WORK -> R.string.rise_domain_work
        RiseDomain.LEAD -> R.string.rise_domain_lead
        RiseDomain.MOVE -> R.string.rise_domain_move
        RiseDomain.MONEY -> R.string.rise_domain_money
        RiseDomain.DECIDE -> R.string.rise_domain_decide
        RiseDomain.BE_ME -> R.string.rise_domain_be_me
        RiseDomain.OTHER -> R.string.rise_domain_other
    }

    /** Suggested steps per domain: about the ACTIVITY, never "without support" (StepTemplatesTest). */
    fun templates(domain: RiseDomain): List<Int> = when (domain) {
        RiseDomain.GO -> listOf(R.string.rise_template_go_1, R.string.rise_template_go_2, R.string.rise_template_go_3)
        RiseDomain.LEARN -> listOf(R.string.rise_template_learn_1, R.string.rise_template_learn_2, R.string.rise_template_learn_3)
        RiseDomain.WORK -> listOf(R.string.rise_template_work_1, R.string.rise_template_work_2, R.string.rise_template_work_3,
            R.string.rise_template_work_4)
        RiseDomain.LEAD -> listOf(R.string.rise_template_lead_1, R.string.rise_template_lead_2, R.string.rise_template_lead_3)
        RiseDomain.MOVE -> listOf(R.string.rise_template_move_1, R.string.rise_template_move_2, R.string.rise_template_move_3)
        RiseDomain.MONEY -> listOf(R.string.rise_template_money_1, R.string.rise_template_money_2, R.string.rise_template_money_3)
        RiseDomain.DECIDE -> listOf(R.string.rise_template_decide_1, R.string.rise_template_decide_2,
            R.string.rise_template_decide_3, R.string.rise_template_decide_4)
        RiseDomain.BE_ME -> listOf(R.string.rise_template_be_me_1)
        RiseDomain.OTHER -> emptyList()
    }

    @StringRes
    fun expectation(expectation: Expectation): Int = when (expectation) {
        Expectation.CANT -> R.string.rise_expect_cant
        Expectation.NOT_SURE -> R.string.rise_expect_not_sure
        Expectation.PROBABLY_FINE -> R.string.rise_expect_fine
        Expectation.CONFIDENT -> R.string.rise_expect_confident
    }

    @StringRes
    fun doubt(doubt: SelfDoubt): Int = when (doubt) {
        SelfDoubt.CAN_I -> R.string.rise_doubt_can_i
        SelfDoubt.ON_MY_OWN -> R.string.rise_doubt_on_my_own
        SelfDoubt.NOT_FOR_SOMEONE_LIKE_ME -> R.string.rise_doubt_not_for_me
        SelfDoubt.WHAT_OTHERS_THINK -> R.string.rise_doubt_others
        SelfDoubt.NOT_GOOD_ENOUGH_YET -> R.string.rise_doubt_not_good_enough
        SelfDoubt.NEVER_DONE_THIS -> R.string.rise_doubt_never
    }

    @StringRes
    fun support(support: Support): Int = when (support) {
        Support.WITH_SOMEONE -> R.string.rise_support_with_someone
        Support.TIME_I_CHOOSE -> R.string.rise_support_time
        Support.WAY_I_KNOW -> R.string.rise_support_way
        Support.PRACTISE_FIRST -> R.string.rise_support_practise
        Support.SMALLER_VERSION -> R.string.rise_support_smaller
        Support.SOMEONE_KNOWS -> R.string.rise_support_someone_knows
    }

    @StringRes
    fun outcome(outcome: Outcome): Int = when (outcome) {
        Outcome.BETTER -> R.string.rise_outcome_better
        Outcome.AS_EXPECTED -> R.string.rise_outcome_as_expected
        Outcome.HARDER -> R.string.rise_outcome_harder
        Outcome.DID_NOT_GO_AHEAD -> R.string.rise_outcome_not_ahead
        Outcome.NOT_OK -> R.string.rise_outcome_not_ok
    }

    /** What the person sees right after saving an outcome (never a judgement). */
    @StringRes
    fun afterOutcome(outcome: Outcome): Int = when (outcome) {
        Outcome.BETTER, Outcome.AS_EXPECTED -> R.string.rise_after_proof
        Outcome.HARDER -> R.string.rise_after_harder
        Outcome.DID_NOT_GO_AHEAD -> R.string.rise_after_not_ahead
        Outcome.NOT_OK -> R.string.rise_not_ok_title
    }

    /** The replay card, honest: parts with zero are left out; nothing persuasive is added when it was mostly harder. */
    fun replay(resources: Resources, replay: Replay): String = buildList {
        add(resources.getQuantityString(R.plurals.rise_replay_times, replay.times, replay.times))
        if (replay.better > 0) add(resources.getQuantityString(R.plurals.rise_replay_better, replay.better, replay.better))
        if (replay.asExpected > 0) add(resources.getQuantityString(R.plurals.rise_replay_as_expected, replay.asExpected, replay.asExpected))
        if (replay.harder > 0) add(resources.getQuantityString(R.plurals.rise_replay_harder, replay.harder, replay.harder))
        if (replay.notAhead > 0) add(resources.getQuantityString(R.plurals.rise_replay_not_ahead, replay.notAhead, replay.notAhead))
    }.joinToString(" ")

    /** Per-goal line in "Your proof" (only when the person was unsure at least once). */
    fun summary(resources: Resources, summary: GoalSummary): String? {
        if (summary.unsure == 0) return null
        val unsure = resources.getQuantityString(R.plurals.rise_summary_unsure, summary.unsure, summary.unsure)
        if (summary.unsureBetter == 0) return unsure
        return unsure + " " + resources.getQuantityString(R.plurals.rise_summary_better, summary.unsureBetter, summary.unsureBetter)
    }

    /** A goal's title in a list: the person's words, or "Goal 2 · Learn" in quiet mode / when nothing was written. */
    fun goalTitle(resources: Resources, goal: Goal, number: Int, quiet: Boolean): String {
        val domain = resources.getString(domain(goal.domain))
        return when {
            quiet -> resources.getString(R.string.rise_goal_quiet, number, domain)
            goal.words.isBlank() -> domain
            else -> goal.words
        }
    }
}
