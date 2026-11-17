package com.safesphere.app.feature.moments.data.remote

import com.safesphere.app.feature.moments.domain.AuthorLines
import com.safesphere.app.feature.moments.domain.DayPart
import com.safesphere.app.feature.moments.domain.FollowUp
import com.safesphere.app.feature.moments.domain.HappenedRef
import com.safesphere.app.feature.moments.domain.MomentKind
import com.safesphere.app.feature.moments.domain.MomentResponses
import com.safesphere.app.feature.moments.domain.PlanPeople
import com.safesphere.app.feature.moments.domain.PlanPerson
import com.safesphere.app.feature.moments.domain.PlanStanding
import com.safesphere.app.feature.moments.domain.PlanState
import com.safesphere.app.feature.moments.domain.RoughDay
import com.safesphere.app.feature.moments.domain.RoughTime
import com.safesphere.app.feature.moments.domain.SameBand
import com.safesphere.app.feature.moments.domain.SameState
import java.time.Instant

// Phase N5: the "responses" part of a moment (docs/API.md "Phase N5"). Read fail-closed: an unknown band shows no band,
// an unknown standing shows no plan controls (the server refuses anything not allowed anyway), names must be names.

data class SameDto(val mine: Boolean? = null, val band: String? = null)

data class SayMoreDto(val mine: Boolean? = null)

data class MoreDto(val text: String? = null, val `when`: WhenDto? = null, val at: String? = null)

data class PlanViewDto(
    val you: String? = null,
    val open: Boolean? = null,
    val calledOff: Boolean? = null,
    val planId: String? = null
)

data class ForAuthorDto(
    val same: List<String?>? = null,
    val otherSame: Boolean? = null,
    val sayMore: List<String?>? = null,
    val otherSayMore: Boolean? = null,
    val asking: List<String?>? = null,
    val otherAsking: Boolean? = null,
    val `in`: List<String?>? = null,
    val otherIn: Boolean? = null
)

data class ResponsesDto(
    val same: SameDto? = null,
    val sayMore: SayMoreDto? = null,
    val more: MoreDto? = null,
    val plan: PlanViewDto? = null,
    val canReply: Boolean? = null,
    val replyId: String? = null,
    val author: ForAuthorDto? = null
) {
    fun toDomain(kind: MomentKind, mine: Boolean): MomentResponses = MomentResponses(
        // Same is on a Say, for others; the author gets names.
        same = if (kind == MomentKind.SAY && !mine) same?.let {
            SameState(it.mine == true, SameBand.entries.firstOrNull { b -> b.name == it.band })
        } ?: SameState(false, null) else null,
        sayMore = kind == MomentKind.SAY && !mine && sayMore?.mine == true,
        more = if (kind == MomentKind.SAY) more?.let { m ->
            m.text?.trim()?.takeIf { it.isNotEmpty() }?.let { FollowUp(it, rough(m.`when`), if (mine) instant(m.at) else null) }
        } else null,
        plan = if (kind == MomentKind.WHOS_IN) plan?.let { p ->
            val you = if (mine) PlanStanding.AUTHOR
                else PlanStanding.entries.firstOrNull { it.name == p.you && it != PlanStanding.AUTHOR } ?: return@let null
            PlanState(you, p.open == true, p.calledOff == true, uuid(p.planId))
        } else null,
        canReply = !mine && canReply == true,
        replyId = if (mine) null else uuid(replyId),
        author = if (mine) author?.let { a ->
            AuthorLines(names(a.same), a.otherSame == true, names(a.sayMore), a.otherSayMore == true, names(a.asking),
                a.otherAsking == true, names(a.`in`), a.otherIn == true)
        } else null
    )
}

data class HappenedRefDto(val id: String? = null, val text: String? = null) {
    fun toDomain(): HappenedRef? = uuid(id)?.let { HappenedRef(it, text?.trim()?.takeIf { t -> t.isNotEmpty() }) }
}

data class PersonDto(
    val ref: String? = null,
    val name: String? = null,
    val profileId: String? = null,
    val photo: String? = null,
    val programme: String? = null,
    val year: Int? = null,
    val myPeople: Boolean? = null
) {
    fun toDomain(): PlanPerson? {
        val shown = name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return PlanPerson(uuid(ref), shown, uuid(profileId), uuid(photo), programme?.takeIf { CODE.matches(it) },
            year?.takeIf { it in 2000..2100 }, myPeople == true)
    }
}

data class PeopleDto(
    val same: List<PersonDto?>? = null,
    val sayMore: List<PersonDto?>? = null,
    val asking: List<PersonDto?>? = null,
    val setAside: List<PersonDto?>? = null,
    val `in`: List<PersonDto?>? = null
) {
    fun toDomain() = PlanPeople(list(same), list(sayMore), list(asking), list(setAside), list(`in`))

    private fun list(raw: List<PersonDto?>?) = raw.orEmpty().mapNotNull { it?.toDomain() }
}

/** Body of POST /api/moments/{id}/more. */
data class TextBodyDto(val text: String) {
    override fun toString() = "TextBodyDto(<words hidden>)"
}

internal fun rough(w: WhenDto?): RoughTime? = w?.let {
    RoughDay.entries.firstOrNull { d -> d.name == it.day }?.let { day -> RoughTime(day, DayPart.entries.firstOrNull { p -> p.name == it.part }) }
}

private fun instant(raw: String?): Instant? = raw?.let { runCatching { Instant.parse(it) }.getOrNull() }

/** At most 3 real names (a blank one is no name). */
private fun names(raw: List<String?>?): List<String> = raw.orEmpty().mapNotNull { it?.trim()?.takeIf { n -> n.isNotEmpty() } }.take(3)

private val CODE = Regex("^[A-Z_]{1,30}$")
