package com.safesphere.app.feature.moments.domain

import com.safesphere.app.core.network.ApiResult
import java.time.Instant

/*
 * Phase N5: how people answer a moment (docs/PHASE_N5.md, D14 to D19). Same and Say more on a Say, I'm in on a Who's in?,
 * a private reply on both. Nothing here is a number: other people get a band ("a few feel the same"), the author gets
 * names. Nothing is stored on the phone.
 */

/** D14: how many OTHER people feel the same, never as a number: 1, 2 to 5, 6 or more. */
enum class SameBand { SOMEONE, A_FEW, MANY }

/** [mine] = the reader said Same; [band] = the other people (null when nobody else did). */
data class SameState(val mine: Boolean, val band: SameBand?)

/** D15: the author's one follow-up. [at] is the exact time for the author only; others get [shared] (rough). */
data class FollowUp(val text: String, val shared: RoughTime?, val at: Instant?)

/** Where the reader stands in a Who's in?: AUTHOR, NONE (has not asked), ASKED, IN, REMOVED. Never "declined" (D16). */
enum class PlanStanding { AUTHOR, NONE, ASKED, IN, REMOVED }

/**
 * A Who's in? for the reader. [open] = asks are still taken; [planId] = the plan's conversation (the author and the
 * people in it, once someone was added).
 */
data class PlanState(val you: PlanStanding, val open: Boolean, val calledOff: Boolean, val planId: String?)

/** The author's own lines: up to 3 names each, and whether there are more. Never sent to anyone else. */
data class AuthorLines(
    val same: List<String>,
    val otherSame: Boolean,
    val sayMore: List<String>,
    val otherSayMore: Boolean,
    val asking: List<String>,
    val otherAsking: Boolean,
    val inPlan: List<String>,
    val otherIn: Boolean
) {
    val hasSame: Boolean get() = same.isNotEmpty()
    val hasSayMore: Boolean get() = sayMore.isNotEmpty()
    val hasAsking: Boolean get() = asking.isNotEmpty()
    val hasIn: Boolean get() = inPlan.isNotEmpty()
}

/**
 * Everything about responses the reader may know. [same] null for the author (they get [author] names instead);
 * [canReply] = they may start a private reply now; [replyId] = their own private reply with the author.
 */
data class MomentResponses(
    val same: SameState?,
    val sayMore: Boolean,
    val more: FollowUp?,
    val plan: PlanState?,
    val canReply: Boolean,
    val replyId: String?,
    val author: AuthorLines?
) {
    companion object {
        val NONE = MomentResponses(null, false, null, null, false, null, null)
    }
}

/** N-92: the plan a This happened Say came from (its words, shortened by the server). */
data class HappenedRef(val id: String, val text: String?)

/** One person in the author's people sheet (or the plan's list for someone in it). [ref] is what the author acts on. */
data class PlanPerson(
    val ref: String?,
    val name: String,
    val profileId: String?,
    val photo: String?,
    val programme: String?,
    val year: Int?,
    val myPeople: Boolean
)

/** The author's lists (D16): who wants in, who is in, who was set aside ("Not this time"), and who felt or asked. */
data class PlanPeople(
    val same: List<PlanPerson>,
    val sayMore: List<PlanPerson>,
    val asking: List<PlanPerson>,
    val setAside: List<PlanPerson>,
    val inPlan: List<PlanPerson>
)

/**
 * Phase N5: responses on the backend. Each answer is the moment as it is now (or the people lists), so a screen never
 * guesses. A refused answer comes with the server's own words (409, 403 for a pause).
 */
interface ResponseRepository {
    suspend fun same(momentId: String, on: Boolean): ApiResult<Moment>
    suspend fun sayMore(momentId: String, on: Boolean): ApiResult<Moment>
    suspend fun addMore(momentId: String, text: String): ApiResult<Moment>
    suspend fun askIn(momentId: String): ApiResult<Moment>
    suspend fun leave(momentId: String): ApiResult<Unit>
    suspend fun people(momentId: String): ApiResult<PlanPeople>
    suspend fun add(momentId: String, ref: String): ApiResult<PlanPeople>
    suspend fun setAside(momentId: String, ref: String, aside: Boolean): ApiResult<PlanPeople>
    suspend fun remove(momentId: String, ref: String): ApiResult<PlanPeople>
    suspend fun callOff(momentId: String): ApiResult<Moment>
}
