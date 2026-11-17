package com.safesphere.app.feature.moments.ui

import android.content.Context
import androidx.annotation.StringRes
import com.safesphere.app.R
import com.safesphere.app.feature.moments.domain.Moment
import com.safesphere.app.feature.moments.domain.MomentKind
import com.safesphere.app.feature.moments.domain.MomentStatus
import com.safesphere.app.feature.moments.domain.PlanStanding
import com.safesphere.app.feature.moments.domain.PlanState
import com.safesphere.app.feature.moments.domain.SameBand
import com.safesphere.app.feature.moments.domain.SameState

/**
 * One line about responses: [res] takes the joined [names] ("Asha, Ravi and others") or [arg] as its first argument,
 * or nothing. Never a number (D14).
 */
data class ResponseWords(
    @StringRes val res: Int,
    val names: List<String> = emptyList(),
    val others: Boolean = false,
    val arg: String? = null
)

/** What the reader may do with a moment now (N5 §3, the response matrix). The server decides again on every tap. */
data class ResponseActions(
    val same: Boolean,
    val sayMore: Boolean,
    val askIn: Boolean,
    /** Take back an ask, or leave a plan one is in ("… can't make it"). */
    val withdraw: Boolean,
    /** The author's one follow-up (D15). */
    val addMore: Boolean,
    /** The author's lists (who feels the same, wants more, wants in, is in). */
    val people: Boolean,
    val callOff: Boolean
) {
    val any: Boolean get() = same || sayMore || askIn || withdraw || addMore || people || callOff

    companion object {
        val NONE = ResponseActions(false, false, false, false, false, false, false)
    }
}

/**
 * Phase N5: the words for responses. Pure (resource ids only), so the rules are unit tested:
 * - D14: the author gets names, never a band; everyone else a band of the OTHER people; on a Today card the band shows
 *   only after the reader's own Same.
 * - D16: an asker is never told "declined": "Waiting for …", then "You're in", "This plan has closed", "Called off" or
 *   "You're not in this plan any more".
 */
object ResponseLabels {

    /** D14 for everyone but the author, on the moment's page. Null = no line. */
    @StringRes
    fun same(state: SameState?): Int? {
        if (state == null) return null
        return if (state.mine) when (state.band) {
            null -> R.string.response_same_you
            SameBand.SOMEONE -> R.string.response_same_you_someone
            SameBand.A_FEW -> R.string.response_same_you_few
            SameBand.MANY -> R.string.response_same_you_many
        } else when (state.band) {
            null -> null
            SameBand.SOMEONE -> R.string.response_same_someone
            SameBand.A_FEW -> R.string.response_same_few
            SameBand.MANY -> R.string.response_same_many
        }
    }

    /** D14 on a Today card: the band only after the reader said Same themselves. */
    @StringRes
    fun sameOnCard(state: SameState?): Int? = if (state?.mine == true) same(state) else null

    /**
     * Where the reader stands in someone's Who's in? (D16). [onCard]: a card stays quiet for someone who has not
     * asked (only "Called off" is said to everyone).
     */
    fun standing(plan: PlanState?, authorName: String, onCard: Boolean): ResponseWords? {
        if (plan == null || plan.you == PlanStanding.AUTHOR) return null
        return when {
            plan.you == PlanStanding.REMOVED -> ResponseWords(R.string.response_plan_removed)
            plan.calledOff -> ResponseWords(R.string.response_plan_called_off)
            plan.you == PlanStanding.IN -> ResponseWords(R.string.response_plan_in)
            plan.you == PlanStanding.ASKED && plan.open -> ResponseWords(R.string.response_plan_waiting, arg = authorName)
            plan.you == PlanStanding.ASKED -> ResponseWords(R.string.response_plan_closed)
            !plan.open && !onCard -> ResponseWords(R.string.response_plan_not_taking)
            else -> null
        }
    }

    /** The author's own lines, in the order they matter: who waits for an answer first. Names only (D14, D15, D16). */
    fun author(moment: Moment): List<ResponseWords> {
        val lines = moment.responses.author
        val out = ArrayList<ResponseWords>()
        when (moment.kind) {
            MomentKind.SAY -> {
                if (moment.responses.more != null) {
                    out += ResponseWords(R.string.response_author_said_more)
                } else if (lines != null && lines.hasSayMore) {
                    out += names(lines.sayMore, lines.otherSayMore, R.string.response_author_say_more_one,
                        R.string.response_author_say_more_many)
                }
                if (lines != null && lines.hasSame) {
                    out += names(lines.same, lines.otherSame, R.string.response_author_same_one, R.string.response_author_same_many)
                }
            }
            MomentKind.WHOS_IN -> {
                if (moment.responses.plan?.calledOff == true) out += ResponseWords(R.string.response_author_called_off)
                if (lines != null && lines.hasAsking) {
                    out += names(lines.asking, lines.otherAsking, R.string.response_author_asking_one,
                        R.string.response_author_asking_many)
                }
                if (lines != null && lines.hasIn) {
                    out += names(lines.inPlan, lines.otherIn, R.string.response_author_in_one, R.string.response_author_in_many)
                }
            }
        }
        return out
    }

    /** The ONE quiet line under a card (Today, profiles): the author's first line, or the reader's own. */
    fun cardLine(moment: Moment): ResponseWords? = when {
        moment.mine -> author(moment).firstOrNull()
        moment.kind == MomentKind.SAY -> sameOnCard(moment.responses.same)?.let { ResponseWords(it) }
        else -> standing(moment.responses.plan, moment.author.name, onCard = true)
    }

    /** What the reader can do here now. Responses only while the moment is on Today; never on one's own (N5 §3). */
    fun actions(moment: Moment): ResponseActions {
        val live = moment.status == MomentStatus.LIVE
        val r = moment.responses
        val plan = r.plan
        return if (moment.mine) {
            val shown = live && !moment.hiddenFromOthers
            ResponseActions(
                same = false, sayMore = false, askIn = false, withdraw = false,
                addMore = moment.kind == MomentKind.SAY && shown && r.more == null && r.author?.hasSayMore == true,
                people = r.author?.let { it.hasSame || it.hasSayMore || it.hasAsking || it.hasIn } == true,
                callOff = moment.kind == MomentKind.WHOS_IN && plan != null && !plan.calledOff &&
                    moment.status != MomentStatus.WAITING && (plan.open || plan.planId != null)
            )
        } else {
            ResponseActions(
                same = moment.kind == MomentKind.SAY && live && r.same != null,
                // Once the author said more, asking again means nothing.
                sayMore = moment.kind == MomentKind.SAY && live && r.more == null,
                askIn = moment.kind == MomentKind.WHOS_IN && live && plan != null && plan.you == PlanStanding.NONE &&
                    plan.open && !plan.calledOff,
                // Nobody is ever trapped in a plan: leaving is always offered while they are in it or asked.
                withdraw = plan != null && !plan.calledOff && (plan.you == PlanStanding.IN ||
                    (plan.you == PlanStanding.ASKED && plan.open)),
                addMore = false,
                // Someone in the plan sees who else is in it (the server answers them that list only).
                people = plan?.you == PlanStanding.IN && !plan.calledOff,
                callOff = false
            )
        }
    }

    /** "Take it back" for an ask, "I can't make it" for someone in. */
    @StringRes
    fun withdrawLabel(plan: PlanState?): Int =
        if (plan?.you == PlanStanding.IN) R.string.response_action_cant_make_it else R.string.response_action_take_back

    private fun names(names: List<String>, others: Boolean, @StringRes one: Int, @StringRes many: Int) =
        ResponseWords(if (names.size == 1 && !others) one else many, names, others)

    /** The line in words (needs the resources): names joined as "Asha", "Asha and Ravi", "Asha, Ravi and others". */
    fun text(context: Context, words: ResponseWords): String = when {
        words.names.isNotEmpty() -> context.getString(words.res, joined(context, words.names, words.others))
        words.arg != null -> context.getString(words.res, words.arg)
        else -> context.getString(words.res)
    }

    fun joined(context: Context, names: List<String>, others: Boolean): String = when {
        others -> context.getString(R.string.response_names_others, names.joinToString(", "))
        names.size == 1 -> names[0]
        names.size == 2 -> context.getString(R.string.response_names_two, names[0], names[1])
        else -> context.getString(R.string.response_names_two, names.dropLast(1).joinToString(", "), names.last())
    }
}
